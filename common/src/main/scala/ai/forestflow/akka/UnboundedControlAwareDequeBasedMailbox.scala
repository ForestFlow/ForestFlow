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
package ai.forestflow.akka

import java.util
import java.util.concurrent.LinkedBlockingDeque

import akka.actor.{ActorRef, ActorSystem}
import akka.dispatch._
import com.typesafe.config.Config

import scala.concurrent.duration.Duration

object UnboundedControlAwareDequeBasedMailbox {

  // The message queue implementation
  class PriorityPersistenceAwareMessageQueue extends MessageQueue with BoundedDequeBasedMessageQueueSemantics with ControlAwareMessageQueueSemantics {
    final val priorityMessages = new LinkedBlockingDeque[Envelope]()
    final val opportunisticMessages = new LinkedBlockingDeque[Envelope]()

    final val controlQueue: util.Queue[Envelope] = priorityMessages

    final val queue: util.Queue[Envelope] = opportunisticMessages

    override def enqueueFirst(receiver: ActorRef, handle: Envelope): Unit = handle match {
      case envelope @ Envelope(_: ControlMessage, _) ⇒ priorityMessages addFirst envelope
      case envelope                                  ⇒ opportunisticMessages addFirst envelope
    }

    override def pushTimeOut: Duration = ???
  }
}

class UnboundedControlAwareDequeBasedMailbox extends MailboxType
  with ProducesMessageQueue[UnboundedControlAwareDequeBasedMailbox.PriorityPersistenceAwareMessageQueue] {

  import UnboundedControlAwareDequeBasedMailbox._

  // This constructor signature must exist, it will be called by Akka
  def this(settings: ActorSystem.Settings, config: Config) = {
    // put your initialization code here
    this()
  }

  final override def create(owner: Option[ActorRef], system: Option[ActorSystem]): MessageQueue = {
    new PriorityPersistenceAwareMessageQueue()
  }
}
