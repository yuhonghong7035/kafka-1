/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.admin

import kafka.log.Log
import kafka.zk.ZooKeeperTestHarness
import kafka.utils.{TestUtils, ZkUtils}
import kafka.utils.ZkUtils._
import kafka.server.{KafkaConfig, KafkaServer}
import org.junit.Assert._
import org.junit.{After, Test}
import java.util.Properties

import kafka.common.{TopicAlreadyMarkedForDeletionException, TopicAndPartition}
import kafka.controller._
import org.apache.kafka.common.{KafkaException, TopicPartition}
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException

class DeleteTopicTest extends ZooKeeperTestHarness {

  var servers: Seq[KafkaServer] = Seq()

  @After
  override def tearDown() {
    TestUtils.shutdownServers(servers)
    super.tearDown()
  }

  @Test
  def testDeleteTopicWithAllAliveReplicas() {
    val topicPartition = new TopicPartition("test", 0)
    val topic = topicPartition.topic
    servers = createTestTopicAndCluster(topic)
    // start topic deletion
    AdminUtils.deleteTopic(zkUtils, topic)
    TestUtils.verifyTopicDeletion(zkUtils, topic, 1, servers)
  }

  @Test
  def testResumeDeleteTopicWithRecoveredFollower() {
    val topicPartition = new TopicPartition("test", 0)
    val topic = topicPartition.topic
    servers = createTestTopicAndCluster(topic)
    // shut down one follower replica
    val leaderIdOpt = zkUtils.getLeaderForPartition(topic, 0)
    assertTrue("Leader should exist for partition [test,0]", leaderIdOpt.isDefined)
    val follower = servers.filter(s => s.config.brokerId != leaderIdOpt.get).last
    follower.shutdown()
    // start topic deletion
    AdminUtils.deleteTopic(zkUtils, topic)
    // check if all replicas but the one that is shut down has deleted the log
    TestUtils.waitUntilTrue(() =>
      servers.filter(s => s.config.brokerId != follower.config.brokerId)
        .forall(_.getLogManager().getLog(topicPartition).isEmpty), "Replicas 0,1 have not deleted log.")
    // ensure topic deletion is halted
    TestUtils.waitUntilTrue(() => zkUtils.isTopicMarkedForDeletion(topic),
      "Admin path /admin/delete_topic/test path deleted even when a follower replica is down")
    // restart follower replica
    follower.startup()
    TestUtils.verifyTopicDeletion(zkUtils, topic, 1, servers)
  }

  @Test
  def testResumeDeleteTopicOnControllerFailover() {
    val topicPartition = new TopicPartition("test", 0)
    val topic = topicPartition.topic
    servers = createTestTopicAndCluster(topic)
    val controllerId = zkUtils.getController()
    val controller = servers.filter(s => s.config.brokerId == controllerId).head
    val leaderIdOpt = zkUtils.getLeaderForPartition(topic, 0)
    val follower = servers.filter(s => s.config.brokerId != leaderIdOpt.get && s.config.brokerId != controllerId).last
    follower.shutdown()

    // start topic deletion
    AdminUtils.deleteTopic(zkUtils, topic)
    // shut down the controller to trigger controller failover during delete topic
    controller.shutdown()

    // ensure topic deletion is halted
    TestUtils.waitUntilTrue(() => zkUtils.isTopicMarkedForDeletion(topic),
      "Admin path /admin/delete_topic/test path deleted even when a replica is down")

    controller.startup()
    follower.startup()

    TestUtils.verifyTopicDeletion(zkUtils, topic, 1, servers)
  }

  @Test
  def testPartitionReassignmentDuringDeleteTopic() {
    val expectedReplicaAssignment = Map(0 -> List(0, 1, 2))
    val topic = "test"
    val topicPartition = new TopicPartition(topic, 0)
    val brokerConfigs = TestUtils.createBrokerConfigs(4, zkConnect, false)
    brokerConfigs.foreach(p => p.setProperty("delete.topic.enable", "true"))
    // create brokers
    val allServers = brokerConfigs.map(b => TestUtils.createServer(KafkaConfig.fromProps(b)))
    this.servers = allServers
    val servers = allServers.filter(s => expectedReplicaAssignment(0).contains(s.config.brokerId))
    // create the topic
    AdminUtils.createOrUpdateTopicPartitionAssignmentPathInZK(zkUtils, topic, expectedReplicaAssignment)
    // wait until replica log is created on every broker
    TestUtils.waitUntilTrue(() => servers.forall(_.getLogManager().getLog(topicPartition).isDefined),
      "Replicas for topic test not created.")
    val leaderIdOpt = zkUtils.getLeaderForPartition(topic, 0)
    assertTrue("Leader should exist for partition [test,0]", leaderIdOpt.isDefined)
    val follower = servers.filter(s => s.config.brokerId != leaderIdOpt.get).last
    follower.shutdown()
    // start topic deletion
    AdminUtils.deleteTopic(zkUtils, topic)
    // start partition reassignment at the same time right after delete topic. In this case, reassignment will fail since
    // the topic is being deleted
    // reassign partition 0
    val oldAssignedReplicas = zkUtils.getReplicasForPartition(topic, 0)
    val newReplicas = Seq(1, 2, 3)
    val reassignPartitionsCommand = new ReassignPartitionsCommand(zkUtils, None, Map(new TopicAndPartition(topicPartition) -> newReplicas))
    assertTrue("Partition reassignment should fail for [test,0]", reassignPartitionsCommand.reassignPartitions())
    // wait until reassignment is completed
    TestUtils.waitUntilTrue(() => {
      val partitionsBeingReassigned = zkUtils.getPartitionsBeingReassigned().mapValues(_.newReplicas)
      ReassignPartitionsCommand.checkIfPartitionReassignmentSucceeded(zkUtils, new TopicAndPartition(topicPartition),
        Map(new TopicAndPartition(topicPartition) -> newReplicas), partitionsBeingReassigned) == ReassignmentFailed
    }, "Partition reassignment shouldn't complete.")
    val controllerId = zkUtils.getController()
    val controller = servers.filter(s => s.config.brokerId == controllerId).head
    assertFalse("Partition reassignment should fail",
      controller.kafkaController.controllerContext.partitionsBeingReassigned.contains(new TopicAndPartition(topicPartition)))
    val assignedReplicas = zkUtils.getReplicasForPartition(topic, 0)
    assertEquals("Partition should not be reassigned to 0, 1, 2", oldAssignedReplicas, assignedReplicas)
    follower.startup()
    TestUtils.verifyTopicDeletion(zkUtils, topic, 1, servers)
  }

  @Test
  def testDeleteTopicDuringAddPartition() {
    val topic = "test"
    servers = createTestTopicAndCluster(topic)
    val leaderIdOpt = zkUtils.getLeaderForPartition(topic, 0)
    assertTrue("Leader should exist for partition [test,0]", leaderIdOpt.isDefined)
    val follower = servers.filter(s => s.config.brokerId != leaderIdOpt.get).last
    val newPartition = new TopicPartition(topic, 1)
    follower.shutdown()
    // add partitions to topic
    AdminUtils.addPartitions(zkUtils, topic, 2, "0:1:2,0:1:2", false)
    // start topic deletion
    AdminUtils.deleteTopic(zkUtils, topic)
    follower.startup()
    // test if topic deletion is resumed
    TestUtils.verifyTopicDeletion(zkUtils, topic, 1, servers)
    // verify that new partition doesn't exist on any broker either
    TestUtils.waitUntilTrue(() =>
      servers.forall(_.getLogManager().getLog(newPartition).isEmpty),
      "Replica logs not for new partition [test,1] not deleted after delete topic is complete.")
  }

  @Test
  def testAddPartitionDuringDeleteTopic() {
    val topic = "test"
    servers = createTestTopicAndCluster(topic)
    // start topic deletion
    AdminUtils.deleteTopic(zkUtils, topic)
    // add partitions to topic
    val newPartition = new TopicPartition(topic, 1)
    AdminUtils.addPartitions(zkUtils, topic, 2, "0:1:2,0:1:2")
    TestUtils.verifyTopicDeletion(zkUtils, topic, 1, servers)
    // verify that new partition doesn't exist on any broker either
    assertTrue("Replica logs not deleted after delete topic is complete",
      servers.forall(_.getLogManager().getLog(newPartition).isEmpty))
  }

  @Test
  def testRecreateTopicAfterDeletion() {
    val expectedReplicaAssignment = Map(0 -> List(0, 1, 2))
    val topic = "test"
    val topicPartition = new TopicPartition(topic, 0)
    servers = createTestTopicAndCluster(topic)
    // start topic deletion
    AdminUtils.deleteTopic(zkUtils, topic)
    TestUtils.verifyTopicDeletion(zkUtils, topic, 1, servers)
    // re-create topic on same replicas
    AdminUtils.createOrUpdateTopicPartitionAssignmentPathInZK(zkUtils, topic, expectedReplicaAssignment)
    // wait until leader is elected
    TestUtils.waitUntilLeaderIsElectedOrChanged(zkUtils, topic, 0, 1000)
    // check if all replica logs are created
    TestUtils.waitUntilTrue(() => servers.forall(_.getLogManager().getLog(topicPartition).isDefined),
      "Replicas for topic test not created.")
  }

  @Test
  def testDeleteNonExistingTopic() {
    val topicPartition = new TopicPartition("test", 0)
    val topic = topicPartition.topic
    servers = createTestTopicAndCluster(topic)
    // start topic deletion
    try {
      AdminUtils.deleteTopic(zkUtils, "test2")
      fail("Expected UnknownTopicOrPartitionException")
    } catch {
      case _: UnknownTopicOrPartitionException => // expected exception
    }
    // verify delete topic path for test2 is removed from zookeeper
    TestUtils.verifyTopicDeletion(zkUtils, "test2", 1, servers)
    // verify that topic test is untouched
    TestUtils.waitUntilTrue(() => servers.forall(_.getLogManager().getLog(topicPartition).isDefined),
      "Replicas for topic test not created")
    // test the topic path exists
    assertTrue("Topic test mistakenly deleted", zkUtils.pathExists(getTopicPath(topic)))
    // topic test should have a leader
    TestUtils.waitUntilLeaderIsElectedOrChanged(zkUtils, topic, 0, 1000)
  }

  @Test
  def testDeleteTopicWithCleaner() {
    val topicName = "test"
    val topicPartition = new TopicPartition(topicName, 0)
    val topic = topicPartition.topic

    val brokerConfigs = TestUtils.createBrokerConfigs(3, zkConnect, false)
    brokerConfigs.head.setProperty("delete.topic.enable", "true")
    brokerConfigs.head.setProperty("log.cleaner.enable","true")
    brokerConfigs.head.setProperty("log.cleanup.policy","compact")
    brokerConfigs.head.setProperty("log.segment.bytes","100")
    brokerConfigs.head.setProperty("log.cleaner.dedupe.buffer.size","1048577")

    servers = createTestTopicAndCluster(topic,brokerConfigs)

    // for simplicity, we are validating cleaner offsets on a single broker
    val server = servers.head
    val log = server.logManager.getLog(topicPartition).get

    // write to the topic to activate cleaner
    writeDups(numKeys = 100, numDups = 3,log)

    // wait for cleaner to clean
   server.logManager.cleaner.awaitCleaned(new TopicPartition(topicName, 0), 0)

    // delete topic
    AdminUtils.deleteTopic(zkUtils, "test")
    TestUtils.verifyTopicDeletion(zkUtils, "test", 1, servers)
  }

  @Test
  def testDeleteTopicAlreadyMarkedAsDeleted() {
    val topicPartition = new TopicPartition("test", 0)
    val topic = topicPartition.topic
    servers = createTestTopicAndCluster(topic)

    try {
      // start topic deletion
      AdminUtils.deleteTopic(zkUtils, topic)
      // try to delete topic marked as deleted
      AdminUtils.deleteTopic(zkUtils, topic)
      fail("Expected TopicAlreadyMarkedForDeletionException")
    }
    catch {
      case _: TopicAlreadyMarkedForDeletionException => // expected exception
    }

    TestUtils.verifyTopicDeletion(zkUtils, topic, 1, servers)
  }

  private def createTestTopicAndCluster(topic: String, deleteTopicEnabled: Boolean = true): Seq[KafkaServer] = {

    val brokerConfigs = TestUtils.createBrokerConfigs(3, zkConnect, false)
    brokerConfigs.foreach(p => p.setProperty("delete.topic.enable", deleteTopicEnabled.toString)
    )
    createTestTopicAndCluster(topic,brokerConfigs)
  }

  private def createTestTopicAndCluster(topic: String, brokerConfigs: Seq[Properties]): Seq[KafkaServer] = {
    val expectedReplicaAssignment = Map(0 -> List(0, 1, 2))
    val topicPartition = new TopicPartition(topic, 0)
    // create brokers
    val servers = brokerConfigs.map(b => TestUtils.createServer(KafkaConfig.fromProps(b)))
    // create the topic
    AdminUtils.createOrUpdateTopicPartitionAssignmentPathInZK(zkUtils, topic, expectedReplicaAssignment)
    // wait until replica log is created on every broker
    TestUtils.waitUntilTrue(() => servers.forall(_.getLogManager().getLog(topicPartition).isDefined),
      "Replicas for topic test not created")
    servers
  }

  private def writeDups(numKeys: Int, numDups: Int, log: Log): Seq[(Int, Int)] = {
    var counter = 0
    for (_ <- 0 until numDups; key <- 0 until numKeys) yield {
      val count = counter
      log.appendAsLeader(TestUtils.singletonRecords(value = counter.toString.getBytes, key = key.toString.getBytes), leaderEpoch = 0)
      counter += 1
      (key, count)
    }
  }

  @Test
  def testDisableDeleteTopic() {
    val topicPartition = new TopicPartition("test", 0)
    val topic = topicPartition.topic
    servers = createTestTopicAndCluster(topic, deleteTopicEnabled = false)
    // mark the topic for deletion
    AdminUtils.deleteTopic(zkUtils, "test")
    TestUtils.waitUntilTrue(() => !zkUtils.isTopicMarkedForDeletion(topic),
      "Admin path /admin/delete_topic/%s path not deleted even if deleteTopic is disabled".format(topic))
    // verify that topic test is untouched
    assertTrue(servers.forall(_.getLogManager().getLog(topicPartition).isDefined))
    // test the topic path exists
    assertTrue("Topic path disappeared", zkUtils.pathExists(getTopicPath(topic)))
    // topic test should have a leader
    val leaderIdOpt = zkUtils.getLeaderForPartition(topic, 0)
    assertTrue("Leader should exist for topic test", leaderIdOpt.isDefined)
  }

  @Test
  def testDeleteTopicAfterEnableZkDeleteTopicFlag() {
    val topicPartition = new TopicPartition("test", 0)
    val topic = topicPartition.topic
    servers = createTestTopicAndCluster(topic, deleteTopicEnabled = false)
    // mark the topic for deletion
    AdminUtils.deleteTopic(zkUtils, "test")
    TestUtils.waitUntilTrue(() => !zkUtils.isTopicMarkedForDeletion(topic),
      "Admin path /admin/delete_topic/%s path not deleted even if deleteTopic is disabled".format(topic))
    // verify that topic test is untouched
    assertTrue(servers.forall(_.getLogManager().getLog(topicPartition).isDefined))
    // test the topic path exists
    assertTrue("Topic path disappeared even when topic deletion is disabled", zkUtils.pathExists(getTopicPath(topic)))
    // topic test should have a leader
    val leaderIdOpt = zkUtils.getLeaderForPartition(topic, 0)
    assertTrue("Leader should exist for topic test", leaderIdOpt.isDefined)

    // Set TopicDeletionFlag to true in zk and try delete topic again
    zkUtils.updatePersistentPath(ZkUtils.TopicDeletionEnabledPath, "true")
    TestUtils.waitUntilTrue(() =>
      try {
        zkUtils.readData(ZkUtils.TopicDeletionEnabledPath)._1 == "true"
      } catch {
        case _: Throwable => false
      },
      "TopicDeletionFlag is not set")
    TestUtils.waitUntilTrue( () => getController()._1.kafkaController.topicDeletionManager.isDeleteTopicEnabled,
      "Delete topic is not enabled")
    // mark the topic for deletion
    AdminUtils.deleteTopic(zkUtils, "test")
    TestUtils.verifyTopicDeletion(zkUtils, "test", 1, servers)

    // Set TopicDeletionFlag to invalid value in zk
    zkUtils.updatePersistentPath(ZkUtils.TopicDeletionEnabledPath, "flase")
    TestUtils.waitUntilTrue(() =>
      try {
        zkUtils.readData(ZkUtils.TopicDeletionEnabledPath)._1 == "true"
      } catch {
        case _: Throwable => false
      },
      "TopicDeletionFlag is not overwritten")

    // delete TopicDeletionFlagPath in zk
    zkUtils.deletePath(ZkUtils.TopicDeletionEnabledPath)
    TestUtils.waitUntilTrue(() =>
      try {
        !zkUtils.pathExists(ZkUtils.TopicDeletionEnabledPath)
      } catch {
        case _: Throwable => false
      },
      "TopicDeletionFlagPath is not deleted")
    TestUtils.waitUntilTrue(() =>
      getController()._1.kafkaController.topicDeletionManager.isDeleteTopicEnabled == false,
      "Topic deletion flag is not rest"
    )
  }


  private def getController() : (KafkaServer, Int) = {
    val controllerId = zkUtils.getController
    val controller = servers.filter(s => s.config.brokerId == controllerId).head
    (controller, controllerId)
  }

  private def ensureControllerExists() = {
    TestUtils.waitUntilTrue(() => {
      try {
        getController()
        true
      } catch {
        case _: Throwable  => false
      }
    }, "Controller should eventually exist")
  }

  private def getAllReplicasFromAssignment(topic : String, assignment : Map[Int, Seq[Int]]) : Set[PartitionAndReplica] = {
    assignment.flatMap { case (partition, replicas) =>
      replicas.map {r => new PartitionAndReplica(topic, partition, r)}
    }.toSet
  }

  @Test
  def testDeletingMultipleTopicsWithOfflineBroker() {
    // create the cluster
    val brokerConfigs = TestUtils.createBrokerConfigs(3, zkConnect, enableControlledShutdown = false)
    brokerConfigs.foreach(_.setProperty("delete.topic.enable", "true"))
    servers = brokerConfigs.map(b => TestUtils.createServer(KafkaConfig.fromProps(b)))

    // create one topic with replicas assigned to broker 1 and 2
    val topic1Partition0 = new TopicPartition("topic1", 0)
    val topic1Partition1 = new TopicPartition("topic1", 1)
    val topic1 = topic1Partition0.topic
    val partitionReplicaAssignment1 = Map(0 -> List(1, 2), 1 -> List(1, 2))

    AdminUtils.createOrUpdateTopicPartitionAssignmentPathInZK(zkUtils, topic1, partitionReplicaAssignment1)

    // create another topic with replicas assigned to broker 0 and 1
    val topic2Partition0 = new TopicPartition("topic2", 0)
    val topic2 = topic2Partition0.topic
    val partitionReplicaAssignment2 = Map(0 -> List(0, 1))
    AdminUtils.createOrUpdateTopicPartitionAssignmentPathInZK(zkUtils, topic2, partitionReplicaAssignment2)

    // create a third topic with replicas assigned to broker 1 and 0
    val topic3Partition0 = new TopicPartition("topic3", 0)
    val topic3 = topic3Partition0.topic
    val partitionReplicaAssignment3 = Map(0 -> List(1, 0))
    AdminUtils.createOrUpdateTopicPartitionAssignmentPathInZK(zkUtils, topic3, partitionReplicaAssignment3)

    // fail broker 2 so that topic1 becomes a topic ineligible for deletion
    val broker2 = servers.filter(s => s.config.brokerId == 2).last
    broker2.shutdown()
    // delete topic1, topic2 and topic3
    AdminUtils.deleteTopic(zkUtils, topic1)
    AdminUtils.deleteTopic(zkUtils, topic2)
    AdminUtils.deleteTopic(zkUtils, topic3)

    /** verify that topic2 and topic3 can be deleted, even though
      * topic1 is ineligible for deletion
      */
    ensureControllerExists()
    val (controller, controllerId) = getController()


    val allReplicasForTopic1 = getAllReplicasFromAssignment(topic1, partitionReplicaAssignment1)
    TestUtils.waitUntilTrue(() => {
      val replicasInDeletionSuccessful = controller.kafkaController.replicaStateMachine.replicasInState(topic1, ReplicaDeletionSuccessful)
      val offlineReplicas = controller.kafkaController.replicaStateMachine.replicasInState(topic1, OfflineReplica)
      info(s"replicasInDeletionStarted ${replicasInDeletionSuccessful.mkString(",")} and offline replicas ${offlineReplicas.mkString(",")}")
      (allReplicasForTopic1 == (replicasInDeletionSuccessful union offlineReplicas) && offlineReplicas.nonEmpty)
    }, s"Not all replicas for topic $topic1 are in states of either ReplicaDeletionSuccessful or OfflineReplica (at least one)")

    // verify that topic2 and topic3 were deleted
    TestUtils.verifyTopicDeletion(zkUtils, topic2, 1, servers)
    TestUtils.verifyTopicDeletion(zkUtils, topic3, 1, servers)

    // bring up the failed broker, and verify the eventual topic deletion
    broker2.startup()
    TestUtils.verifyTopicDeletion(zkUtils, topic1, 2, servers)
  }

  @Test
  def testDeletingPartitiallyDeletedTopic() {
    /**
      * a previous controller could have deleted some partitions of a topic from ZK, but not all partitions, and then died
      * in that case, the new controller should be able to handle the partially deleted topic, and finish the deletion
      */

    /**
      * set up a partially deleted topic topic1 where the data of /brokers/topics/topic1 has 2 partitions and assigned replicas
      * but there is only one partition under /brokers/topics/topic1/partitions/
      */

    val brokerConfigs = TestUtils.createBrokerConfigs(3, zkConnect, enableControlledShutdown = false)
    brokerConfigs.foreach(_.setProperty("delete.topic.enable", "true"))
    servers = brokerConfigs.map(b => TestUtils.createServer(KafkaConfig.fromProps(b)))

    val topicPartitions =  Seq(new TopicPartition("topic1", 0), new TopicPartition("topic1", 1))
    val topic1 = "topic1"
    val partitionReplicaAssignment1 = Map(0 -> List(0, 1, 2), 1 -> List(0, 1, 2))

    AdminUtils.createOrUpdateTopicPartitionAssignmentPathInZK(zkUtils, topic1, partitionReplicaAssignment1)
    TestUtils.waitUntilTrue(() => servers.forall { server =>
      topicPartitions.forall {
        topicPartition => server.getLogManager().getLog(topicPartition).isDefined
      }
    }, "Not all replicas for topic %s are created.".format(topic1))

    zkUtils.deletePathRecursive(ZkUtils.getTopicPartitionPath(topic1, 1))

    // delete the topic
    AdminUtils.deleteTopic(zkUtils, topic1)

    TestUtils.verifyTopicDeletion(zkUtils, topic1, 2, servers)
  }
}
