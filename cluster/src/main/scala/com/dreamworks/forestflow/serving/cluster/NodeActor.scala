package com.dreamworks.forestflow.serving.cluster

import java.io.File

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Timers}
import akka.cluster.Cluster
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Subscribe
import com.dreamworks.forestflow.domain.CleanupLocalStorage
import org.apache.commons.io.FileUtils
import com.typesafe.scalalogging.LazyLogging
import com.dreamworks.forestflow.utils.ThrowableImplicits._

import scala.util.{Failure, Success, Try}

object NodeActor extends LazyLogging {
  /* Utils */
  def props(): Props =
    Props(new NodeActor)
      .withDispatcher("blocking-io-dispatcher")

  def cleanupLocalStorage(path: String): Unit = {
    val localDir = new File(path)
    val localDirExists = localDir.exists()
    logger.info(s"Cleaning up local storage: Local Directory: $localDir , exists? $localDirExists")
    if (localDirExists)
      Try(FileUtils.deleteDirectory(localDir)) match {
        case Success(_) => logger.info(s"Local Directory $localDir cleaned up successfully")
        case Failure(ex) => logger.error(s"Local Directory $localDir cleanup failed! Reason: ${ex.printableStackTrace}")
      }
  }
}

class NodeActor extends Actor
  with ActorLogging
  with Timers {

  /* activate extensions */
  implicit val cluster: Cluster = Cluster(context.system)
  val mediator: ActorRef = DistributedPubSub(context.system).mediator

  mediator ! Subscribe(classOf[CleanupLocalStorage].getSimpleName, self)

  override def receive: Receive = {
    case CleanupLocalStorage(path) =>
      NodeActor.cleanupLocalStorage(path)
  }
}
