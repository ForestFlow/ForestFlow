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
package com.dreamworks.forestflow.serving.cluster

import java.io.{File, FileReader}
import java.nio.ByteOrder
import java.nio.file.{Files, Paths}

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, ReceiveTimeout, Timers}
import akka.cluster.Cluster
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.persistence._
import com.dreamworks.forestflow.domain.ServableRegistry._
import com.dreamworks.forestflow.domain._
import com.dreamworks.forestflow.serving.MLFlow.MLFlowModelSpec
import com.dreamworks.forestflow.serving.cluster
import com.dreamworks.forestflow.serving.cluster.Mechanics.TakeSnapshot
import com.dreamworks.forestflow.serving.cluster.Sharding.Shutdown
import com.dreamworks.forestflow.serving.config.{ApplicationEnvironment, RegistryConfigs}
import com.dreamworks.forestflow.serving.interfaces.Protocol.{BasicScore, GraphPipeScore, HasSideEffects, Score}
import com.dreamworks.forestflow.serving.interfaces._
import com.dreamworks.forestflow.utils.SourceStorageProtocols
import com.dreamworks.forestflow.utils.ThrowableImplicits._
import com.google.protobuf.{ByteString => protoBString}
import graphpipe.InferRequest
import io.circe.{Error, yaml}
import org.apache.commons.io.FileUtils
import com.dreamworks.forestflow.domain.ShimImplicits._
import com.dreamworks.forestflow.serving.impl.{BasicH2OMojoLoader, EnvironmentContext}

import scala.collection.mutable
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object ServableRegistry {

  /* Persistence */
  /*
      A persistent actor can query its own recovery status via the methods
      def recoveryRunning: Boolean
      def recoveryFinished: Boolean
   *  */

  /* Utils */
  def props(localBasePath: String): Props =
    Props(new ServableRegistry(localBasePath))
      .withMailbox("scoring-priority-mailbox")
      .withDispatcher("blocking-io-dispatcher")

  /* Sharding */
  // SEE https://doc.akka.io/docs/akka/2.5/persistence.html#event-adapters

  def startClusterSharding(localBasePath: String)(implicit system: ActorSystem): ActorRef = {
    ClusterSharding(system).start(
      typeName = "ServableRegistry",
      entityProps = props(localBasePath),
      settings = ClusterShardingSettings(system)
        .withRememberEntities(true)
        .withPassivateIdleAfter(RegistryConfigs.ACTIVITY_TIMEOUT_SECS seconds),
      extractEntityId = cluster.Sharding.extractEntityId,
      extractShardId = cluster.Sharding.extractShardId(ApplicationEnvironment.MAX_NUMBER_OF_SHARDS)
    )
  }

  final case class ServableRegistryState(
    serveRequests: mutable.Map[FQRV, ServeRequestShim]
  )

  private val sslVerify : Boolean = ApplicationEnvironment.config.getBoolean("application.ssl-verify")

}


class ServableRegistry(localBasePath: String) extends Actor with ActorLogging with Timers with PersistentActor {
  import ServableRegistry._

  implicit private val dispatcher: ExecutionContextExecutor = context.dispatcher
  timers.startPeriodicTimer(TakeSnapshot, TakeSnapshot, RegistryConfigs.STATE_SNAPSHOT_TRIGGER_SECS seconds)
  private val reuseLocalServableCopyOnRecovery = RegistryConfigs.REUSE_LOCAL_SERVABLE_COPY_ON_RECOVERY

  /* activate extensions */
  implicit val cluster: Cluster = Cluster(context.system)
  // val mediator: ActorRef = DistributedPubSub(context.system).mediator // Solves: How do I send a message to an actor without knowing which node it is running on
  // val replicator: ActorRef = DistributedData(context.system).replicator

  /* Persistent Data - Shard local */
  // TODO Servable Registry is no longer responsible for more than one servable,
  private var servables = mutable.Map.empty[FQRV, Servable]
  private var serveRequests = mutable.Map.empty[FQRV, ServeRequestShim]

  override def receiveCommand: Receive = {
    case request: Score =>
      sender() ! {
        servables.get(request.fqrv) match {
          // basic request
          case Some(servable: HasBasicSupport) if request.isInstanceOf[BasicScore] =>
            val basicRequest = request.asInstanceOf[BasicScore]
            servable.score(basicRequest.inferenceRequest) match {
              case Success(prediction) =>
                if (servable.logPredictions) {
                  context.system.eventStream.publish(
                    PredictionEvent(
                      prediction,
                      request.isInstanceOf[HighPriorityScoreRequest],
                      basicRequest.inferenceRequest,
                      servable.settings.loggingSettings.get))
                }
                prediction
              case Failure(exception) => UnknownServableError(request.fqrv, Some(exception.printableStackTrace))
            }
          // GraphPipe request
          case Some(servable: HasGraphPipeSupport) if request.isInstanceOf[GraphPipeScore] =>
            val gpRequest = request.asInstanceOf[GraphPipeScore]
            Try {
              val req = graphpipe.Request.getRootAsRequest(gpRequest.reqBytes.asReadOnlyByteBuffer().order(ByteOrder.LITTLE_ENDIAN))
              val inferRequest = req.req(new InferRequest()).asInstanceOf[InferRequest]
              servable.scoreGP(inferRequest)
            } match {
              case Success(inferResponseBytes) =>
                val prediction = PredictionGP(protoBString.copyFrom(inferResponseBytes), request.fqrv)
                if (servable.logPredictions) {
                  context.system.eventStream.publish(
                    PredictionEventGP(
                      prediction,
                      request.isInstanceOf[HighPriorityScoreRequestGP],
                      gpRequest.reqBytes,
                      servable.settings.loggingSettings.get))
                }
                inferResponseBytes
              case Failure(exception) =>
                UnknownServableError(request.fqrv, Some(exception.printableStackTrace))
            }
          case Some(servable) =>
            ProtocolNotSupported(request.fqrv, s"Servable ${servable.getClass.getSimpleName} does not conform to `HasBasicSupport` protocol")
          case _ => UnknownServable(request.fqrv)
        }
      }

    case Registry_GetServableMetaDataGP(fqrv) =>
      sender() ! {
        servables.get(fqrv) match {
          case Some(servable: HasGraphPipeSupport) => Try(servable.asInstanceOf[HasGraphPipeSupport].getGPMetaData) match {
            case Success(metadata) => metadata
            case Failure(exception) => UnknownServableError(fqrv, Some(exception.printableStackTrace))
          }
          case Some(servable) =>
            ProtocolNotSupported(fqrv, s"Servable ${servable.getClass.getSimpleName} does not conform to `HasGraphPipeSupport` protocol")
          case _ => UnknownServable(fqrv)
        }
      }

    case Registry_GetServableMetaData(fqrv) =>
      sender() ! {
        servables.get(fqrv) match {
          case Some(servable) => Try(servable.asInstanceOf[HasBasicSupport].getMetaData) match {
            case Success(metadata) => metadata
            case Failure(exception) => UnknownServableError(fqrv, Some(exception.printableStackTrace))
          }
          case _ => UnknownServable(fqrv)
        }
      }

    case command: HasSideEffects => handleSideEffectingCommand(command)

    case TakeSnapshot =>
      saveSnapshot(ServableRegistryState(serveRequests))

    case Shutdown | ShardRegion.Passivate =>
      // safe shutdown. Poison Pill doesn't work with persistent actors due to persistent actor stash
      log.info("Safe shutdown. Poison Pill doesn't work with persistent actors due to persistent actor stash")
      context.stop(self)

    /**
      * We don't use this anymore. Instead we rely on akka.cluster.sharding.passivate-idle-entity-after setting,
      * or by explicitly setting ClusterShardingSettings.passivateIdleAfter
      * This is a tradeoff. On one hand, this doesn't give us finer control to do this like: If the entity
      * is still updating time-based phase-in percentages to keep it alive even if it's not getting score requests.
      * On the other hand, it's more difficult to implement correctly if used as we'd have to account for every message
      * that we don't want to trigger a reset of the receive timeout by mixing in `NotInfluenceReceiveTimeout` for every
      * message that we don't want to cause a reset of the timeout timer. For example, perhaps we want to selectively
      * reset the timer based on if an `EvalActiveServables` evaluation results in any updates to the actor state.
      * Again, there is a tradeoff here simplicity with using `passivateIdleAfter` that relies solely on incoming messages
      * through the shard region, i.e., doesn't reset with any self-sent messages or messages sent to the actor directly
      * vs. the fine-grained control, but more complex, implementation with ReceiveTimeouts. We're going with simplicity
      * for now.
      */
    case ReceiveTimeout =>
      log.info(s"ServableRegistry has had no activity for the configured ReceiveTimeout, passivating servables ${servables.keys}")
      context.parent ! ShardRegion.Passivate(Shutdown)
  }

  override def receiveRecover: Receive = {
    case ServableSettingsUpdated(fqrv, servableSettings) =>
      serveRequests.get(fqrv).foreach(req => serveRequests += (fqrv -> req.withServableSettings(servableSettings)))
      servables(fqrv).settings = servableSettings

    case ServableDeleted(fqrv) =>
      serveRequests -= fqrv
      servables -= fqrv

      // Cleanup local storage
      if (reuseLocalServableCopyOnRecovery) {
        val localDir = Paths.get(localBasePath, fqrv.toString).toFile
        if (localDir.exists())
          FileUtils.deleteDirectory(localDir)
      }

    case CreateServableRequested(serveRequest) =>
      loadServable(serveRequest) { servable =>
        servables += (servable.fqrv -> servable)
        serveRequests += (servable.fqrv -> serveRequest)
      } { ex => log.error(s"Exception encountered while loading Servable for serve request $serveRequest. ${ex.printableStackTrace}") }

    case (CreateServableRequested(serveRequest), servable: Servable) =>
      servables += (servable.fqrv -> servable)
      serveRequests += (servable.fqrv -> serveRequest)
      log.info(s"Created new servable ${servable.fqrv} with: ${serveRequest.servableSettings}")

    case SnapshotOffer(metadata, snapshot: ServableRegistryState) =>
      serveRequests = snapshot.serveRequests
      serveRequests.values.foreach(serveRequest =>
        loadServable(serveRequest) { servable =>
          servables += (servable.fqrv -> servable)
        } { ex => log.error(s"Exception encountered while loading Servable for serve request $serveRequest. ${ex.printableStackTrace}") }
      )
      log.info(s"Snapshot offer completed for $metadata")
      deleteMessages(metadata.sequenceNr)

    case SaveSnapshotSuccess(metadata) => log.info(s"Snapshot successful for $metadata")
    case SaveSnapshotFailure(metadata, reason) => log.warning(s"Snapshot failed for $metadata due to ${reason.printableStackTrace}")
    case RecoveryCompleted =>
      // perform init after recovery, before any other messages
      log.info(s"Recovery completed for $persistenceId")

    case something => log.warning(s"ServableRegistry - Received something we don't understand $something")
  }

  override def preStart(): Unit = log.info(s"Starting actor with persistenceID ${"ServableRegistryActor-" + self.path.name}")

  def handleDeleteServable(fqrv: FQRV, deliveryId: Long, requester: ActorRef): Unit = {
    servables.get(fqrv) match {
      case None =>
        sender() ! Registry_DeleteForUnknownServableRequested(deliveryId, fqrv, requester)
      case Some(_) =>
        persist(ServableDeleted(fqrv)) { event =>
          sender() ! Registry_ValidDeleteReceived(deliveryId, fqrv, requester)
          receiveRecover(event)
        }

    }
  }

  /**
    * The main responsibility of an event handler is changing persistent actor state using event data and notifying
    * others about successful state changes by publishing events. SEE https://doc.akka.io/docs/akka/2.5/persistence.html
    *
    * @param command
    * Any Registry command that has a side effect
    */
  private def handleSideEffectingCommand(command: HasSideEffects) {
    def handleUpdateServable(fqrv: FQRV, settings: ServableSettings, deliveryId: Long, requester: ActorRef) {
      def update(servable: Servable) {
        if (servable.settings == settings)
          sender() ! Registry_UpdateWithSameSettingsRequested(deliveryId, fqrv, requester)
        else {
          servable.settings = settings
          persist(ServableSettingsUpdated(fqrv, settings)) { event =>
            sender() ! Registry_ValidUpdateReceived(deliveryId, fqrv, requester)
            receiveRecover(event)
            log.info(s"Updated servable $fqrv with $settings")
          }
        }
      }

      servables.get(fqrv) match {
        case Some(servable) =>
          update(servable)
        case None =>
          sender() ! Registry_UpdateForUnknownServableRequested(deliveryId, fqrv, requester)
      }
    }

    def handleCreateServable(serveRequest: ServeRequestShim, deliveryId: Long, requester: ActorRef): Unit = {

      def create(): Unit = {
        loadServable(serveRequest) { servable =>
          persist(CreateServableRequested(serveRequest)) { event =>
            sender() ! Registry_ValidCreateServableRequest(deliveryId, serveRequest, requester)
            receiveRecover((event, servable)) // INFO per docs, we are allowed to close over the actor's state in the handler
          }
        } { ex: Throwable =>
          sender() ! Registry_InvalidCreateServableRequest(deliveryId, serveRequest, requester, ex)
        }
      }

      servables.get(serveRequest.getUltimateFQRV) match {
        case None =>
          create()
        case Some(servable) =>
          sender() ! Registry_ServableAlreadyExists(deliveryId, servable.fqrv, servable.settings, requester)
      }
    }

    command match {
      case Registry_UpdateServable(fqrv, settings, deliveryId, requester) => handleUpdateServable(fqrv, settings, deliveryId, requester)
      case Registry_CreateServable(serveRequest, deliveryId, requester) => handleCreateServable(serveRequest, deliveryId, requester)
      case Registry_DeleteServable(fqrv, deliveryId, requester) => handleDeleteServable(fqrv, deliveryId, requester)
    }
  }

  private def loadServable(serveRequest: ServeRequestShim)(successAction: Servable => Unit)(failureAction: Throwable => Unit) {
    import cats.syntax.either._


    val fqrv = serveRequest.getUltimateFQRV
    val protocol: SourceStorageProtocols.EnumVal = SourceStorageProtocols.getProtocolWithDefault(serveRequest.path, SourceStorageProtocols.LOCAL)

    def tryLoad(pre: () => Either[Throwable, Unit] = () => Right(Unit), post: () => Either[Throwable, Unit] = () => Right(Unit))(implicit eCTX: EnvironmentContext) = {
      def load = {
        Try {
          serveRequest match {
            case s: BasicServeRequest =>
              s.flavor match {
                case loader: H2OMojoFlavor =>
                  log.info(s"Trying to load a BasicServeRequest $s")

                  loader.createServable(
                    ArtifactReader
                      .getArtifactReader(s.getArtifactPath)
                      .getArtifact(loader.getRelativeServablePath, eCTX.localDir.getAbsolutePath),
                    fqrv,
                    s.servableSettings)

              }
            case s: MLFlowServeRequest =>
              log.info(s"Trying to load an MLFlowServeRequest $s")
              val mlModelPath = Paths.get(eCTX.localDir.getAbsolutePath, "MLmodel").toString
              log.debug(s"Attempting to parse mlModelPath = $mlModelPath")

              val json = yaml.parser.parse(new FileReader(mlModelPath))

              val model = json
                .leftMap(err => err: Error)
                .flatMap(_.as[MLFlowModelSpec])
                .valueOr(throw _)

              val (flavorName, servableLoader) = {
                model.getServableFlavor match {
                  case Some(flavor) => flavor
                  case None => throw new IllegalArgumentException(s"No support for any of the servable flavors provided: ${model.flavors}")
                }
              }
              val loader = servableLoader.asInstanceOf[Loader]

              loader.createServable(
                model
                  .artifactReader
                  .getArtifact(loader.getRelativeServablePath, eCTX.localDir.getAbsolutePath),
                fqrv,
                serveRequest.servableSettings)
          }

        }.toEither
      }

      for {
        preOp <- pre().right
        servable <- load.right
        postOp <- post().right
      } yield servable

    }

    val servableTry = if (reuseLocalServableCopyOnRecovery) {

      val localDirPath = Paths.get(localBasePath, fqrv.toString)
      val localDir = localDirPath.toFile
      implicit val eCTX: EnvironmentContext = EnvironmentContext(localDir)

      if (recoveryRunning) {
        if (localDir.exists()) {
          log.info(s"Found a local copy while in recovery for ${fqrv.toString}")
          val attempt = tryLoad()
          attempt match {
            case Left(_) =>
              log.info(s"Local copy failed to load for ${fqrv.toString}, delete and re-download")
              tryLoad(
                () => Try {
                  FileUtils.deleteDirectory(localDir)
                  Files.createDirectories(localDirPath)
                  protocol.downloadDirectory(serveRequest.path, localDir, fqrv, sslVerify)
                }.toEither
              )
            case _ =>
              attempt

          }
        }
        else {
          log.info(s"No local copy for ${fqrv.toString}, create one")
          tryLoad(
            () => Try {
              Files.createDirectories(localDirPath)
              protocol.downloadDirectory(serveRequest.path, localDir, fqrv, sslVerify)
            }.toEither
          )
        }
      } else {
        log.info(s"Not in recovery, but reuse local selected for ${fqrv.toString}. We're setting up for a new download and keeping the local copy around.")
        tryLoad(
          () => Try {
            if (localDir.exists()) {
              log.info(s"Deleting local copy for ${fqrv.toString}, (something left behind from another failed attempt)")
              FileUtils.deleteDirectory(localDir)
            }
            log.info(s"Creating local directory: ${localDirPath.toString}")
            Files.createDirectories(localDirPath)
            log.info(s"Create new local copy for ${fqrv.toString}")
            protocol.downloadDirectory(serveRequest.path, localDir, fqrv, sslVerify)
          }.toEither
        )
      }
    } else {
      log.info(s"Don't reuse local copy selected for ${fqrv.toString}. Create temporary directory and cleanup after.")
      val localDir: File = Files.createTempDirectory(Paths.get(localBasePath), fqrv.toString).toFile
      implicit val eCTX: EnvironmentContext = EnvironmentContext(localDir)

      tryLoad(
        () => Try {
          protocol.downloadDirectory(serveRequest.path, localDir, fqrv, sslVerify)
        }.toEither,
        () => Right {
          try {
            FileUtils.deleteDirectory(localDir)
          } catch {
            case ex: Throwable => log.warning(s"Exception while cleaning up local temporary directory: ${ex.printableStackTrace}")
          }
        }
      )
    }

    servableTry match {
      case Right(servable) => successAction(servable)
      case Left(exception: Throwable) => failureAction(exception)
    }
  }

  override def persistenceId: String = "ServableRegistry-" + self.path.name
}
