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

import io.circe.Decoder
import io.circe.generic.extras._
import io.circe.generic.extras.semiauto.deriveDecoder

case class H2OMLFlowSpec (
  fullFile: String,

  modelDir: String,

  modelFile: String
) {

}


object H2OMLFlowSpec {

  implicit val config: Configuration = {
    val baseConfig = Configuration.default.withSnakeCaseMemberNames
    baseConfig.copy(transformMemberNames = baseConfig.transformMemberNames andThen {
      case "artifact_reader" => "artifact_path" // from snake_case in class to snake_case file
      case other => other
    })
  }

  implicit val decodeH2OMLFlowSpec : Decoder[H2OMLFlowSpec] = deriveDecoder[H2OMLFlowSpec]

}
