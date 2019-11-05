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
package com.dreamworks.forestflow.serving.restapi

import akka.Done
import akka.actor.CoordinatedShutdown.{PhaseServiceUnbind, Reason}
import akka.actor.SupervisorStrategy._
import akka.actor.{ActorRef, ActorSystem, CoordinatedShutdown}
import akka.cluster.Cluster
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.dreamworks.forestflow.akka.Supervisor
import com.dreamworks.forestflow.domain.ServableRoutes.GetProxyRoutes
import com.dreamworks.forestflow.serving.config.ApplicationEnvironment
import com.dreamworks.forestflow.utils.ThrowableImplicits._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

//noinspection TypeAnnotation
object HTTPServer {

  private final case object BindFailure extends Reason

}
//noinspection TypeAnnotation
class HTTPServer(servableProxyRef: ActorRef)(implicit system: ActorSystem, cluster: Cluster, shutdown: CoordinatedShutdown) {
  import HTTPServer._
  import system.log

  private implicit val materializer = ActorMaterializer()
  private implicit val executionContext = system.dispatcher
  implicit lazy val timeout: Timeout = Timeout(ApplicationEnvironment.HTTP_COMMAND_TIMEOUT_SECS seconds)

  private val address = ApplicationEnvironment.HTTP_BIND_ADDRESS
  private val port = ApplicationEnvironment.HTTP_PORT

  val routesSupervisor = system.actorOf(Supervisor.props {
    case _: ArithmeticException => Resume
    case _: Exception => Restart
  })


  private val servableRoutesActor = Await.result(
    routesSupervisor
      .ask(ServableRoutes.props(servableProxyRef))
      .mapTo[ActorRef], ApplicationEnvironment.HTTP_COMMAND_TIMEOUT_SECS second)

  servableRoutesActor.ask(GetProxyRoutes()).onComplete {
    case Success(r: Route) =>
      val bindingFuture = Http().bindAndHandle(r, address, port)
      bindingFuture.onComplete {
        case Success(bound) =>
          log.info(s"AKKA HTTP Server online at http://${bound.localAddress.getHostString}:${bound.localAddress.getPort}/")
          shutdown.addTask(PhaseServiceUnbind, "api.unbind") { () =>
            bound.terminate(5 seconds).map(_ => Done)
          }
        case Failure(e) =>
          log.error(s"AKKA HTTP Server could not start! Shutting down... ${e.printableStackTrace}")
          shutdown.run(BindFailure)
      }

    case Failure(e) =>
      log.error(s"Couldn't get dynamic HTTP routes from ServableRoutes actor! Shutting down... ${e.printableStackTrace}")
      shutdown.run(BindFailure)
  }


}
