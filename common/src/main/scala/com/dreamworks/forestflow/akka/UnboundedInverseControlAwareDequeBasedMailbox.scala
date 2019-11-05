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
package com.dreamworks.forestflow.akka

import java.util
import java.util.concurrent.LinkedBlockingDeque

import akka.actor.{ActorRef, ActorSystem}
import akka.dispatch._
import com.typesafe.config.Config


object UnboundedInverseControlAwareDequeBasedMailbox {

  /**
    * InverseControlAwareMessageQueueSemantics handles messages that extend [[akka.dispatch.ControlMessage]] with lower priority.
    */
  trait InverseControlAwareMessageQueueSemantics extends QueueBasedMessageQueue {
    def controlQueue: util.Queue[Envelope]
    def queue: util.Queue[Envelope]

    def enqueue(receiver: ActorRef, handle: Envelope): Unit = handle match {
      case envelope @ Envelope(_: ControlMessage, _) ⇒ controlQueue add envelope
      case envelope                                  ⇒ queue add envelope
    }

    def dequeue(): Envelope = {
      val queueMsg = queue.poll()

      if (queueMsg ne null) queueMsg
      else controlQueue.poll()
    }

    override def numberOfMessages: Int = controlQueue.size() + queue.size()

    override def hasMessages: Boolean = !(queue.isEmpty && controlQueue.isEmpty)
  }

  // The message queue implementation
  class PriorityPersistenceAwareMessageQueue extends MessageQueue with UnboundedDequeBasedMessageQueueSemantics with InverseControlAwareMessageQueueSemantics {
    final val priorityMessages = new LinkedBlockingDeque[Envelope](1000)
    final val opportunisticMessages = new LinkedBlockingDeque[Envelope](1000)

    final val controlQueue: util.Queue[Envelope] = priorityMessages

    final val queue: util.Queue[Envelope] = opportunisticMessages

    override def enqueueFirst(receiver: ActorRef, handle: Envelope): Unit = handle match {
      case envelope @ Envelope(_: ControlMessage, _) ⇒ priorityMessages addFirst envelope
      case envelope                                  ⇒ opportunisticMessages addFirst envelope
    }
  }
}

class UnboundedInverseControlAwareDequeBasedMailbox extends MailboxType
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
