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
package com.dreamworks.forestflow.serving.config

import java.util.concurrent.TimeUnit

object RegistryConfigs {
  import ApplicationEnvironment.config

  lazy private val registryConfigs = config.getConfig("application.registry")

  lazy val ACTIVITY_TIMEOUT_SECS: Long = registryConfigs.getDuration("activity-timeout", TimeUnit.SECONDS)
  lazy val STATE_SNAPSHOT_TRIGGER_SECS: Long = registryConfigs.getDuration("state-snapshot-trigger", TimeUnit.SECONDS)
  lazy val REUSE_LOCAL_SERVABLE_COPY_ON_RECOVERY: Boolean = registryConfigs.getBoolean("reuse-local-servable-copy-on-recovery")
}
