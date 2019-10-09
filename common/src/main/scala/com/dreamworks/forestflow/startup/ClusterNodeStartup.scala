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
package com.dreamworks.forestflow.startup

import java.io.File
import java.net.InetAddress

import akka.Done
import akka.actor.ActorSystem
import akka.actor.CoordinatedShutdown.PhaseBeforeActorSystemTerminate
import akka.cluster.Cluster
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import com.dreamworks.forestflow.utils.ThrowableImplicits._
import com.dreamworks.forestflow.startup.{ActorSystemStartup => Startup}
import Startup._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

trait ClusterNodeStartup extends StrictLogging {


  private var requiresLocalDirectory: Boolean = false
  def Start(requiresLocalDirectory: Boolean): Unit = {
    this.requiresLocalDirectory = requiresLocalDirectory

    def localDirectorySetup(config: Config) = {
      if (requiresLocalDirectory) {
        val localWorkingDir = new File(config.getString("application.local-working-dir"))
        if (!localWorkingDir.exists())
          localWorkingDir.mkdirs()
      }
    }

    localDirectorySetup(typeSafeConfig)
    preStartup(typeSafeConfig)

    logger.info(s"Started local system: [$system], cluster.selfAddress = ${cluster.selfAddress}")

    shutdown.addTask(PhaseBeforeActorSystemTerminate, "main.cleanup") { () => cleanup(typeSafeConfig) }

    bootstrapCluster
  }

  private def bootstrapCluster(implicit system: ActorSystem, cluster: Cluster) : Unit = {
    system.log.info(s"Akka Management hostname from InetAddress.getLocalHost.getHostAddress is: ${InetAddress.getLocalHost.getHostAddress}")

    val uri = for {
      // Akka Management hosts the HTTP routes used by bootstrap
      mgmt <- AkkaManagement(system).start()

      // Starting the bootstrap process needs to be done explicitly
      boot <- Future {
        system.log.info("Starting Bootstrap ...")
        ClusterBootstrap(system).start()
      }
    } yield mgmt


    val result = Await.ready(uri, 10 seconds).value.get

    result match {
      case success@Success(u) =>
        system.log.info(s"Akka Management started successfully: ${u.toString()}")

        logger.info(s"Sharding lease owner for this node will be set to: ${cluster.selfAddress.hostPort}")
        // Start application after self member joined the cluster (Up)
        cluster.registerOnMemberUp({
          logger.info(s"Cluster Member is up: ${cluster.selfMember.toString()}")
          postStartup
        })

      case Failure(ex) =>
        logger.error(s"Problem with bootstrap: ${ex.printableStackTrace}")
        logger.error(s"System shutting down ...")
        system.terminate()
    }
  }

  def preStartup(config: Config): Unit = {}

  def postStartup(implicit system: ActorSystem, config: Config): Unit = {}

  def beforeActorSystemTerminate(config: Config): Unit = {}

  private def cleanup(config: Config)(implicit executionContext: ExecutionContext) : Future[Done] = Future {
    if (requiresLocalDirectory) {
      Try{
        if (config.getBoolean("application.shutdown.cleanup-local-dir")) {
          val localWorkingDir = new File(config.getString("application.local-working-dir"))
          if (localWorkingDir.exists()) {
            localWorkingDir.delete()
          }
        }
      } match {
        case Failure(ex) => logger.error(s"Failed local directory cleanup: ${ex.getMessage}")
        case Success(_) => Unit
      }
    }

    beforeActorSystemTerminate(config)
    Done
  }
}
