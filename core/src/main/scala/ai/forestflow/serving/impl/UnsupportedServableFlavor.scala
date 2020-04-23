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
package ai.forestflow.serving.impl

import ai.forestflow.serving.interfaces.{Loader, Servable}
import ai.forestflow.domain.{FQRV, ServableSettings}
import ai.forestflow.serving.interfaces.{Loader, Servable}

case class UnsupportedServableFlavor(props: Map[String, String]) extends Loader {
  override def createServable(servableBinary: Array[Byte], fqrv: FQRV, settings: ServableSettings)(implicit eCTX: EnvironmentContext): Servable = null
  override def getRelativeServablePath(implicit eCTX: EnvironmentContext): String = null
}
