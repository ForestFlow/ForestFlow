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

import com.typesafe.config.{Config, ConfigFactory, ConfigRenderOptions}
import com.typesafe.scalalogging.StrictLogging

import scala.util.Try

//noinspection TypeAnnotation
object ApplicationEnvironment extends StrictLogging {

  def getConfig(env: Option[String]): Config = {
    val base = ConfigFactory.load()
    val defaults = base.getConfig("defaults")
    env match {
      case Some(envName) => base.getConfig(envName) withFallback defaults
      case None => defaults
    }
  }

  private lazy val applicationEnvironment = Try(ConfigFactory.load().getString("application.environment")).toOption
  logger.info(s"Application environment: $applicationEnvironment")
  lazy val config: Config = getConfig(applicationEnvironment)
  logger.debug(config.root().render(ConfigRenderOptions.concise()))

  lazy val SYSTEM_NAME = config.getString("application.system-name")

  lazy val MAX_NUMBER_OF_SHARDS = {
    val maxShards = config.getInt("application.max-number-of-shards")
    require(maxShards >= 1, "max-number-of-shards must be >= 1")
    maxShards
  }
  lazy val HTTP_COMMAND_TIMEOUT_SECS = {
    val duration = config.getDuration("application.http-command-timeout", TimeUnit.SECONDS)
    require(duration > 1, "http-command-timeout cannot be less than 1 second")
    duration
  }
  lazy val HTTP_PORT = config.getInt("application.http-port")
  lazy val HTTP_BIND_ADDRESS = Try(config.getString("http-bind-address")).getOrElse("0.0.0.0")

}
