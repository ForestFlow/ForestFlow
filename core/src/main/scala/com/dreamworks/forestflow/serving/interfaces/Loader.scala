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
package com.dreamworks.forestflow.serving.interfaces

import com.dreamworks.forestflow.domain.{FQRV, ServableSettings}
import com.dreamworks.forestflow.serving.impl.EnvironmentContext

/**
  * Loaders manage a servable's life cycle. The Loader API enables common infrastructure independent from specific
  * learning algorithms, data or product use-cases involved. Specifically, Loaders standardize the APIs for loading and
  * unloading a servable.
  */
trait Loader { // INFO: Loader is model type specific (Like Flavor in MLFlow)

  // def createServable: Servable

  def createServable(servableBinary: Array[Byte], fqrv: FQRV, settings: ServableSettings)(implicit eCTX: EnvironmentContext) : Servable

  /**
    * Path is relative to an optional base path called: artifact_path Example
    * artifact_path: model
    * flavors:
    *   myflavor:
    *     myflavor_model_file: myflavor.modelformat
    * @return
    *         Returns myflavor.modelformat relative to artifact_path
    *         If artifact_path is not provided, local is assumed
    *         artifact_path can also be relative (assumes local) or absolute with a protocol like hdfs://mymodel/
    *
    */
  def getRelativeServablePath(implicit eCTX: EnvironmentContext) : String
}