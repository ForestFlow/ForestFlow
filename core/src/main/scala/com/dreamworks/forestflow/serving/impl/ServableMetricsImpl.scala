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

import scala.collection.mutable

object ServableMetricsImpl {
  def empty() = ServableMetricsImpl(0, 0, System.currentTimeMillis(), mutable.Map.empty[String, Float], None, 0)
}

case class ServableMetricsImpl(
  var scoreCount: Long,
  var shadeCount: Long,
  var createdAtMS: Long,
  var performanceMetrics : mutable.Map[String, Float],
  var becameValidAtMS: Option[Long],
  var currentPhaseInPct: Int
) {
  require(currentPhaseInPct <= 100 && currentPhaseInPct >=0, "App: currentPhaseInPct can only be set to a value between 0 and 100")

}
