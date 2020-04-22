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
package ai.forestflow.serving.interfaces

import akka.dispatch.ControlMessage
import ai.forestflow.domain.{Contract, FQRV, InferenceRequest, ServeRequestShim}
import com.google.protobuf.{ByteString => protoBString}
import ai.forestflow.domain.ShimImplicits._

object Protocol {
  /* traits */
  /**
    * Persistent actors (Commands and Events)
    * Command: Hey actor, Do something
    * Event: Something has been done
    * All commands the ServableProxy and ServableRegistry actors accepts must implement Command
    */
  trait Command {
    def id: String
  }

  trait ProxyCommandWithContract extends Command {
    def contract: Contract

    override def id: String = contract.toString
  }

  trait ProxyCommandWithFQRV extends Command {
    def fqrv: FQRV

    override def id: String = fqrv.contract.toString
  }

  trait ProxyCommandWithServeRequest extends Command {
    def serveRequest: ServeRequestShim

    override def id: String = {
      serveRequest.getUltimateFQRV.contract.toString
    }
  }

  trait RegistryCommand extends Command {
    def fqrv: FQRV

    override def id: String = fqrv.toString
  }

  trait RegistryCommandWithServeRequest extends Command {
    def serveRequest: ServeRequestShim

    override def id: String = {
      serveRequest.getUltimateFQRV.toString
    }
  }

  trait Score extends RegistryCommand {
    val fqrv: FQRV
  }

  trait BasicScore extends Score {
    val inferenceRequest: InferenceRequest
  }

  trait GraphPipeScore extends Score {
    val reqBytes: protoBString
  }

  trait LowPriorityShadeRequest extends ControlMessage with Command {
    val excludeFQRV: Option[FQRV]
  }

  /**
    * Records that an event has occured
    */
  trait Event

  /**
    * All commands that have side effects, i.e, hae an effect on the state of servables MUST extend from HasSideEffects
    * This is used to ensure all side effects take appropriate actions while keeping the code DRY
    * Example: ensuring servableUpdated is called which subsequently triggers an evaluation of activeServables
    */
  trait HasSideEffects

  /**
    * All events published to the event bus from this actor MUST extend from EventNotifications.
    * This allows subscribers to be specific about what they subscribe to or to ask for everything from this class.
    */
  trait EventNotifications


  trait CommandDeliveryConfirmation {
    val deliveryId: Long
  }

}
