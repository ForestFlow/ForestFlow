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

import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props}
import akka.actor.SupervisorStrategy._

import scala.concurrent.duration._

object Supervisor {
  def props(decider: Decider) = Props(new Supervisor(decider))
}

/**
  * Actor Supervision requires a parent that defines the supervision strategy and acts on all children.
  * This actor acts as this parent supervisor, hence the name. An instnace of this parent can only
  * supervise a single actor type. If you have multiple actor types (classes that extend from Actor) then create
  * multiple instances of the supervisor for each type.
  * This supervisor can be used in 2 separate ways to assign it a child actor to supervise:
  *  - Either send a message of type Props for the child actor to be created
  *  - OR, provide an implicit props: Some[Props]
  *  In both cases, the provided Props instance will be used to create the child actor.
  *
  *  Note that the supervisor actor will forward all other messages to the child actor. This is an unchecked
  *  process for efficiency. i.e., if no child actor has been assigned, this will raise an exception.
  *
  *  Additionally, the "Ask" or Props via message approach provides additional optimizations in that the supervisor
  *  will respond with a message supplying the ActorRef for the child so you can communicate with it directly
  *  instead of going through the supervisor's forwarding strategy and as a result mailbox as well so this is
  *  the preferred approach in using the supervisor.
  *
  *  To keep this pattern flexible, the supervisor doesn't imply what the strategy actually is. Instead, this
  *  is supplied by the user as a decider argument. Here's an example
  *
  *   val mySupervisor = system.actorOf(Supervisor.props {
  *       case _: ArithmeticException => Resume
  *       case _: Exception => Restart
  *   })
  *
  *    private val mySupervisedChildActor = Await.result(
  *      mySupervisor
  *       .ask(childProps)
  *       .mapTo[ActorRef], some-timeout second)
  *
  *    mySupervisedChildActor ! "sending something"
  *
  * @param decider
  * @param props
  */
class Supervisor(decider: Decider)(implicit props: Option[Props] = None) extends Actor with ActorLogging {
  var realActor: ActorRef = _

  override val supervisorStrategy: OneForOneStrategy =
    OneForOneStrategy(maxNrOfRetries = -1, withinTimeRange = Duration.Inf)(decider)

  override def receive: Receive = {
    case p: Props =>
      realActor = context.actorOf(p)
      sender() ! realActor

    case msg => realActor.forward(msg)
  }

  override def preStart(): Unit = {
    props.foreach(p => realActor = context.actorOf(p))
  }

  // override default so we don't kill all children during restart
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    /* Do nothing */
    log.debug("preRestart for Supervisor. No op so we don't kill all children during restart.")
  }
}
