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
package ai.forestflow.serving.restapi

import ai.forestflow.serving.cluster
import ai.forestflow.serving.config.ApplicationEnvironment
import ai.forestflow.serving.impl.ServableMetricsImpl
import ai.forestflow.serving.interfaces.{HasHTTPStatusCode, ServeRequest}
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, Timers}
import akka.cluster.Cluster
import akka.cluster.ddata.{DistributedData, Replicator}
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.`Content-Type`
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.MethodDirectives.post
import akka.http.scaladsl.server.directives.RouteDirectives.complete
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.{ByteString, Timeout}
import ai.forestflow.akka.flatbuffers.FlatBuffersSupport
import ai.forestflow.domain.ServableProxy._
import ai.forestflow.domain.ServableRoutes.GetProxyRoutes
import ai.forestflow.domain.ShimImplicits._
import ai.forestflow.domain._
import ai.forestflow.serving.cluster.ServableProxy
import ai.forestflow.serving.config.ApplicationEnvironment
import ai.forestflow.serving.impl.ServableMetricsImpl
import ai.forestflow.serving.interfaces.{HasHTTPStatusCode, ServeRequest}
import ai.forestflow.utils.GraphPipe._
import ai.forestflow.utils.ThrowableImplicits._
import com.google.flatbuffers.FlatBufferBuilder
import com.google.protobuf.{ByteString => protoBString}
import graphpipe.Req
import scalapb.GeneratedSealedOneof
import fr.davit.akka.http.scaladsl.marshallers.scalapb.ScalaPBJsonSupport

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}


object ServableRoutes {
  def props(servableProxyActor: ActorRef)(implicit system: ActorSystem, cluster: Cluster): Props = {
    Props(new ServableRoutes(servableProxyActor))
      .withDispatcher("blocking-io-dispatcher")
  }
}

class ServableRoutes(servableProxyActor: ActorRef)(implicit system: ActorSystem, cluster: Cluster) extends Actor
  with ActorLogging
  with Timers
  with FlatBuffersSupport
  with ScalaPBJsonSupport {

  implicit val executionContext : ExecutionContext = context.dispatcher
  log.info(s"Using dispatcher: ${executionContext} ")

  implicit private val fbb: FlatBufferBuilder = new FlatBufferBuilder(1024)

  /**
    * The FQRV list of active servables need to be a CRDT.
    * The actual servables, need to be a sharded resource. This is how we can allow for scale out of resources.
    */
  @volatile var contracts: Set[Contract] =  Set.empty[Contract]

  /* activate extensions */
  val replicator: ActorRef = DistributedData(context.system).replicator

  // Required by the `ask` (?) method below
  implicit lazy val timeout: Timeout = Timeout(ApplicationEnvironment.HTTP_COMMAND_TIMEOUT_SECS seconds)

  private def completeSuccessResponse(response: Any) = {
    response match {
      case res: GeneratedSealedOneof with HasHTTPStatusCode => // covers ErrorResponse, SuccessResponse any other SealedOnOf Case that also extends from HasHTTPStatusCode
        complete(res.getStatusCode, res.asMessage)
      case res: ErrorResponse =>
        complete(StatusCodes.InternalServerError, res.asMessage)
      case res: GeneratedSealedOneof =>
        complete(StatusCodes.OK, res.asMessage)
      case res: Iterable[GeneratedSealedOneof] =>
        import org.json4s.JsonAST.JArray
        import org.json4s.jackson.JsonMethods._
        val dd = JArray(res.map(t => scalapb.json4s.JsonFormat.toJson(t.asMessage)).toList)
        complete(StatusCodes.OK, HttpEntity(ContentTypes.`application/json`, compact(dd)))
    }
  }

  private def completeFailureResponse(ex: Throwable) = {
    complete(StatusCodes.InternalServerError, List(`Content-Type`(`text/plain(UTF-8)`)), s"Failure - Error: ${ex.printableStackTrace}")
  }

  def completeProtoRequest(f: Future[Any], conversions: Option[Any => Any] = None): Route = { // Problem .. what if I get a List[T]
    onComplete(f) {
      case Success(value) =>
        val response = if (conversions.isDefined) {
          conversions.get.apply(value)
        } else value

        completeSuccessResponse(response)

      case Failure(ex) =>
        completeFailureResponse(ex)
    }
  }

  def completeProtoRequest(f: Future[Any]): Route = { // Problem .. what if I get a List[T]
    onComplete(f) {
      case Success(response) =>
        completeSuccessResponse(response)

      case Failure(ex) =>
        completeFailureResponse(ex)
    }
  }

  private def completeGPFailure(error: Array[String]) = {
    complete(HttpResponse(
      entity = HttpEntity.Strict(MediaTypes.`application/octet-stream`,
        ByteString(createErrorInferResponse(error)
        ))))
  }

  private def completeGPFailure(error: Any) = {
    complete(HttpResponse(
      entity = HttpEntity.Strict(MediaTypes.`application/octet-stream`,
        ByteString(createErrorInferResponse(error)
        ))))
  }


  private def completeServeRequest(serveRequest: ServeRequest): Route = {
    log.debug({
      serveRequest match {
        case s: BasicServeRequest => s"Received BasicServeRequest: ${serveRequest.toString}"
        case s: MLFlowServeRequest => s"Received MLFlowServeRequest: ${serveRequest.toString}"
        case _ => s"Received ServeRequest: ${serveRequest.toString}"
      }
    })
    serveRequest.checkRequirements()
    completeProtoRequest(servableProxyActor ? CreateServable(serveRequest.asInstanceOf[ServeRequestShim]))
  }

  val staticRoute: Route = {
    concat(
      pathPrefix("servable") {
        concat(
          post {
            concat(
              entity(as[BasicServeRequest]) { completeServeRequest },
              entity(as[MLFlowServeRequest]) { completeServeRequest },
            )
          },
          pathPrefix(Segment / Segment / IntNumber).as(Contract.apply) { contract =>
            path(Segment) { releaseVersion =>
              val fqrv = FQRV(contract, releaseVersion)
              post {
                concat(
                  entity(as[BasicServeRequest]) { sr => completeServeRequest(sr.withFqrv(fqrv)) },
                  entity(as[MLFlowServeRequest]) { sr => completeServeRequest(sr.withFqrv(fqrv)) },
                )
              }
            }
          }
        )
      },
      path("contract" / Segment / Segment / IntNumber).as(Contract.apply) { contract =>
        post {
          entity(as[ContractSettings]) { contractSettings: ContractSettings =>
            completeProtoRequest(servableProxyActor ? CreateContract(contract, contractSettings))
          }
        }
      },
      pathPrefix("contracts") {
        concat(
          path("list") {
            get {
              completeSuccessResponse(Contracts(contracts))
            }
          }
        )
      }
    )
  }

  val dynamicRoute : Route = implicit ctx => {
    val routes = contracts.map { contract =>
      pathPrefix(contract.organization / contract.project / contract.contractNumber.toString) {
        concat(
          put {
            entity(as[ContractSettings]) { contractSettings: ContractSettings =>
              completeProtoRequest(servableProxyActor ? UpdateContract(contract, contractSettings))
            }
          },
          pathPrefix("gp") {
            post {
              val tryInfer = Try {
                extractStrictEntity(timeout.duration) { entity =>
                  val raw = entity.data.asByteBuffer
                  val req = graphpipe.Request.getRootAsRequest(raw)
                  val f = req.reqType() match {
                    case Req.InferRequest =>
                      servableProxyActor ? ScoreByContractGP(contract, protoBString.copyFrom(raw))
                    case Req.MetadataRequest =>
                      servableProxyActor ? GetServableMetaDataByContractGP(contract)
                  }

                  onComplete(f) {
                    case Success(value: Array[Byte]) => complete(HttpResponse(entity = HttpEntity.Strict(MediaTypes.`application/octet-stream`, ByteString(value))))
                    case Success(issue) => completeGPFailure(issue)
                    case Failure(e) => completeGPFailure(Array(e.printableStackTrace))
                  }
                }
              } recover {
                case e: Throwable => completeGPFailure(Array(e.printableStackTrace))
              }

              tryInfer.get
            }
          },
          path("score") {
            post {
              entity(as[InferenceRequest]) { inferenceRequest =>
                completeProtoRequest(servableProxyActor ? ScoreByContract(contract, inferenceRequest))
              }
            }
          },
          path("stats") {
            get {
              completeProtoRequest(servableProxyActor ? GetServableMetricsByContract(contract), Some({
                case metrics : Iterable[(FQRV, ServableMetricsImpl)] =>
                  metrics.map(ServableMetricsImpl2ServableMetrics)
                case another =>
                  another
              })
              )
            }
          },
          path("s-settings" | "servable-settings") {
            get {
              completeProtoRequest(servableProxyActor ? GetServableSettingsByContract(contract))
            }
          },
          path("releases" | "list") {
            get {
              completeProtoRequest(servableProxyActor ? GetServableReleases(contract))
            }
          },
          path("metadata") {
            get {
              completeProtoRequest(servableProxyActor ? GetServableMetaDataByContract(contract))
            }
          },
          path("c-settings" | "contract-settings") {
            get {
              completeProtoRequest(servableProxyActor ? GetContractSettings(contract))
            }
          },
          pathPrefix(Segment) { releaseVersion =>
            val fqrv = FQRV(contract, releaseVersion)
            concat(
              path("policy" | "policy-settings") {
                put {
                  entity(as[PolicySettings]) { policySettings =>
                    completeProtoRequest(servableProxyActor ? UpdateServable(fqrv, policySettings = Some(policySettings)))
                  }
                }
              },
              path("logging" | "logging-settings") {
                put {
                  entity(as[LoggingSettings]) { loggingSettings : LoggingSettings =>
                    completeProtoRequest(servableProxyActor ? UpdateServable(fqrv, loggingSettings = Some(loggingSettings)))
                  }
                }
              },
              delete {
                completeProtoRequest(servableProxyActor ? DeleteServable(fqrv))
              },
              path("metadata") {
                get {
                  completeProtoRequest(servableProxyActor ? GetServableMetaData(fqrv))
                }
              },
              path("settings") {
                get {
                  completeProtoRequest(servableProxyActor ? GetServableSettings(fqrv))
                }
              },

            )
          }
        )
      }
    }

    concat(routes.toList: _*)(ctx)
  }

  override def preStart(): Unit = {
    replicator ! Replicator.Subscribe(ServableProxy.ContractsKey, self)
    super.preStart()
  }

  override def receive: Receive = {

    case change @ Replicator.Changed(ServableProxy.ContractsKey) =>
      log.debug(s"ServableRoutes - Distributed Data received ${change.get(ServableProxy.ContractsKey).elements}")
      contracts = change.get(ServableProxy.ContractsKey).elements

    case GetProxyRoutes() =>
      log.info(s"ServableRoutes - Dynamic routes requested.. ")
      sender() ! staticRoute ~ dynamicRoute
  }
}

