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
package com.dreamworks.forestflow.event.subscribers

import java.nio.ByteOrder

import akka.actor.{Actor, ActorLogging, Props}
import akka.kafka.ProducerSettings
import com.dreamworks.forestflow.domain.{PredictionEvent, PredictionEventGP}
import com.dreamworks.forestflow.serving.config.ApplicationEnvironment
import graphpipe.InferRequest
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{ByteArraySerializer, StringSerializer}
//import scalapb.json4s.JsonFormat

import scala.util.{Success, Try}

object PredictionLogger {
  /* Utils */
  def props(basic_topic: Option[String], gp_topic: Option[String]): Props = {
    require(basic_topic.isDefined || gp_topic.isDefined, "At least one of the output topics needs to be defined to use a PredictionLogger")
    Props(new PredictionLogger(basic_topic, gp_topic))
  }

}

class PredictionLogger(basic_topic: Option[String], gp_topic: Option[String]) extends Actor with ActorLogging{

  private val config = ApplicationEnvironment.config
  private val producerConfig = config.getConfig("akka.kafka.producer")

  /*private lazy val stringProducerSettings =
    ProducerSettings(producerConfig, new StringSerializer, new StringSerializer)
  private lazy val stringProducer = stringProducerSettings.createKafkaProducer()*/

  private lazy val binaryProducerSettings =
    ProducerSettings(producerConfig, new StringSerializer, new ByteArraySerializer)
  private lazy val binaryProducer = binaryProducerSettings.createKafkaProducer()

  override def preStart(): Unit = {
    if (basic_topic.isDefined)
      context.system.eventStream.subscribe(self, classOf[PredictionEvent])

    if (gp_topic.isDefined)
      context.system.eventStream.subscribe(self, classOf[PredictionEventGP])
    super.preStart()
  }
  override def receive: Receive = {
    case event@PredictionEvent(prediction, servedRequest, inferenceRequest, loggingSettings) =>

      val key = loggingSettings
        .keyFeatures
        .flatMap(inferenceRequest.configs.get)
        .mkString(loggingSettings.getKeyFeaturesSeparator)

      if (key.length > 0 )
        binaryProducer.send(new ProducerRecord(basic_topic.get, key, event.toByteArray))
      else
        binaryProducer.send(new ProducerRecord(basic_topic.get, event.toByteArray))

    case event@PredictionEventGP(prediction, servedRequest, inferBytes, loggingSettings) =>
      Try {
        val req = graphpipe.Request.getRootAsRequest(inferBytes.asReadOnlyByteBuffer().order(ByteOrder.LITTLE_ENDIAN))
        val inferRequest = req.req(new InferRequest()).asInstanceOf[InferRequest]
        val inferConfigs = inferRequest.config()
          .split(",")
          .map(_.split(":"))
          .flatMap{ case Array(k, v) =>  Some((k, v)) case _ => None}.toMap

        loggingSettings
          .keyFeatures
          .flatMap(inferConfigs.get)
          .mkString(loggingSettings.getKeyFeaturesSeparator)

      } match {
        case Success(key) =>
          binaryProducer.send(new ProducerRecord(gp_topic.get, key, event.toByteArray))
        case _ =>
          binaryProducer.send(new ProducerRecord(gp_topic.get, event.toByteArray))
      }

    case _ => // ignore
  }
}
