package ai.forestflow.serving.cluster

import akka.actor.{Actor, ActorLogging}
import akka.persistence.PersistentActor

trait HasPersistence extends ActorLogging {
  this: PersistentActor =>

  def persistencePrefix: String

  override def persistenceId: String = {
    /*log.info(s"Getting persistenceId: akka.serialization.Serialization.serializedActorPath(self) = ${akka.serialization.Serialization.serializedActorPath(self)}")
    log.info(s"self.path.address = ${self.path.address}")
    log.info(s"self.path.elements.toList.mkString('-') = ${self.path.elements.toList.mkString("-")}")
    log.info(s"self.path.elements.toString() = ${self.path.elements.toString()}")
    log.info(s"self.path.toStringWithAddress(self.path.address) = ${self.path.toStringWithAddress(self.path.address)}")
    log.info(s"self.path.toString = ${self.path.toString}")*/
    s"$persistencePrefix-${context.parent.path.name}-${self.path.name}"
  }
}
