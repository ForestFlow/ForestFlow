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
package ai.forestflow.serving.cluster

import java.io.{File, FileReader}
import java.net.URI
import java.nio.ByteOrder
import java.nio.file.{Files, Path, Paths}

import ai.forestflow.serving.MLFlow.MLFlowModelSpec
import ai.forestflow.serving.cluster
import ai.forestflow.serving.config.{ApplicationEnvironment, RegistryConfigs}
import ai.forestflow.serving.impl.EnvironmentContext
import ai.forestflow.serving.interfaces.Protocol.{BasicScore, GraphPipeScore, HasSideEffects, Score}
import ai.forestflow.serving.interfaces.{ArtifactReader, HasBasicSupport, HasGraphPipeSupport, Loader, Servable}
import akka.actor.SupervisorStrategy._
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, ReceiveTimeout, Timers}
import akka.cluster.Cluster
import akka.cluster.ddata.Replicator
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.{Publish, Subscribe}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.persistence._
import ai.forestflow.akka.Supervisor
import ai.forestflow.domain.ServableRegistry._
import ai.forestflow.domain._
import ai.forestflow.serving.MLFlow.MLFlowModelSpec
import ai.forestflow.serving.cluster
import ai.forestflow.serving.cluster.Mechanics.TakeSnapshot
import ai.forestflow.serving.cluster.Sharding.Shutdown
import ai.forestflow.serving.config.{ApplicationEnvironment, RegistryConfigs}
import ai.forestflow.serving.interfaces.Protocol.{BasicScore, GraphPipeScore, HasSideEffects, Score}
import ai.forestflow.serving.interfaces._
import ai.forestflow.utils.SourceStorageProtocols
import ai.forestflow.utils.ThrowableImplicits._
import com.google.protobuf.{ByteString => protoBString}
import graphpipe.InferRequest
import io.circe.{Error, yaml}
import org.apache.commons.io.FileUtils
import ai.forestflow.domain.ShimImplicits._
import ai.forestflow.serving.impl.EnvironmentContext
import akka.stream.ActorMaterializer

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
    Supervisor.props(
      {
        case _: ArithmeticException => Resume
        case _: java.nio.file.InvalidPathException => Stop
        case _: Exception => Restart
      },
      Props(new ServableRegistry(localBasePath))
        .withMailbox("scoring-priority-mailbox")
        .withDispatcher("blocking-io-dispatcher")
    )

  /* Sharding */
  // SEE https://doc.akka.io/docs/akka/2.5/persistence.html#event-adapters
  val SHARDING_TYPE_NAME = "ServableRegistry"

  def startClusterSharding(localBasePath: String)(implicit system: ActorSystem): ActorRef = {
    ClusterSharding(system).start(
      typeName = SHARDING_TYPE_NAME,
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


class ServableRegistry(localBasePath: String) extends Actor
  with ActorLogging
  with Timers
  with PersistentActor
  with HasPersistence {
  import ServableRegistry._

  implicit private val system : ActorSystem = context.system
  implicit private val dispatcher: ExecutionContextExecutor = context.dispatcher
  implicit private val materializer: ActorMaterializer = ActorMaterializer()

  log.info(s"Using  dispatcher: ${dispatcher}")
  timers.startPeriodicTimer(TakeSnapshot, TakeSnapshot, RegistryConfigs.STATE_SNAPSHOT_TRIGGER_SECS seconds)
  private val reuseLocalServableCopyOnRecovery = RegistryConfigs.REUSE_LOCAL_SERVABLE_COPY_ON_RECOVERY

  /* activate extensions */
  implicit val cluster: Cluster = Cluster(context.system)
  val mediator: ActorRef = DistributedPubSub(context.system).mediator

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

    case Sharding.Shutdown | ShardRegion.Passivate =>
      // safe shutdown. Poison Pill doesn't work with persistent actors due to persistent actor stash
      log.info(s"Safe shutdown of ${self.path} ... ")
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
      context.parent ! ShardRegion.Passivate(Sharding.Shutdown)
  }

  override def receiveRecover: Receive = {
    case ServableSettingsUpdated(fqrv, servableSettings) =>
      serveRequests.get(fqrv).foreach(req => serveRequests += (fqrv -> req.withServableSettings(servableSettings)))
      servables(fqrv).settings = servableSettings

    case ServableDeleted(fqrv) =>
      serveRequests -= fqrv
      servables -= fqrv
      log.info(s"ServableDeleted: $fqrv")

      // Cleanup local storage
      log.info(s"Checking local storage cleanup requirements... Reuse local servable copy on recovery: $reuseLocalServableCopyOnRecovery")
      if (reuseLocalServableCopyOnRecovery) {
        val path = Paths.get(localBasePath, fqrv.toString).toString

        // ensure local copy cleanup as dist pub/sub does not necessarily guarantee delivery
        NodeActor.cleanupLocalStorage(path)
        mediator ! Publish(classOf[CleanupLocalStorage].getSimpleName, CleanupLocalStorage(path))
      }

      if (servables.isEmpty){
        /*To permanently stop entities, a Passivate message must be sent to the parent of the entity actor,
        otherwise the entity will be automatically restarted after the entity restart backoff specified in
        the configuration.*/
        log.info(s"Servable list empty; stopping self ${self.path} by sending passivate message to parent")
        context.parent ! ShardRegion.Passivate(Sharding.Shutdown)
      }

    case CreateServableRequested(serveRequest) =>
      loadServable(serveRequest) { servable =>
        receiveRecover((serveRequest, servable))
      } { ex => log.error(s"Exception encountered while loading Servable for serve request $serveRequest. ${ex.printableStackTrace}") }

    case (serveRequest: ServeRequestShim, servable: Servable) =>
      servables += (servable.fqrv -> servable)
      serveRequests += (servable.fqrv -> serveRequest)
      log.info(s"Created and now tracking servable ${servable.fqrv} with: ${serveRequest.servableSettings}")

    case SnapshotOffer(metadata, snapshot: ServableRegistryState) =>
      /* INFO: We maintain the state of serve requests received even if loading a particular servable fails.
          This protects against losing the state of servables that just happen to fail loading.
          We could simply not allow this actor to recover in case its snapshot fails.

          Additionally, this protection might not be necessary given each ServableRegistry actor is now only
           responsible for a single servable.

           Cleanup considerations will be taken into account when replication and auto-scale out is implemented using
           load balancing routers.
      * */
      serveRequests = snapshot.serveRequests
      serveRequests.values.foreach(serveRequest =>
        loadServable(serveRequest) { servable =>
          receiveRecover((serveRequest, servable))
        } {
          /*
          TODO: Consider disabling snapshots post a failed snapshot recovery failure so we don't persist incorrect
           state. This interacts with the above comment surrounding ```serveRequests = snapshot.serveRequests```
          */
          ex => log.error(s"Exception encountered while loading Servable from snapshot for serve request $serveRequest. ${ex.printableStackTrace}")
        }
      )

      log.info(s"Snapshot offer successfully completed for $metadata")
      deleteMessages(metadata.sequenceNr)

    case SaveSnapshotSuccess(metadata) => log.info(s"Snapshot successful for $metadata")
    case SaveSnapshotFailure(metadata, reason) => log.warning(s"Snapshot failed for $metadata due to ${reason.printableStackTrace}")
    case RecoveryCompleted =>
      // perform init after recovery, before any other messages
      if (recoveryRunning) {
        log.info(s"Recovery completed for $persistenceId")
      } else {
        log.info(s"New actor created for $persistenceId")
      }

    case something => log.warning(s"ServableRegistry - Received something we don't understand $something")
  }

  override def preStart(): Unit = log.info(s"Starting actor with persistenceID $persistenceId")

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
            receiveRecover((serveRequest, servable)) // INFO per docs, we are allowed to close over the actor's state in the handler
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

    command match {
      case Registry_UpdateServable(fqrv, settings, deliveryId, requester) => handleUpdateServable(fqrv, settings, deliveryId, requester)
      case Registry_CreateServable(serveRequest, deliveryId, requester) => handleCreateServable(serveRequest, deliveryId, requester)
      case Registry_DeleteServable(fqrv, deliveryId, requester) => handleDeleteServable(fqrv, deliveryId, requester)
    }
  }

  private def loadServable(serveRequest: ServeRequestShim)(successAction: Servable => Unit)(failureAction: Throwable => Unit) {
    import cats.syntax.either._
    val FQRV = serveRequest.getUltimateFQRV
    log.info(s"LoadServable received for [$FQRV]")

    def tryLoad(pre: () => Either[Throwable, Any] = () => Right(Unit),
                post: () => Either[Throwable, Unit] = () => Right(Unit),
                downloadArtifact: Boolean = false
               )(implicit eCTX: EnvironmentContext) : Either[Throwable, Servable] = {
      def load: Either[Throwable, Servable] = {
        Try {
          val (flavor, dl) = {

            val protocol: SourceStorageProtocols.EnumVal = SourceStorageProtocols.getProtocolWithDefault(serveRequest.path, SourceStorageProtocols.LOCAL)
            val download: (String) => Unit = {
              if (downloadArtifact) {
                protocol.download(eCTX.remotePath, _, eCTX.localDirectory, eCTX.fqrv, eCTX.sslVerify, eCTX.tags)
              } else { (_: String) => }
            }

            serveRequest match {
              case s: BasicServeRequest =>
                (s.flavor, download)

              case s: MLFlowServeRequest =>
                log.info(s"Trying to load an MLFlowServeRequest [$s]")
                // path in MLFlowServeRequest points to MLmodel file. Download that first
                download("MLmodel")
                val mlModelPath = Paths.get(eCTX.localDirectory.getAbsolutePath, "MLmodel").toString

                log.debug(s"Attempting to parse mlModelPath = $mlModelPath")
                val json = yaml.parser.parse(new FileReader(mlModelPath))
                val model = json
                  .leftMap(err => err: Error)
                  .flatMap(_.as[MLFlowModelSpec])
                  .valueOr(throw _)

                val (flavorName, flavor) = {
                  model.getServableFlavor match {
                    case Some(flavor) => flavor
                    case None => throw new IllegalArgumentException(s"No support for any of the servable flavors provided: ${model.flavors}")
                  }
                }

                (flavor, if (!downloadArtifact) { (_: String) => } else {
                  // Update download requirements as needed if MLmodel file provides a path
                  (model.path, SourceStorageProtocols.getProtocolWithDefault(model.path.getOrElse(""), SourceStorageProtocols.LOCAL)) match {
                    case (Some(providedPath), stor) if stor == SourceStorageProtocols.LOCAL =>
                      // path is relative/local
                      if (stor.singleFileProtocol) {
                        protocol.download(Paths.get(eCTX.remotePath, providedPath).toString, _, eCTX.localDirectory, eCTX.fqrv, eCTX.sslVerify, eCTX.tags)
                      } else {
                        // TODO do nothing.. but what happens to the provided path?
                        // Test this scenario with MLmodel yaml file in git and relative path provided
                        { (_: String) => }
                      }
                    case (Some(providedPath), stor) if stor != SourceStorageProtocols.LOCAL =>
                      stor.download(providedPath, _, eCTX.localDirectory, eCTX.fqrv, eCTX.sslVerify, eCTX.tags)

                    case (None, stor) if stor.singleFileProtocol =>
                      download

                    case _ =>
                      { (_: String) => }
                  }
                })
            }
          }

          flavor match {
            case loader: H2OMojoFlavor =>
              log.info(s"Trying to load a ServeRequest [$serveRequest]")
              dl(loader.getRelativeServablePath)
              loader.createServable(
                ArtifactReader
                  .getLocalFileArtifactReader
                  .getArtifact(loader.getRelativeServablePath, eCTX.localDirectory.getAbsolutePath),
                eCTX.fqrv,
                serveRequest.servableSettings)
          }

        }.toEither
      }

      for {
        _ <- pre().right
        servable <- load.right
        _ <- post().right
      } yield servable

    }

    servables.get(FQRV) match {
      case Some(servable) =>
        log.warning(s"Servable already loaded for [$FQRV]. Returning existing servable. Recovery: [$recoveryRunning]")
        successAction(servable)
      case None =>
        val servableTry = Try (if (reuseLocalServableCopyOnRecovery) {
          val localDirPath = Paths.get(localBasePath, FQRV.toString)
          val localDir = localDirPath.toFile
//          val download = protocol.download(serveRequest.path, serveRequest.artifactPath, _, localDir, fqrv, sslVerify, serveRequest.tags)
          implicit val eCTX: EnvironmentContext = EnvironmentContext(serveRequest.path, localDir, FQRV, sslVerify, serveRequest.tags)

          if (recoveryRunning) {
            if (localDir.exists()) {
              log.info(s"Found a local copy while in recovery for [$FQRV]")
              val attempt = tryLoad()
              attempt match {
                case Left(_) =>
                  log.info(s"Local copy failed to load for [$FQRV], delete and re-download")
                  tryLoad(
                    pre = () => Try {
                      FileUtils.deleteDirectory(localDir)
                      Files.createDirectories(localDirPath)
                    }.toEither,
                    downloadArtifact = true
                  )
                case _ =>
                  attempt
              }
            }
            else {
              log.info(s"No local copy for [$FQRV], create one")
              tryLoad(
                pre = () => Try {
                  Files.createDirectories(localDirPath)
                }.toEither,
                downloadArtifact = true
              )
            }
          } else {
            log.info(s"Not in recovery, but reuse local selected for [$FQRV]. We're setting up for a new download and keeping the local copy around.")
            tryLoad(
              pre = () => Try {
                if (localDir.exists()) {
                  log.info(s"Deleting local copy for [$FQRV], (something left behind from another failed attempt)")
                  FileUtils.deleteDirectory(localDir)
                }
                log.info(s"Creating local directory: $localDirPath")
                Files.createDirectories(localDirPath)
                log.info(s"Create new local copy for [$FQRV]")
              }.toEither,
              downloadArtifact = true
            )
          }
        } else {
          log.info(s"Don't reuse local copy selected for [$FQRV]. Create temporary directory and cleanup after.")
          val localDir: File = Files.createTempDirectory(Paths.get(localBasePath), FQRV.toString).toFile
//          val download = protocol.download(serveRequest.path, serveRequest.artifactPath, _, localDir, fqrv, sslVerify, serveRequest.tags)
          implicit val eCTX: EnvironmentContext = EnvironmentContext(serveRequest.path, localDir, FQRV, sslVerify, serveRequest.tags)

          tryLoad(
            post = () => Right {
              try {
                FileUtils.deleteDirectory(localDir)
              } catch {
                case ex: Throwable => log.warning(s"Exception while cleaning up local temporary directory: ${ex.printableStackTrace}")
              }
            },
            downloadArtifact = true
          )
        })

        servableTry match {
          case Success(Right(servable)) => successAction(servable)
          case Success(Left(exception: Throwable)) => failureAction(exception)
          case Failure(exception: Throwable) => failureAction(exception)
        }
    }
  }

  override def persistencePrefix: String = ServableRegistry.SHARDING_TYPE_NAME
}
