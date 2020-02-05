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
package com.dreamworks.forestflow.domain

import com.dreamworks.forestflow.serving.impl.ServableMetricsImpl
import com.dreamworks.forestflow.serving.interfaces.{RouterType, ServableMetaData, ServeRequest}

import scala.language.implicitConversions

object ShimImplicits {
  implicit def ValidityRuleShim2Base(shim: ValidityRuleShim): ValidityRule = shim.asInstanceOf[ValidityRule]
  implicit def PhaseInPolicyShim2Base(shim: PhaseInPolicyShim): PhaseInPolicy = shim.asInstanceOf[PhaseInPolicy]
  implicit def RouterTypeShim2Base(shim: RouterTypeShim): RouterType = shim.asInstanceOf[RouterType]
  implicit def ExpirationPolicyShim2Base(shim: ExpirationPolicyShim): ExpirationPolicy = shim.asInstanceOf[ExpirationPolicy]
  implicit def ServeRequestShim2Base(shim: ServeRequestShim) : ServeRequest = shim.asInstanceOf[ServeRequest]

/*  implicit def ServableFeaturesShim2Base(shim: ServableFeaturesShim): ServableFeatures = shim.asInstanceOf[ServableFeatures]
  implicit def ServableFeaturesShim2Message(shim: ServableFeaturesShim): ServableFeaturesShimMessage = shim.asMessage*/

  implicit def ServableMetaDataShim2Base(shim: ServableMetaDataShim): ServableMetaData = shim.asInstanceOf[ServableMetaData]
  implicit def ServableMetaDataShim2Message(shim: ServableMetaDataShim): ServableMetaDataShimMessage = shim.asMessage

  implicit def ServableMetrics2ServableMetricsImpl(metrics: ServableMetrics) : ServableMetricsImpl = ServableMetricsImpl(
    metrics.scoreCount,
    metrics.shadeCount,
    metrics.createdAtMS,
    metrics.performanceMetrics,
    metrics.becameValidAtMS,
    metrics.currentPhaseInPct
  )
  implicit def ServableMetricsImpl2ServableMetrics(metrics: (FQRV, ServableMetricsImpl)) : ServableMetrics = ServableMetrics(
    metrics._1,
    metrics._2.scoreCount,
    metrics._2.shadeCount,
    metrics._2.createdAtMS,
    metrics._2.performanceMetrics,
    metrics._2.becameValidAtMS,
    metrics._2.currentPhaseInPct
  )
}
