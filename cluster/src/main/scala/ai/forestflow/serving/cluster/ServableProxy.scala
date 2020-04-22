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

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, ReceiveTimeout, Timers}
import akka.cluster.ddata._
import akka.cluster.sharding
import akka.cluster.sharding.{ClusterSharding, ShardRegion}
import akka.pattern.{ask, pipe}
import akka.persistence._
import akka.util.Timeout
import ai.forestflow.domain
import ai.forestflow.domain._
import ai.forestflow.domain.ServableProxy._
import ai.forestflow.domain.ServableRegistry._
import ai.forestflow.serving.cluster.Mechanics.TakeSnapshot
import ai.forestflow.serving.config.{ApplicationEnvironment, ProxyConfigs}
import ai.forestflow.serving.impl.ServableMetricsImpl
import ai.forestflow.serving.interfaces.ContractRouter
import ai.forestflow.serving.interfaces.Protocol.{Command, CommandDeliveryConfirmation, HasSideEffects, LowPriorityShadeRequest}
import ai.forestflow.utils.ThrowableImplicits._
import ai.forestflow.domain.ShimImplicits._
import Sharding.Shutdown
import ai.forestflow.serving.config.{ApplicationEnvironment, ProxyConfigs}
import ai.forestflow.serving.impl.ServableMetricsImpl
import ai.forestflow.serving.interfaces.ContractRouter
import ai.forestflow.serving.interfaces.Protocol.{Command, CommandDeliveryConfirmation, HasSideEffects, LowPriorityShadeRequest}
import ai.forestflow.domain.ShimImplicits._
import akka.cluster.metrics.{AdaptiveLoadBalancingPool, MixMetricsSelector}
import akka.cluster.metrics.protobuf.msg.ClusterMetricsMessages.MetricsSelector
import akka.routing.ConsistentHashingPool
import akka.cluster.routing.{ClusterRouterPool, ClusterRouterPoolSettings}
import com.google.flatbuffers.FlatBufferBuilder

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.language.postfixOps

object ServableProxy {

  /* Events */
  val EVAL_SUBSCRIPTION: String = classOf[EvalActiveServables].getCanonicalName

  private case object EvalActiveServablesKey


  /* Event Bus Notifications */

  /* Utils */
  def props(servableRegistry: ActorRef): Props =
    Props(new ServableProxy(servableRegistry))
      .withMailbox("inverse-priority-mailbox")
      .withDispatcher("proxy-dispatcher")

  /* Sharding */
  val SHARDING_TYPE_NAME = "ServableProxy"

  def startClusterSharding(servableRegistry: ActorRef)(implicit system: ActorSystem): ActorRef = {
/*    /**
      * if we want to use a Cluster Aware Router, where do we then save state?
      * This should be modeled differently.
      * There should be an actor that saves the state of the servable registry i.e., create servable request only.
      * And possibly some stats. This actor is used as the sharded entity. It then creates its own cluster aware pool
      * of actors and maintains them. The assumtion here is that it knows how to send create servable requests to its
      * routees each time one is created (as the cluster expands/contracts for replication). That would be active management.
      * An alternative approach to management would be a passive one whereas the routees ask the entity router (parent) for
      * the create servable requests if they don't have a servable loaded and they receive a request to score. This means
      * high latency as the cluster reaches steady state as children load servables on-demand but this is likely
      * much easier to implement and address edge cases unless Akka has provisions to guarantee sending messages to all
      * router routees and re-send this message if a routee is re/created.
      */
    val routerProps = ClusterRouterPool(
      AdaptiveLoadBalancingPool(metricsSelector = MixMetricsSelector, routerDispatcher = "proxy-dispatcher"),
      ClusterRouterPoolSettings(totalInstances = 2, maxInstancesPerNode = 1, allowLocalRoutees = true))
      .props(props(servableRegistry))*/
    ClusterSharding(system).start(
      typeName = SHARDING_TYPE_NAME,
//      entityProps = routerProps,
      entityProps = props(servableRegistry),
      settings = sharding.ClusterShardingSettings(system)
        .withRememberEntities(true)
        .withPassivateIdleAfter(ProxyConfigs.ACTIVITY_TIMEOUT_SECS seconds),
      extractEntityId = Sharding.extractEntityId,
      extractShardId = Sharding.extractShardId(ApplicationEnvironment.MAX_NUMBER_OF_SHARDS)
    )
  }

  /* Distributed Data */
  val ContractsKey: Key[ORSet[Contract]] = ORSetKey.create[Contract]("contracts")


  /* State Snapshots */
  final case class ServableProxyState(
    contracts: mutable.Map[Contract, ContractSettings],
    activeContractServables: mutable.Map[Contract, ContractRouter],
    servables : mutable.Map[FQRV, ServableSettings],
    servablesWithLogging : mutable.Map[FQRV, LoggingSettings],
    servableMetrics : mutable.Map[FQRV, ServableMetricsImpl],
    contractStats : mutable.Map[Contract, Long]
  )
}

/** *
  *
  * @param servableRegistry ActorRef for an actor that knows how to deal with ServableRegistry commands/events
  */
class ServableProxy(servableRegistry: ActorRef) extends Actor
  with ActorLogging
  with PersistentActor
  with Timers
  with AtLeastOnceDelivery
  with ClusterEventSubscription
  with HasPersistence {
  import ServableProxy._

  /* activate extensions */
  val replicator: ActorRef = DistributedData(context.system).replicator
  //  val mediator: ActorRef = DistributedPubSub(context.system).mediator // Solves: How do I send a message to an actor without knowing which node it is running on

  override val redeliverInterval: FiniteDuration = {
    FiniteDuration(ProxyConfigs.SIDE_EFFECTS_DELIVERY_TIMEOUT_SECS, TimeUnit.SECONDS)
  }

  override def preStart(): Unit = {
    super.preStart()
  }

  override def postStop(): Unit = {
    super.postStop()
  }

  implicit private val dispatcher: ExecutionContextExecutor = context.dispatcher
  log.info(s"Using dispatcher: $dispatcher")
  timers.startPeriodicTimer(TakeSnapshot, TakeSnapshot, ProxyConfigs.STATE_SNAPSHOT_TRIGGER_SECS seconds)

  implicit val timeout: Timeout = Timeout(5 seconds) // TODO This should not be a global timeout.. Gets vs Scoring!!
  implicit private val fbb: FlatBufferBuilder = new FlatBufferBuilder(1024)

  /* State */
  var contracts = mutable.Map.empty[Contract, ContractSettings]
  var activeContractServables = mutable.Map.empty[Contract, ContractRouter]
  var servables = mutable.Map.empty[FQRV, ServableSettings]
  var servablesWithLogging = mutable.Map.empty[FQRV, LoggingSettings]
  var servableMetrics = mutable.Map.empty[FQRV, ServableMetricsImpl]
  var contractStats = mutable.Map.empty[Contract, Long]

  private def persistCreateServable(serveRequest: ServeRequestShim)(postSideEffects: ServeRequestValidated => Unit = _ => Unit): Unit = {
    persist(ServeRequestValidated(serveRequest)) { event: ServeRequestValidated =>
      receiveRecover(event)
      self ! EvalActiveServables(Some(event.serveRequest.getUltimateFQRV.contract), contractSettingsUpdated = false)
      postSideEffects(event)
      log.info(s"Finished Creating Servable ${event.serveRequest}")
    }
  }

  private def persistDeleteServable(fqrvs: List[FQRV], postPersist: ServablesDeleted => Unit = x => Unit, postAction: ServablesDeleted => Unit = x => Unit): Unit = {
    persist(ServablesDeleted(fqrvs)) { event: ServablesDeleted =>
      log.info(s"Deleting servables $fqrvs")
      postPersist(event)
      receiveRecover(event)
      postAction(event)
      log.info(s"Deleted servables $fqrvs")
    }
  }

  private def persistUpdateServable(fqrv: FQRV, servableSettings: ServableSettings) {
    /** INFO
      * The main responsibility of an event handler is changing persistent actor state using event data and notifying
      * others about successful state changes by publishing events.
      */

    persist(ServableSettingsUpdated(fqrv, servableSettings)) { event: ServableSettingsUpdated =>
    receiveRecover(event)
      self ! EvalActiveServables(Some(event.fqrv.contract), contractSettingsUpdated = false)
      log.debug(s"Updated servable $fqrv with ${event.servableSettings}")
    }
  }

  private def createServable(serveRequest: ServeRequestShim) = {
    val fqrv = serveRequest.getUltimateFQRV

    if (contracts.get(fqrv.contract).isEmpty) {
        serveRequest.contractSettings.foreach { cs =>
          contracts += (fqrv.contract -> cs)

          replicateContracts(List(fqrv.contract))
        }
    }
    updateServable(fqrv, serveRequest.servableSettings)
  }

  private def updateServable(fqrv: FQRV, servableSettings: ServableSettings) = {
    servables += (fqrv -> servableSettings)
    servableMetrics += (fqrv -> ServableMetricsImpl.empty())
    if (servableSettings.loggingSettings.isDefined && servableSettings.loggingSettings.get.logLevel != LogLevel.NONE)
      servablesWithLogging += (fqrv -> servableSettings.loggingSettings.get)
  }

  private def deleteServables(fqrvs: List[FQRV]): Unit = {
    servables --= fqrvs
    servableMetrics --= fqrvs
    servablesWithLogging --= fqrvs
    val cleanupContracts = fqrvs.flatMap { fqrv =>
      deliver(servableRegistry.path) { deliveryId =>
        Registry_DeleteServable(fqrv, deliveryId, sender())
      }

      if (!servables.exists(_._1.contract == fqrv.contract))
        Some(fqrv.contract)
      else
        None
    }.distinct

    contracts --= cleanupContracts
    contractStats --= cleanupContracts
    replicateContracts(cleanupContracts, added = false)
  }

  private def replicateContracts(contracts: List[Contract], added: Boolean = true): Unit = {
    if (added) {
      replicator ! Replicator.Update(
        key = ContractsKey,
        initial = ORSet.empty[Contract],
        writeConsistency = Replicator.WriteMajority(5 seconds),
        request = Some(ContractsAdded(contracts))
      ) {contractSet => contracts.foldLeft(contractSet) { (acc, c) => acc + c }}
    } else {
      replicator ! Replicator.Update(
        key = ContractsKey,
        initial = ORSet.empty[Contract],
        writeConsistency = Replicator.WriteMajority(5 seconds),
        request = Some(ContractsRemoved(contracts))
      ) {contractSet => contracts.foldLeft(contractSet) { (acc, c) => acc - c }}
    }
  }

  // AKKA Persistence
  override def receiveCommand: Receive = commands orElse distributedData orElse clusterEvents

  override def receiveRecover: Receive = {

    case ServeRequestValidated(serveRequest: ServeRequestShim) =>
      log.debug(s"receiveRecover(ServeRequestValidated) $serveRequest")
      createServable(serveRequest)

    case ContractSettingsUpdated(contract, contractSettings) =>
      contracts += (contract -> contractSettings)

    case ContractSettingsCreated(contract, contractSettings) =>
      contracts += (contract -> contractSettings)
      replicateContracts(List(contract))

    case ServableSettingsUpdated(fqrv, servableSettings) => updateServable(fqrv, servableSettings)

    case ServablesDeleted(fqrvs) => deleteServables(fqrvs)

    case ServableMetricsUpdated(metrics) => servableMetrics += (metrics.fqrv -> metrics)

    case SnapshotOffer(metadata, snapshot: ServableProxyState) =>
      contracts = snapshot.contracts
      activeContractServables = snapshot.activeContractServables
      servables = snapshot.servables
      servablesWithLogging = snapshot.servablesWithLogging
      servableMetrics = snapshot.servableMetrics
      contractStats = snapshot.contractStats

      replicateContracts(contracts.keys.toList)

      deleteMessages(metadata.sequenceNr)

    case SaveSnapshotSuccess(metadata) =>
      log.info(s"Snapshot successful for $metadata")


    case SaveSnapshotFailure(metadata, reason) => log.warning(s"Snapshot failed for $metadata due to ${reason.printableStackTrace}")
    case RecoveryCompleted => // INFO this is always called, even for new actors
      // perform init after recovery, before any other messages
      if (recoveryRunning) {
        log.info(s"Recovery completed for $persistenceId")
      } else {
        log.info(s"New actor created for $persistenceId")
      }
      self ! EvalActiveServables.defaultInstance
  }

  private def commands: Receive = {
    case command: HasSideEffects => handleSideEffectingCommand(command)
    case command: Command => handleNonSideEffectingCommand(command)
    case commandConfirmation: CommandDeliveryConfirmation => hanldeCommandConfirmation(commandConfirmation)
    case EvalActiveServables(c, su) => evalActiveServables(c, su)
    case TakeSnapshot =>
      log.info(s"Taking snapshot for $persistenceId")
      saveSnapshot(ServableProxyState(
        contracts,
        activeContractServables,
        servables,
        servablesWithLogging,
        servableMetrics,
        contractStats
      ))
    case Shutdown | ShardRegion.Passivate =>
      // safe shutdown. Poison Pill doesn't work with persistent actors due to persistent actor stash
      context.stop(self)

    /** Note:
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
      log.info(s"ServableProxy has had no activity for the configured ReceiveTimeout, passivating servables ${servables.keys}")
      context.parent ! ShardRegion.Passivate(Shutdown)
  }

  private def distributedData: Receive = {
    case Replicator.UpdateSuccess(ContractsKey, Some(event)) =>
      log.debug(s"Distributed Data Update successful: $event")

    case Replicator.UpdateTimeout(ContractsKey, Some(event)) =>
      log.warning(s"Update timeout for $ContractsKey : $event")

    case Replicator.StoreFailure(ContractsKey, Some(event)) =>
      log.error(s"Distributed Data Store failed for $ContractsKey : $event")

  }

  private def handleNonSideEffectingCommand(command: Command): Unit = {
    command match {
      case ScoreByContractGP(contract, reqBytes) =>
        // val inferRequest = InferRequest.getRootAsInferRequest(reqBytes.asReadOnlyByteBuffer())
            contractStats += (contract -> (contractStats.getOrElse(contract, 0L) + 1))
            activeContractServables.get(contract) match {
              case Some(router) =>
                router.next() match {
                  case Some(fqrv) =>
                    servableRegistry.forward(HighPriorityScoreRequestGP(fqrv, reqBytes))
                    servableMetrics(fqrv).scoreCount += 1
                    self ! ShadeRequestGP(excludeFQRV = Some(fqrv), reqBytes, persistenceId)
                  case _ =>
                    sender() ! NoActiveServablesForContract(contract)
                    self ! ShadeRequestGP(excludeFQRV = None, reqBytes, persistenceId)
                }
              case None if servables.exists { case (fqrv, _) => fqrv.contract == contract } =>
                sender() ! NoActiveServablesForContract(contract)
                self ! ShadeRequestGP(excludeFQRV = None, reqBytes, persistenceId)
              case _ =>
                sender() ! {
                  if (contracts.get(contract).isDefined)
                    NoActiveServablesForContract(contract)
                  else UnknownContract(contract)
                }
            }

/*      case ScoreByContract(contract, datum) =>
        contractStats += (contract -> (contractStats.getOrElse(contract, 0L) + 1))
        activeContractServables.get(contract) match {
          case Some(router) =>
            router.next() match {
              case Some(fqrv) =>
                servableRegistry.forward(HighPriorityScoreRequest(fqrv, datum))
                servableMetrics(fqrv).scoreCount += 1
                self ! ShadeRequest(excludeFQRV = Some(fqrv), datum, persistenceId)
              case _ =>
                sender() ! NoActiveServablesForContract(contract)
                self ! ShadeRequest(excludeFQRV = None, datum, persistenceId)
            }
          case None if servables.exists { case (fqrv, _) => fqrv.contract == contract } =>
            sender() ! NoActiveServablesForContract(contract)
            self ! ShadeRequest(excludeFQRV = None, datum, persistenceId)
          case _ =>
            sender() ! (if (contracts.get(contract).isDefined) NoActiveServablesForContract(contract) else UnknownContract(contract))
        }*/

      case ScoreByContract(contract, inferRequest) =>
        contractStats += (contract -> (contractStats.getOrElse(contract, 0L) + 1))
        activeContractServables.get(contract) match {
          case Some(router) =>
            router.next() match {
              case Some(fqrv) =>
                servableRegistry.forward(HighPriorityScoreRequest(fqrv, inferRequest))
                servableMetrics(fqrv).scoreCount += 1
                self ! ShadeRequest(excludeFQRV = Some(fqrv), inferRequest, persistenceId)
              case _ =>
                sender() ! NoActiveServablesForContract(contract)
                self ! ShadeRequest(excludeFQRV = None, inferRequest, persistenceId)
            }
          case None if servables.exists { case (fqrv, _) => fqrv.contract == contract } =>
            sender() ! NoActiveServablesForContract(contract)
          //            self ! ShadeRequest(excludeFQRV = None, inferRequest, persistenceId)
          case _ =>
            sender() ! (if (contracts.get(contract).isDefined) NoActiveServablesForContract(contract) else UnknownContract(contract))
        }

      /**
        * Shade requests go to all servables, minus the one that actually served the request, even if they aren't
        * valid yet because validity policies would otherwise be unable to use servable metrics
        */
      case shade: LowPriorityShadeRequest =>
        val servablesToShadow = shade.excludeFQRV match {
          case Some(ex) => servablesWithLogging.filterNot { case (fqrv, _) => fqrv == ex }
          case _ => servablesWithLogging
        }

        servablesToShadow
          .foreach { case (fqrv, loggingSettings) =>
            // TODO sample rate configurable
            if (loggingSettings.logLevel == LogLevel.FULL || (loggingSettings.logLevel == LogLevel.SAMPLE && contractStats(fqrv.contract) % 100 == 0))  {
                shade match {
                  case ShadeRequest(_, inferenceRequest, _) => servableRegistry ! LowPriorityScoreRequest(fqrv, inferenceRequest)
                  case ShadeRequestGP(_, reqBytes, _) => LowPriorityScoreRequestGP(fqrv, reqBytes)
                }
                servableMetrics(fqrv).shadeCount += 1
            }
          }

      case GetServableMetaDataGP(fqrv) =>
        servables.get(fqrv) match {
          case Some(_) => servableRegistry.forward(Registry_GetServableMetaDataGP(fqrv))
          case _ => sender() ! UnknownServable(fqrv)
        }

      case GetServableMetaData(fqrv) =>
        servables.get(fqrv) match {
          case Some(_) => servableRegistry.forward(Registry_GetServableMetaData(fqrv))
          case _ => sender() ! UnknownServable(fqrv)
        }

      case GetServableMetaDataByContractGP(contract) =>
        servables
          .keys
          .find(_.contract == contract)
          .foreach(fqrv => servableRegistry.forward(Registry_GetServableMetaDataGP(fqrv)))

      case GetServableMetaDataByContract(contract) =>
        val metaDataFuture = Future.sequence(
          servables.keys.filter(_.contract == contract)
            .map(fqrv => servableRegistry ? Registry_GetServableMetaData(fqrv))
        )
        metaDataFuture pipeTo sender()


      case GetContractSettings(contract) => sender() ! {
        contracts.get(contract) match {
          case Some(settings) => settings
          case _ => UnknownContract(contract)
        }
      }

      case GetServableSettings(fqrv) => sender() ! {
        servables.get(fqrv) match {
          case Some(settings) => settings
          case _ => UnknownServable(fqrv)
        }
      }
      case GetServableSettingsByContract(contract) => sender() ! {
        val settingsMap = servables.filter(_._1.contract == contract)
        if (settingsMap.isEmpty)
          if (contracts.get(contract).isDefined) NoActiveServablesForContract(contract) else UnknownContract(contract)
        else
          settingsMap.values
      }
      case GetServableMetrics(fqrv) => sender() ! {
        servableMetrics.get(fqrv) match {
          case Some(stats) => (fqrv, stats)
          case _ => UnknownServable(fqrv)
        }
      }
      case GetServableMetricsByContract(contract) => sender() ! {
        val statsMap = servableMetrics.filter(_._1.contract == contract)
        if (statsMap.isEmpty)
          if (contracts.get(contract).isDefined) NoActiveServablesForContract(contract) else UnknownContract(contract)
        else
          statsMap // map(s => Stats(s._1, s._2)).toList
      }
      case GetServableReleases(contract) => sender() ! {
        val releases = servables.filter(_._1.contract == contract).keys
        if (releases.isEmpty)
          if (contracts.get(contract).isDefined) NoActiveServablesForContract(contract) else UnknownContract(contract)
        else
          releases.toList
      }
    }
  }

  private def handleSideEffectingCommand(command: HasSideEffects) {
    command match {
      case UpdateContract(contract: Contract, contractSettings: ContractSettings) => handleUpdateContract(contract, contractSettings)
      case UpdateServable(fqrv,policySettings, loggingSettings) => handleUpdateServable(fqrv, policySettings, loggingSettings)
      case CreateServable(serveRequest) => handleCreateServable(serveRequest)
      case DeleteServable(fqrv) => handleDeleteServable(fqrv)
      case CreateContract(contract, contractSettings) => handleCreateContract(contract, contractSettings)
    }

    def handleCreateContract(contract: Contract, contractSettings: ContractSettings): Unit = {
      contracts.get(contract) match {
        case Some(_) => sender() ! ContractValidationError(contract, "Contract already exists. Use update API instead.")
        case None => persist(ContractSettingsCreated(contract, contractSettings)) { event: ContractSettingsCreated =>
          receiveRecover(event)
          sender() ! ContractCreatedSuccessfully(contract)
          log.info(s"Created contract $contract with ${event.contractSettings}")
        }
      }
    }

    def handleUpdateContract(contract: Contract, contractSettings: ContractSettings): Unit = {
      contracts.get(contract) match {
        case None => sender() ! UnknownContract(contract)
        case Some(_) => persist(ContractSettingsUpdated(contract, contractSettings)) { event: ContractSettingsUpdated =>
          receiveRecover(event)
          self ! EvalActiveServables(Some(contract), contractSettingsUpdated = true)
          sender() ! ContractUpdatedSuccessfully(contract)
          log.info(s"Updated contract $contract with ${event.contractSettings}")
        }
      }
    }

    def handleUpdateServable(fqrv: FQRV,policySettings: Option[PolicySettings],loggingSettings: Option[LoggingSettings]) {
      servables.get(fqrv) match {
        case Some(currentSettings) => update(currentSettings)
        case None => sender() ! UnknownServable(fqrv)
      }

      def update(currentSettings: ServableSettings) {
        val updatedSettings = ServableSettings(
          policySettings.getOrElse(currentSettings.policySettings),
          loggingSettings match {
            case Some(ls) => Some(ls)
            case None => currentSettings.loggingSettings
          })

        // Deliver and continue to persist since there's no validation ask from registry
        deliver(servableRegistry.path) { deliveryId =>
          Registry_UpdateServable(fqrv, updatedSettings, deliveryId, sender()) // pass original requester so we can respond to them?
        }

        persistUpdateServable(fqrv, updatedSettings)
      }
    }

    def handleCreateServable(serveRequest: ServeRequestShim): Unit = {
      val fqrv = serveRequest.getUltimateFQRV
      servables.get(fqrv) match {
        case Some(settings) =>
          sender() ! ServableAlreadyExistsError(fqrv, settings)

        case None =>
          val currentContractSettings = contracts.get(fqrv.contract)
          if (serveRequest.contractSettings.isDefined && currentContractSettings.isDefined) {
            sender() ! ContractValidationError(fqrv.contract, "Contract already exists. Cannot override contract settings with Serve Request. Providing contract settings with a serve request is only valid for new contracts")
          }
          else {
            val request = {
              if (currentContractSettings.isEmpty && serveRequest.contractSettings.isEmpty) {
                val contractSettings = domain.ContractSettings(KeepLatest(1), FairPhaseInPctBasedRouter())
                log.info(s"Supplying default contract settings $contractSettings for serve request: $serveRequest")
                serveRequest.withContractSettings(contractSettings)
              }
              else
                serveRequest
            }
            deliver(servableRegistry.path) { deliveryId =>
              Registry_CreateServable(request, deliveryId, sender()) // pass original requester so we can respond to them?
            }
          }
      }
    }

    def handleDeleteServable(fqrv: FQRV): Unit = {
      persistDeleteServable(List(fqrv), postAction = { servablesDeleted =>
        sender() ! ServablesDeletedSuccessfully(servablesDeleted.fqrv)
      })
    }
  }

  private def hanldeCommandConfirmation(commandConfirmation: CommandDeliveryConfirmation) {
    // Validate then Persist the event and respond to requester in case of errors

    // confirm delivery in all cases to close the loop
    log.debug(s"ServableProxy - Received confirmation for ${commandConfirmation.deliveryId}")
    confirmDelivery(commandConfirmation.deliveryId)

    commandConfirmation match {
      case Registry_ValidCreateServableRequest(_, serveRequest, requester) =>
        // request valid, this is where you persist and make local changes
        // nothing goes back to the requester yet.. sender is notified only after the event is persisted
        persistCreateServable(serveRequest) { event =>
          requester ! ServableCreatedSuccessfully(event.serveRequest.getUltimateFQRV)
          log.info(s"Created servable ${event.serveRequest}")
        }

      case Registry_InvalidCreateServableRequest(_, serveRequest, requester, errorMessage) =>
        // request invalid, let requester know.. no state to persist or change
        requester ! CreateServableRequestInvalidError(serveRequest, errorMessage)

      case Registry_ServableAlreadyExists(_, fqrv, servableSettings, requester) =>
        // Is this just a matter of delivery ACK failure that we have to handle?
        if (servables.contains(fqrv))
          requester ! ServableAlreadyExistsError(fqrv, servableSettings)
        else
          persistUpdateServable(fqrv, servableSettings)

      case Registry_UpdateForUnknownServableRequested(_, fqrv, requester) =>
        log.error(s"Servable Proxy knows about $fqrv but Servable Registry reported an attempted update for an Unknown Servable")
        requester ! UnknownServable(fqrv)

      case Registry_ValidUpdateReceived(_, fqrv, requester) =>
        requester ! ServableUpdatedSuccessfully(fqrv)

      case Registry_UpdateWithSameSettingsRequested(_, fqrv, requester) =>
        log.info(s"Update sent to Registry for $fqrv but Registry reports that update doesn't change anything. Reporting back with ServableUpdatedSuccessfully")
        requester ! ServableUpdatedSuccessfully(fqrv)

      case Registry_ValidDeleteReceived(_, fqrv, requester) =>
        log.info(s"Registry deleted servable successfully: $fqrv")

      case Registry_DeleteForUnknownServableRequested(_, fqrv, requester) =>
        if (recoveryRunning)
          log.info(s"Delete sent for $fqrv from Proxy to Registry during Proxy recovery. Registry reports servable doesn't exist. This is not a concern.")
        else
          log.error(s"Delete sent to Registry for $fqrv but Registry reports servable doesn't exist.")
    }
  }


  /**
    *
    * Gets valid servables for all contracts (or specific contract supplied)
    * Gets Phase-in percent for all valid servables
    * Builds a new Read-Fast Weighted Collection as the activeServables
    *
    * @param contract
    */
  private def evalActiveServables(contract: Option[Contract], contractSettingsUpdated: Boolean) {

    // produce a list of contracts and weighted FQRVs for servables that are active
    def getUpdatedContractList(contractMap: Map[Contract, List[(FQRV, ServableSettings)]]) = {

      def getValidList(candidates: List[(FQRV, ServableSettings)]): List[(FQRV, ServableSettings)] = {
        log.debug(s"Getting valid list of candidates from lifecycle settings ${candidates.map(_._2.policySettings.validityPolicy)}")
        candidates.filter(_._2.policySettings.validityPolicy.forall(rule => rule.isValid))
      }

      def updateServableMetrics(validList: List[(FQRV, ServableSettings)]) = {
        validList.map { case (fqrv, settings) =>
          val stats = servableMetrics.get(fqrv) match {
            case Some(stat) =>
              val phaseInPct = settings.phaseInPercent(stat)
              log.debug(s"Phase in percent for $fqrv is $phaseInPct")
              if (stat.becameValidAtMS.isEmpty)
                stat.copy(becameValidAtMS = Some(System.currentTimeMillis()), currentPhaseInPct = phaseInPct)
              else
                stat.copy(currentPhaseInPct = phaseInPct)
            case None =>
              ServableMetricsImpl.empty()
          }

          log.debug(s"Stats for $fqrv are $stats")

          persist(ServableMetricsUpdated((fqrv, stats))) { event =>
            receiveRecover(event)
          }
          (fqrv, stats)
        }
      }

      contractMap.map { case (c, releaseSettings) =>
        // get list of valid servables
        val validList: List[(FQRV, ServableSettings)] = getValidList(releaseSettings)

        val validStats: List[(FQRV, ServableMetricsImpl)] = updateServableMetrics(validList)

        (c, contracts(c).expirationPolicy.markExpired(validStats))
      }
    }

    val contractsToUpdate = contract match {
      case Some(c) =>
        servables.toList.filter(_._1.contract == c).groupBy(_._1.contract)
      case _ =>
        servables.toList.groupBy(_._1.contract)
    }

    log.debug(s"$persistenceId Evaluating active servables for the following contracts: ${contractsToUpdate.toString()}")
    val contractListUpdates = getUpdatedContractList(contractsToUpdate)
    log.debug(s"Evaluation results: ${contractListUpdates.toString()}")
    // update activeContractServables with valid & unexpired list of servables
    contractListUpdates
      .foreach { case (c, markedServables) =>
        val partitioned = markedServables.partition(_._2 == false)
        val (validServables, expiredServables) = (partitioned._1.unzip._1, partitioned._2.map(_._1._1))

        def updateActiveContractServables() = {
          activeContractServables.get(c) match {
            case Some(contractRouter) if !contractSettingsUpdated =>
              log.debug(s"Merging with updated servable metrics  $validServables")
              activeContractServables += (c -> contractRouter.merge(validServables))
            case _ =>
              log.debug(s"Rebuilding with new set of valid servables $validServables")
              activeContractServables += (c -> contracts(c).router.create(validServables))
          }
        }

        if (expiredServables.isEmpty)
          updateActiveContractServables()
        else {
          log.debug(s"Dropping expired servables $expiredServables")
          persistDeleteServable(expiredServables, { _ =>
            updateActiveContractServables()
          })
        }
      }

    timers.startSingleTimer(EvalActiveServablesKey, EvalActiveServables.defaultInstance, 60 seconds)
  }

  override def persistencePrefix: String = ServableProxy.SHARDING_TYPE_NAME
}