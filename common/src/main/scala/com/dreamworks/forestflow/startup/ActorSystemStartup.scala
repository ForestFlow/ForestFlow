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
package com.dreamworks.forestflow.startup

import akka.actor.{ActorSystem, CoordinatedShutdown}
import akka.cluster.Cluster
import akka.persistence.Persistence
import com.dreamworks.forestflow.serving.config.ApplicationEnvironment
import com.typesafe.config.Config

import scala.concurrent.ExecutionContextExecutor

object ActorSystemStartup {
  implicit val typeSafeConfig: Config = ApplicationEnvironment.config
  implicit val system: ActorSystem = ActorSystem(ApplicationEnvironment.SYSTEM_NAME, typeSafeConfig) // This needs to be unique across deployments. This also controls akka.management.cluster.bootstrap.contact-point-discovery.service-name used to lookup peers in service discovery
  implicit val cluster: Cluster = Cluster(system)
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher
  implicit val shutdown: CoordinatedShutdown = CoordinatedShutdown(system)
  implicit val persistence: Persistence = Persistence(system)
}
