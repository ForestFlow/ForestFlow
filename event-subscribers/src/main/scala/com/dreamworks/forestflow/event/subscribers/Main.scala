/**
 * Copyright 2019 DreamWorks Animation L.L.C.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dreamworks.forestflow.event.subscribers

import java.net.InetAddress

import akka.Done
import akka.actor.ActorSystem
import akka.actor.CoordinatedShutdown.PhaseBeforeActorSystemTerminate
import akka.cluster.Cluster
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object Main extends StrictLogging {
  def main(args: Array[String]): Unit = {
    import com.dreamworks.forestflow.startup.ActorSystemStartup._

    preStartup(typeSafeConfig)

    logger.info(s"Started system: [$system], cluster.selfAddress = ${cluster.selfAddress}")

    shutdown.addTask(PhaseBeforeActorSystemTerminate, "main.cleanup") { () => cleanup(typeSafeConfig) }

    bootstrapCluster(system, cluster)

    logger.info(s"Sharding lease owner for this node will be set to: ${cluster.selfAddress.hostPort}")

    // Start application after self member joined the cluster (Up)
    cluster.registerOnMemberUp({
      logger.info(s"Cluster Member is up: ${cluster.selfMember.toString()}")
      postStartup
    })

  }

  private def bootstrapCluster(system: ActorSystem, cluster: Cluster): Unit = {
    // Akka Management hosts the HTTP routes used by bootstrap
    AkkaManagement(system).start()

    // Starting the bootstrap process needs to be done explicitly
    ClusterBootstrap(system).start()

    system.log.info(s"App: Akka Management hostname from InetAddress.getLocalHost.getHostAddress is: ${InetAddress.getLocalHost.getHostAddress}")
  }

  private def preStartup(config: Config): Unit = {

  }

  private def postStartup(implicit system: ActorSystem, config: Config): Unit = {
    // Kafka Prediction Logger setup
    import system.log

    val basic_topic = Try(config.getString("application.kafka-prediction-logger.basic-topic")).toOption
    val gp_topic = Try(config.getString("application.kafka-prediction-logger.graphpipe-topic")).toOption

    if (basic_topic.isDefined || gp_topic.isDefined){
      log.info(s"App: Setting up Kafka prediction logging with basic_topic: $basic_topic graphpipe_topic: $gp_topic")
      val predictionLogger = system.actorOf(PredictionLogger.props(basic_topic, gp_topic))
    }
  }

  private def cleanup(config: Config)(implicit executionContext: ExecutionContext) = Future {
    Done
  }
}
