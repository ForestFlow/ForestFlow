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
package com.dreamworks.forestflow.serving.MLFlow

import cats.syntax.either._
import com.dreamworks.forestflow.serving.impl.{LocalFileArtifactReader, MLFlowH2OLoader, UnsupportedServableFlavor}
import com.dreamworks.forestflow.serving.interfaces.ArtifactReader
import com.dreamworks.forestflow.utils.SourceStorageProtocols
import io.circe.generic.extras._
import io.circe.generic.extras.semiauto.deriveDecoder
import io.circe.{CursorOp, Decoder, DecodingFailure}
import com.dreamworks.forestflow.serving.interfaces.Loader
import com.dreamworks.forestflow.utils.ThrowableImplicits._
import org.joda.time.{DateTimeZone, LocalDateTime}


case class MLFlowModelSpec(
  artifactReader: ArtifactReader,

  runId: Option[String],

  timeCreated: Long,

  flavors: Map[String, Loader]
) {
  def getServableFlavor: Option[(String, Loader)] = flavors.collectFirst { case (flavor, loader) if !loader.isInstanceOf[UnsupportedServableFlavor] => (flavor, loader) }
}

object MLFlowModelSpec {

  implicit val config: Configuration = {
    val baseConfig = Configuration.default.withSnakeCaseMemberNames
    baseConfig.copy(transformMemberNames = baseConfig.transformMemberNames andThen {
      // from snake_case in class to snake_case file
      case "artifact_reader" => "artifact_path"
      case "time_created" => "utc_time_created" // utc_time_created is a string!
      case other => other
    })
  }

  implicit val decodeTimeCreated: Decoder[Long] = Decoder.decodeString.emap{ tm: String =>
    Either.catchNonFatal[Long]({
      var ts = tm.replace(" ", "T")
      if (ts.takeRight(1) != "Z")
        ts = ts + "Z"
      val ll = LocalDateTime.parse(tm.replace(" ", "T")).toDateTime(DateTimeZone.UTC)
      ll.getMillis
    }
    ).leftMap(t => s"timeCreated Decoder Failed: ${t.printableStackTrace}")
  }.handleErrorWith(_ => Decoder.decodeLong)

  implicit val decodeMLFlowModel: Decoder[MLFlowModelSpec] = deriveDecoder[MLFlowModelSpec]

  implicit val decodeArtifactReaderString: Decoder[ArtifactReader] = Decoder.decodeOption[String].emap { artifactPath: Option[String] =>
    Either.catchNonFatal[ArtifactReader]({
      artifactPath match {
        case Some(path) => ArtifactReader.getArtifactReader(path)
        case _ => LocalFileArtifactReader("")
      }
    }
    ).leftMap(t => s"Artifact Reader Decoder Failed: ${t.printableStackTrace}")
  }

  implicit val decodeServableFlavor: Decoder[Map[String, Loader]] = Decoder.decodeMap[String, Map[String, String]].emap { flavors =>
    Either.catchNonFatal[Map[String, Loader]](
      flavors
        .map { case (flavor, props) => (flavor.toLowerCase, props) }
        .map {
          case (f@"h2o_mojo", props) => f -> MLFlowH2OLoader(dataPath = props.getOrElse("data", ""), version = props.get("h2o_version"))
          /***
            * UnsupportedServableFlavor catch-all case must exist otherwise any flavor that we don't support will
            * immediately raise an error without checking ability for support of other supplied flavors.
             */
          case (f, props) => f -> UnsupportedServableFlavor(props)
          case (f, props) => throw DecodingFailure(s"Unexpected or unsupported flavor type [$f] with props $props", List[CursorOp]())
          // TODO: Support POJO?
          // case (f, _) => p -> BasicSourceProvider()
        }
    ).leftMap(t => t.printableStackTrace)

  }

}





