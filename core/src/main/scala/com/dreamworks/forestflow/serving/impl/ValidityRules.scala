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

import com.dreamworks.forestflow.domain.ValidityRule

object ValidityRules {

  trait MinServedEventsBasedValidityImpl {
    this: ValidityRule =>
    def validAfterMinServedEvents: Long
    override def isValid = true
  }

  trait TimeBasedValidityImpl {
    this: ValidityRule =>
    def validAfterMS: Long
    override def isValid = true
  }

  trait PerformanceBasedValidityImpl {
    this: ValidityRule =>
    def minThreshold: Float
    override def isValid = true
  }

  trait NeverValidImpl {
    this: ValidityRule =>
    override def isValid = false

    override def +(other: ValidityRule): Boolean = false
  }

  trait ImmediatelyValidImpl {
    this: ValidityRule =>
    def isValid = true

    override def +(other: ValidityRule): Boolean = true
  }

}
