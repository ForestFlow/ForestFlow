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
package com.dreamworks.forestflow.serving.impl

import java.io.{ByteArrayInputStream, FileReader}
import java.nio.file.Paths

import cats.syntax.either._
import com.dreamworks.forestflow.domain.{FQRV, FlavorShim, ServableSettings}
import com.dreamworks.forestflow.serving.MLFlow.H2OMLFlowSpec
import com.dreamworks.forestflow.serving.interfaces.Loader
import hex.genmodel.MojoReaderBackendFactory
import hex.genmodel.MojoReaderBackendFactory.CachingStrategy
import io.circe.{Error, yaml}

trait H2OLoader extends Loader {
  def version: Option[String]
  override def createServable(servableBinary: Array[Byte], fqrv: FQRV, settings: ServableSettings)(implicit eCTX: EnvironmentContext): H2OServable = {
    import hex.genmodel.MojoModel

    val mojoReader = MojoReaderBackendFactory.createReaderBackend(
      new ByteArrayInputStream(servableBinary),
      CachingStrategy.MEMORY)

    H2OServable(MojoModel.load(mojoReader), fqrv, settings)
  }
}

case class MLFlowH2OLoader(dataPath: String, version: Option[String]) extends H2OLoader {

  override def getRelativeServablePath(implicit eCTX: EnvironmentContext): String = {
    val json = yaml.parser.parse(new FileReader(Paths.get(eCTX.localDir.getAbsolutePath, dataPath, "h2o.yaml").toFile)) // TODO move "h2o.yaml" constant to configuration

    val h2oSpec = json
      .leftMap(err => err: Error)
      .flatMap(_.as[H2OMLFlowSpec])
      .valueOr(throw _)

    Paths.get(dataPath, h2oSpec.modelFile).toString
  }
}


trait BasicH2OMojoLoader extends H2OLoader  {
  this : FlavorShim with Loader =>
  val mojoPath: String
  val version: Option[String]

  override def getRelativeServablePath(implicit eCTX: EnvironmentContext): String = mojoPath
}