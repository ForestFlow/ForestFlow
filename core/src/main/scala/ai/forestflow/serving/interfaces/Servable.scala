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
import Servable._
import graphpipe.InferRequest

import scala.util.Try


object Servable {

//  type Datum = Map[String, String]

}

/**
  * A servable is a unit of abstraction that provides standard "scoring" functionality and meta-data
  */
abstract class Servable{
  def fqrv: FQRV
  var settings: ServableSettings
  val logPredictions: Boolean = settings.loggingSettings.isDefined && settings.loggingSettings.get.logLevel != LogLevel.NONE
}

trait HasBasicSupport {
  this: Servable =>
  def getMetaData: BasicMetaData // maybe also provide types and (a list of acceptable translations? table this)
  def score(inferenceRequest: InferenceRequest): Try[Prediction]
}

trait HasGraphPipeSupport {
  this: Servable =>
  def getGPMetaData: Array[Byte]
  def scoreGP(datum: InferRequest): Array[Byte]
}
