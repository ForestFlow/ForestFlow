package ai.forestflow.serving.config

import java.util.concurrent.TimeUnit

import com.typesafe.config.Config

object S3Configs {

  lazy val config: Config = ApplicationEnvironment.config
  lazy val s3Configs: Config = config.getConfig("application.s3")
  lazy val S3_CONFIG_PATH: String = s3Configs.getString("config-path")
  lazy val S3_DOWNLOAD_TIMEOUT_SECS: Long = {
    val duration = s3Configs.getDuration("download-timeout", TimeUnit.SECONDS)
    require(duration > 1, "s3 download-timeout cannot be less than 1 second")
    duration
  }
  lazy val S3_ACCESS_KEY_ID_POSTFIX: String = s3Configs.getString("credentials.access-key-id-postfix")
  lazy val S3_SECRET_ACCESS_KEY_POSTFIX: String = s3Configs.getString("credentials.secret-access-key-postfix")

}
