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

import com.dreamworks.forestflow.domain.{ExpirationPolicy, FQRV}
import com.dreamworks.forestflow.domain.Ordering.{ASC, DESC}
import com.dreamworks.forestflow.domain.{Ordering => ORD}

object ExpirationPolicies {


  trait KeepLatestImpl {
    this: ExpirationPolicy =>
    def servablesToKeep: Int

    override def markExpired(servables: List[(FQRV, ServableMetricsImpl)]): List[((FQRV, ServableMetricsImpl), Boolean)] = {
      if (servables.length <= servablesToKeep)
        servables.map((_, false))
      else {
        servables
          .sortWith((a, b) => a._2.becameValidAtMS.getOrElse(0L) > b._2.becameValidAtMS.getOrElse(0L))
          .zipWithIndex
          .map { case (servStats, index) => (servStats, if (index < servablesToKeep) false else true) }
      }
    }
  }

  trait KeepTopRankedImpl {
    this: ExpirationPolicy =>

    def servablesToKeep: Int

    def metricToEvaluate: String

    def ordering: Option[ORD]

    override def markExpired(servables: List[(FQRV, ServableMetricsImpl)]): List[((FQRV, ServableMetricsImpl), Boolean)] = {
      if (servables.length <= servablesToKeep)
        servables.map((_, false))
      else {
        (ordering.getOrElse(DESC) match {
          case DESC =>
            servables
              .sortWith((a, b) =>
                a._2.performanceMetrics.getOrElse(metricToEvaluate, 0F) > b._2.performanceMetrics.getOrElse(metricToEvaluate, 0F)
              )
          case ASC =>
            servables
              .sortWith((a, b) =>
                a._2.performanceMetrics.getOrElse(metricToEvaluate, 0F) < b._2.performanceMetrics.getOrElse(metricToEvaluate, 0F)
              )
        })
          .zipWithIndex
          // Calculate Real Rank = (phaseInPct / performance-based Rank)
          .map { case (servStats, perfRank) => (servStats, servStats._2.currentPhaseInPct / perfRank) }
          // Sort on Real Rank
          .sortWith((a, b) => a._2 > b._2)
          .zipWithIndex
          .map { case (realRank, index) => (realRank._1, if (index < servablesToKeep) false else true) }
      }
    }

  }

}
