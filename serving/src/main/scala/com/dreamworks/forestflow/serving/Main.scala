/**
 * Copyright 2020 DreamWorks Animation L.L.C.
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
package com.dreamworks.forestflow.serving

import _root_.akka.actor.ActorSystem
import com.dreamworks.forestflow.event.subscribers.PredictionLogger
import com.dreamworks.forestflow.serving.cluster.{ServableProxy, ServableRegistry}
import com.dreamworks.forestflow.serving.restapi.HTTPServer
import com.dreamworks.forestflow.startup.ClusterNodeStartup
import com.dreamworks.forestflow.startup.ActorSystemStartup.system.log
import com.typesafe.config.Config

import scala.util.{Failure, Success, Try}

//noinspection TypeAnnotation
object Main extends ClusterNodeStartup {

  def main(args: Array[String]): Unit = {
    Start(requiresLocalDirectory = true)
  }

  override def postStartup(implicit system: ActorSystem, config: Config): Unit = {
    import com.dreamworks.forestflow.startup.ActorSystemStartup.{cluster, shutdown}
    log.info(s"Starting ServableRegistry...")
    val registry = ServableRegistry.startClusterSharding(config.getString("application.local-working-dir"))
    log.info(s"Starting ServableProxy...")
    val proxy = ServableProxy.startClusterSharding(registry)
    log.info(s"Starting HTTP Server...")
    new HTTPServer(proxy)

    // Startup event-subscribers
    val basic_topic = Try(config.getString("application.kafka-prediction-logger.basic-topic")).toOption
    val gp_topic = Try(config.getString("application.kafka-prediction-logger.graphpipe-topic")).toOption

    if (basic_topic.isDefined || gp_topic.isDefined){
      log.info(s"Setting up Kafka prediction logging with basic_topic: $basic_topic graphpipe_topic: $gp_topic")
      val predictionLogger = system.actorOf(PredictionLogger.props(basic_topic, gp_topic))
    }

    super.postStartup
  }
}
