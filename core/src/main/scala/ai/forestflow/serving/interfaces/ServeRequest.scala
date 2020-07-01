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

import ai.forestflow.domain._
import ai.forestflow.utils.SourceStorageProtocols
import ai.forestflow.utils.SourceStorageProtocols.SupportsFQRVExtraction


trait ServeRequest extends Product {
  this: ServeRequestShim => // if you implement ServeRequest then you must extend ServeRequestShim as well (gotta think about this)
  def path: String

  def artifactPath : Option[String]

  protected[this] def protocolOpt = SourceStorageProtocols.getProtocolOption(path)

  def fqrv: Option[FQRV]

  def servableSettings: ServableSettings

  val contractSettings : Option[ContractSettings]

  def tags : Map[String,String]

  def withServableSettings(servableSettings: ServableSettings): ServeRequestShim
  def withContractSettings(contractSettings: ContractSettings): ServeRequestShim

  /**
    * Use to resolve an FQRV based on protocol path or supplied fqrv.proto
    * Supplied fqrv.proto is guarded for correctness (availability) by pathRequirements and require statements
    *
    * @return
    */
  def getUltimateFQRV: FQRV = {
    protocolOpt match {
      case Some(p: SupportsFQRVExtraction) =>
        fqrv.getOrElse(p.getFQRV(path).get)
      case _ =>
        fqrv.get
    }
  }

  def checkRequirements(): Unit = {
    def pathRequirement = {
      protocolOpt match {
        case None => (false, Some(s"path provided doesn't follow a supported protocol: $path"))
        case Some(protocol: SupportsFQRVExtraction) if protocol.hasValidFQRV(path) =>
          (true, None) // "Received request for protocol with SupportsFQRVExtraction"
        case Some(p: SourceStorageProtocols.EnumVal) if fqrv.isEmpty => (false, Some(s"FQRV (Fully Qualified Release Version) is required with protocols that don't have implicit FQRV extraction support or where path doesn't follow FQRV extraction requirements"))
        case Some(p: SourceStorageProtocols.EnumVal) => (true, None)
      }
    }

    val (pathValid, msg) = pathRequirement
    require(pathValid, msg.getOrElse("Invalid path"))
    require(
      servableSettings.loggingSettings.isDefined &&
        !(servableSettings.loggingSettings.get.logLevel != LogLevel.NONE &&
          servableSettings.loggingSettings.get.keyFeatures.isEmpty
          ),
      "LogLevel cannot be specified without defining set of features that define the key for logged messages")
  }
}

/*
trait MLFlowModel {
  this: ServeRequest =>
}

trait BasicModel {
  this: ServeRequest =>
}*/
