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
package ai.forestflow.utils

import java.io.File
import java.net.URI
import java.nio.file.Paths

import ai.forestflow.domain.{Contract, FQRV}
import ai.forestflow.serving.config.S3Configs._
import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.alpakka.s3.{ObjectMetadata, S3Attributes, S3Settings, scaladsl}
import akka.stream.scaladsl.{FileIO, Source}
import akka.stream.{ActorMaterializer, IOResult}
import akka.util.{ByteString, Timeout}
import com.typesafe.scalalogging.StrictLogging
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.CloneCommand
import org.eclipse.jgit.transport.{CredentialItem, CredentialsProvider, URIish}
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, AwsCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.providers.AwsRegionProvider

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.implicitConversions
import scala.util.{Failure, Success, Try}

/**
  * The different protocols we can understand and support
  */
object SourceStorageProtocols extends StrictLogging {

  sealed trait EnumVal {
    protected def downloadImpl(remotePath: String, artifactName: String, localDirectory: File, fqrv: FQRV, sslVerify: Boolean, tags : Map[String,String])
                              (implicit system: ActorSystem, blockingIODispatcher : ExecutionContext, materializer : ActorMaterializer): Unit

    /**
     *
     * @return True if the protocol downloads a single file given an artifact name or an entire directory of files
     *         for example in the case of Git clones (as opposed to a single raw file from Git) where cloning
     *         the repo retrieves the contents of the entire repository and not just the artifactName in question.
     *         This is useful for when indirect links are used for the servable for example in the case of MLFlowModelSpec
     *         where the yaml file can have a "path" value that further directs us where to get a servable from.
     *         In the case of Git, this helps us know that we don't need to clone the repo again because the artifact
     *         would've already been downloaded given a relative "path" in the MLmodel yaml file and original path in
     *         the MLFlowServeRequest that references a git repository.
     */
    def singleFileProtocol : Boolean

    def download(remotePath: String, artifactName: String, localDirectory: File, fqrv: FQRV, sslVerify: Boolean, tags : Map[String,String])
      (implicit system: ActorSystem, blockingIODispatcher : ExecutionContext, materializer : ActorMaterializer): Unit = {
      require(localDirectory.isDirectory, s"Local file path supplied isn't a directory: ${localDirectory.getPath}")
      require(localDirectory.canWrite && localDirectory.canRead, s"Cannot ${List(if (localDirectory.canRead) "read" else "",if (localDirectory.canWrite) "write" else "").mkString(" and ")} to the local directory provided: ${localDirectory.getPath}")
      require(localDirectory.listFiles().length == 0, s"Local directory provided must be empty. Contains ${localDirectory.listFiles().length} files.")

      downloadImpl(remotePath, artifactName, localDirectory, fqrv, sslVerify, tags)
    }
  }

  sealed trait SupportsFQRVExtraction {
    def hasValidFQRV(path: String): Boolean

    def getFQRV(path: String): Option[FQRV]
  }

  /**
    * Regex patterns that match a string to a protocol.
    * INFO: This is done for simplicity and may change to adopt a standard library that does this
    */
  val ProtocolPatterns = List(
    //      (SourceStorageProtocols.GIT, "^(git@.*)".r), // "^(.*).git(.*)".r
    (SourceStorageProtocols.GIT, "^(.*)\\.git(.*)".r), // Covers http(s) and git@ requests as well. Anything that has a .git
    (SourceStorageProtocols.HTTP, "^(http://.*)".r),
    (SourceStorageProtocols.HTTPS, "^(https://.*)".r),
    (SourceStorageProtocols.FTP, "^(ftp://.*)".r),
    (SourceStorageProtocols.S3, "^(s3:(?>:http(?>s)?:)?//.*)".r), // ?> - indicates non-capturing group with backtracking disabled, ? indicates optional (0 or 1 occurrence)
    (SourceStorageProtocols.HDFS, "^(hdfs://.*)".r),
    (SourceStorageProtocols.LOCAL, "^(file://.*)".r)
  )

  def getProtocolOption(path: String): Option[EnumVal with Product with Serializable] = {
    ProtocolPatterns.collectFirst { case (protocol, reg) if reg.findFirstIn(path).isDefined => protocol }
  }

  def getProtocolWithDefault(path: String, default: SourceStorageProtocols.EnumVal): EnumVal = {
    ProtocolPatterns.collectFirst { case (protocol, reg) if reg.findFirstIn(path).isDefined => protocol }.getOrElse(default)
  }

  /* Protocol implementations */
  case object HTTP extends EnumVal {
    def singleFileProtocol = ???
    def downloadImpl(remotePath: String, artifactName: String, localDirectory: File, fqrv: FQRV, sslVerify: Boolean, tags : Map[String,String])
                    (implicit system: ActorSystem, blockingIODispatcher : ExecutionContext, materializer : ActorMaterializer): Unit = ???
  }

  case object HTTPS extends EnumVal {
    def singleFileProtocol = ???
    def downloadImpl(remotePath: String, artifactName: String, localDirectory: File, fqrv: FQRV, sslVerify: Boolean, tags : Map[String,String])
                    (implicit system: ActorSystem, blockingIODispatcher : ExecutionContext, materializer : ActorMaterializer): Unit = ???
  }

  case object FTP extends EnumVal {
    def singleFileProtocol = ???
    def downloadImpl(remotePath: String, artifactName: String, localDirectory: File, fqrv: FQRV, sslVerify: Boolean, tags : Map[String,String])
                    (implicit system: ActorSystem, blockingIODispatcher : ExecutionContext, materializer : ActorMaterializer): Unit = ???
  }

  case object S3 extends EnumVal {
    def singleFileProtocol = true
    def downloadImpl(remotePath: String, artifactName: String, localDirectory: File, fqrv: FQRV, sslVerify: Boolean, tags : Map[String,String])
                    (implicit system: ActorSystem, blockingIODispatcher : ExecutionContext, materializer : ActorMaterializer): Unit = {

      require(remotePath != null && !remotePath.isEmpty, "path field in the serve request cannot be null or empty" )
      require(artifactName != null && !artifactName.isEmpty, "artifact name which serves as the s3 bucket name cannot be null or empty" )

      implicit val s3DownloadTimeout: Timeout = Timeout(S3_DOWNLOAD_TIMEOUT_SECS seconds)


      def getAndSetS3Credentials(uri : URI) : AwsCredentials = {

        //the root key is per bucket per bucket-key
        //the format for the rootKey needs to be strictly followed - refer to the docs for more information
        val credentialsRootKey = s"${uri.getAuthority}${uri.getPath}_$artifactName"
          .replaceAll("""[^A-Za-z0-9]""", "_")
          .replaceAll("""_+""","_")

        val accessKeyId = Try(s3Configs.getString(s"$credentialsRootKey.$S3_ACCESS_KEY_ID_POSTFIX")) match {
          case Success(value) => value
          case _              => System.getenv(s"${credentialsRootKey}_$S3_ACCESS_KEY_ID_POSTFIX")
        }

        val secretAccessKey = Try(s3Configs.getString(s"$credentialsRootKey.$S3_SECRET_ACCESS_KEY_POSTFIX")) match {
          case Success(value) => value
          case _              => System.getenv(s"${credentialsRootKey}_$S3_SECRET_ACCESS_KEY_POSTFIX")
        }

        require(accessKeyId != null && !accessKeyId.isEmpty, "S3 storage's 'Access Key' cannot be null or empty. Refer to the docs on how to configure this information" )
        require(secretAccessKey != null && !secretAccessKey.isEmpty, "S3 storage's 'Access Key Secret' cannot be null or empty. Refer to the docs on how to configure this information" )

        AwsBasicCredentials.create(accessKeyId,secretAccessKey)
      }



      val remotePathRegex = """(s3:(?>:http(?>s)?:)?//.*)\s+bucket=([a-z0-9.-]+)\s*(?:region=(.*))?""".r

      val (uriString, bucket, regionOption) = remotePath match {
        case remotePathRegex(uri, bucket, region) =>
          logger.whenDebugEnabled(s"uri: $uri, bucket: $bucket, region: $region")
          (uri, bucket, Option(region))
        case _ => throw new Exception(s"Invalid remotePath received: [$remotePath], remotePath should follow the protocol s3_url bucket=s3-bucket-name [region=s3-region-name]")
      }

      val uri = URI.create(uriString.replaceFirst("s3::", ""))

      //the style of the bucket access is determined by the position of the bucket name
      val isBucketAccessPathStyle : Boolean = if(uri.getAuthority.contains(bucket)) false else true

      val localFilePath = Paths.get(localDirectory.getAbsolutePath, artifactName)

      val s3StaticCredentials = getAndSetS3Credentials(uri)

      val s3SettingsOverride = S3Settings(config.getConfig(S3_CONFIG_PATH))
        .withEndpointUrl(s"${uri.getScheme}://${uri.getAuthority}")
        .withPathStyleAccess(isBucketAccessPathStyle)
        .withCredentialsProvider(StaticCredentialsProvider.create(s3StaticCredentials))
        .withS3RegionProvider(new AwsRegionProvider {
          override def getRegion: Region = Region.of(regionOption.getOrElse("none"))
        })

      val s3File: Source[Option[(Source[ByteString, NotUsed], ObjectMetadata)], NotUsed] = scaladsl.S3
        .download(bucket, artifactName)
        .withAttributes(S3Attributes.settings(s3SettingsOverride))

      //an unsuccessful download operation like bucket and/or object not found would lead to removal of the file from the given local directory
      val result: Future[IOResult] = s3File
        .flatMapConcat {
          case Some((data: Source[ByteString, _], metadata)) => data
          case _ => Source.empty
        }.runWith(FileIO.toPath(localFilePath))

      val ioResult = Await.result(result, s3DownloadTimeout.duration)

      ioResult.status match {
        case Success(_) => logger.whenDebugEnabled(s"s3 download complete and successfully saved the file locally, written ${ioResult.count / (1024.0 * 1024.0)} mb")
        case Failure(error) => logger.error(s"s3 download unsuccessful with: ${error.printStackTrace()}")
      }
    }

  }

  case object HDFS extends EnumVal {
    def singleFileProtocol = ???
    def downloadImpl(remotePath: String, artifactName: String, localDirectory: File, fqrv: FQRV, sslVerify: Boolean, tags : Map[String,String])
                    (implicit system: ActorSystem, blockingIODispatcher : ExecutionContext, materializer : ActorMaterializer): Unit = ???
  }

  case object GIT extends EnumVal with SupportsFQRVExtraction {
    def singleFileProtocol = false
    private val patternFQRV = "^git@(.*):(.*)/(.*).git#v([0-9]+).(.*)".r

    def hasValidFQRV(path: String): Boolean = {
      path match {
        case patternFQRV(repo, org, project, contract, releaseVersion) => true
        case _ => false
      }
    }

    def getFQRV(path: String): Option[FQRV] = {
      path match {
        case patternFQRV(repo, org, project, contract, releaseVersion) =>
          Some(FQRV(Contract(org, project, contract.toInt), releaseVersion))
        case _ => None
      }
    }

    def downloadImpl(remotePath: String, artifactName: String, localDirectory: File, fqrv: FQRV, sslVerify: Boolean, tags : Map[String,String])
                    (implicit system: ActorSystem, blockingIODispatcher : ExecutionContext, materializer : ActorMaterializer): Unit = {
      logger.trace(s"downloadDirectoryImpl called with $remotePath $localDirectory $fqrv")

      def getRepoBase(path: String): Option[String] = {
        val sshPatternRepoBase = "^git@(.*).git(.*)".r
        val httpPatternRepoBase = "^http([s]?)://(.*).git(.*)".r
        path match {
          case sshPatternRepoBase(repoBase, _) =>
            Some(s"http://${repoBase.replace(":", "/")}.git")
          case httpPatternRepoBase(secure, repoBase, _) =>
            Some(s"http$secure://$repoBase.git")
          case _ => None
        }
      }

      def versionFromFQRV(fqrv: FQRV) = {
        s"v${fqrv.contract.contractNumber}.${fqrv.releaseVersion}"
      }

      val repo = {
        val repoOption = getRepoBase(remotePath)
        require(repoOption.isDefined, s"Supplied path is not compatible with the ${getClass.getName} protocol: $remotePath")
        repoOption.get
      }

      logger.debug(s"Cloning repo: $repo to $localDirectory and setting version to ${versionFromFQRV(fqrv)}")

      import org.eclipse.jgit.api.Git

      implicit class JGitImplicits(cloneCommand: CloneCommand) {
        def withCredentials() : CloneCommand = {
          if (!sslVerify) {
            val credentialsProvider = new CredentialsProvider {
              override def isInteractive: Boolean = false

              override def supports(credentialItems: CredentialItem*): Boolean = {
                credentialItems.exists(_.isInstanceOf[CredentialItem.YesNoType])
              }

              override def get(urIish: URIish, credentialItems: CredentialItem*): Boolean = {
                credentialItems
                  .collectFirst{ case item : CredentialItem.YesNoType => item.setValue(true); true}
                  .getOrElse(false)
              }
            }
            cloneCommand.setCredentialsProvider(credentialsProvider)
          } else {
            cloneCommand
          }
        }
      }

      logger.debug(s"Cloning $repo into $localDirectory")
      val gitRepo = Git.cloneRepository()
        .setURI(repo)
        .setDirectory(localDirectory)
        .withCredentials()
        .call()

      val tag = versionFromFQRV(fqrv)
      logger.debug(s"Checking out tag: $tag")
      gitRepo.checkout().setName(tag).call()
      gitRepo.close()
      logger.debug(s"Checkout complete for tag: $tag")
    }
  }

  case object LOCAL extends EnumVal {
    def singleFileProtocol = true
    def downloadImpl(remotePath: String, artifactName: String, localDirectory: File, fqrv: FQRV, sslVerify: Boolean, tags : Map[String,String])
                    (implicit system: ActorSystem, blockingIODispatcher : ExecutionContext, materializer : ActorMaterializer): Unit = {
      val file = Paths.get(new URI(remotePath)).toFile
      val sourceDir = file

      require(sourceDir.isDirectory, s"Supplied remote path: $remotePath : ${sourceDir.getAbsolutePath} is not a directory")

      FileUtils.copyDirectory(sourceDir, localDirectory)
    }
  }


}
