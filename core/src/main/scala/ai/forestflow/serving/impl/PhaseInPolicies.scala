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

import ai.forestflow.domain.PhaseInPolicy

object PhaseInPolicies {

  trait LinearTimeBasedPhaseInImpl {
    this: PhaseInPolicy =>
    def phaseInOverSecs: Int
    private val msToPercent = phaseInOverSecs * 10L // (phaseInOverSecs / 100) * 1000
    override def getPhaseInPercent(servableStats: ServableMetricsImpl): Int = {
      math.min((System.currentTimeMillis() - servableStats.becameValidAtMS.getOrElse(System.currentTimeMillis())) / msToPercent, 100).toInt
    }
  }

  trait ImmediatePhaseInImpl {
    this: PhaseInPolicy =>
    /**
      *
      * @return 0 - 100 defining the percentage of events to send to this model
      */
    override def getPhaseInPercent(servableStats: ServableMetricsImpl): Int = 100
  }

}
