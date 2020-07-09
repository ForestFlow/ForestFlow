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
import java.nio.file.{Files, Paths}

import ai.forestflow.domain.{Contract, FQRV}
import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.alpakka.s3.{ObjectMetadata, S3Attributes, S3Ext, scaladsl}
import akka.stream.{ActorMaterializer, IOResult}
import akka.stream.scaladsl.{FileIO, Source}
import akka.util.{ByteString, Timeout}
import com.typesafe.scalalogging.StrictLogging
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.CloneCommand
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.{CredentialItem, CredentialsProvider, URIish}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.providers.AwsRegionProvider

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.implicitConversions
import scala.util.{Failure, Success}
import scala.concurrent.duration._


/**
  * The different protocols we can understand and support
  */
object SourceStorageProtocols extends StrictLogging {

  sealed trait EnumVal {
    protected def downloadDirectoryImpl(remotePath: String, artifactPath : Option[String], localDirectory: File, fqrv: FQRV, sslVerify: Boolean, tags : Map[String,String])
      (implicit system: ActorSystem, blockingIODispatcher : ExecutionContext, materializer : ActorMaterializer): Unit

    def downloadDirectory(remotePath: String, artifactPath : Option[String], localDirectory: File, fqrv: FQRV, sslVerify: Boolean, tags : Map[String,String])
      (implicit system: ActorSystem, blockingIODispatcher : ExecutionContext, materializer : ActorMaterializer): Unit = {
      require(localDirectory.isDirectory, s"Local file path supplied isn't a directory: ${localDirectory.getPath}")
      require(localDirectory.canWrite && localDirectory.canRead, s"Cannot ${List(if (localDirectory.canRead) "read" else "",if (localDirectory.canWrite) "write" else "").mkString(" and ")} to the local directory provided: ${localDirectory.getPath}")
      require(localDirectory.listFiles().length == 0, s"Local directory provided must be empty. Contains ${localDirectory.listFiles().length} files.")

      downloadDirectoryImpl(remotePath, artifactPath ,localDirectory, fqrv, sslVerify, tags)
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
    def downloadDirectoryImpl(remotePath: String, artifactPath : Option[String], localDirectory: File, fqrv: FQRV, sslVerify: Boolean, tags : Map[String,String])
      (implicit system: ActorSystem, blockingIODispatcher : ExecutionContext, materializer : ActorMaterializer): Unit = ???
  }

  case object HTTPS extends EnumVal {
    def downloadDirectoryImpl(remotePath: String, artifactPath : Option[String], localDirectory: File, fqrv: FQRV, sslVerify: Boolean, tags : Map[String,String])
      (implicit system: ActorSystem, blockingIODispatcher : ExecutionContext, materializer : ActorMaterializer): Unit = ???
  }

  case object FTP extends EnumVal {
    def downloadDirectoryImpl(remotePath: String, artifactPath : Option[String], localDirectory: File, fqrv: FQRV, sslVerify: Boolean, tags : Map[String,String])
      (implicit system: ActorSystem, blockingIODispatcher : ExecutionContext, materializer : ActorMaterializer): Unit = ???
  }

  case object S3 extends EnumVal {


    def downloadDirectoryImpl(remotePath: String, artifactPath : Option[String], localDirectory: File, fqrv: FQRV, sslVerify: Boolean, tags : Map[String,String])
      (implicit system: ActorSystem, blockingIODispatcher : ExecutionContext, materializer : ActorMaterializer): Unit = {

      require(artifactPath.isDefined, "artifact_path is currently undefined, this setting serves as the bucket name and needs to be defined to use s3 as source storage protocol")
      require(!artifactPath.get.matches("[a-z0-9\\.-]+"), "artifact_path setting,because of its use as a s3 bucket name, can only have  lowercase letters, numbers, dots (.), and hyphens (-)")
      implicit val s3DownloadTimeout: Timeout = Timeout(300 seconds)


      val uri = URI.create(remotePath.replaceFirst("s3::", ""))
      val regionString = tags.getOrElse("s3_region", "none") // region is either user-defined or is set to "none" as there is no sensible default for this setting
      val s3Region = Region.of(regionString)

      val bucketName = artifactPath.get

      val (bucketKey : String, isBucketAccessPathStyle : Boolean) = if(uri.getAuthority.contains(bucketName)) {
        (uri.getPath.replaceFirst("/",""), false)
      }
      else {
        (uri.getPath.replaceFirst("/" + bucketName + "/",""), true)
      }

      //appending providedBasePath (provided via artifact_path setting) to the localDirectory
      val localFilePathWithBase = Paths.get(localDirectory.getAbsolutePath, bucketName, bucketKey)
      val localFilePathWithBaseParent = localFilePathWithBase.getParent

      if(!localFilePathWithBaseParent.toFile.exists()) {
        Files.createDirectories(localFilePathWithBaseParent)
      }

      val s3Settings = S3Ext(system).settings
        .withEndpointUrl(uri.getScheme + "://" + uri.getAuthority)
        .withPathStyleAccess(isBucketAccessPathStyle)
        .withS3RegionProvider(new AwsRegionProvider {
          override def getRegion: Region = s3Region
        })

      val s3File: Source[Option[(Source[ByteString, NotUsed], ObjectMetadata)], NotUsed] = scaladsl.S3
        .download(bucketName, bucketKey)
        .withAttributes(S3Attributes.settings(s3Settings))

      //an unsuccessful download operation like bucket and/or object not found would lead to removal of the file from the given local directory
      val result: Future[IOResult] = s3File
        .flatMapConcat {
          case Some((data: Source[ByteString, _], metadata)) => data
          case _ => Source.empty
        }.runWith(FileIO.toPath(localFilePathWithBase))

      val ioResult = Await.result(result, s3DownloadTimeout.duration)

      ioResult.status match {
        case Success(_) => logger.debug(s"s3 download complete and successfully saved the file locally, written ${ioResult.count / (1024.0 * 1024.0)} mb")
        case Failure(error) => logger.error(s"s3 download unsuccessful with: ${error.printStackTrace()}")
      }
    }
  }

  case object HDFS extends EnumVal {
    def downloadDirectoryImpl(remotePath: String, artifactPath : Option[String], localDirectory: File, fqrv: FQRV, sslVerify: Boolean, tags : Map[String,String])
      (implicit system: ActorSystem, blockingIODispatcher : ExecutionContext, materializer : ActorMaterializer): Unit = ???
  }

  case object GIT extends EnumVal with SupportsFQRVExtraction {
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

    def downloadDirectoryImpl(remotePath: String, artifactPath : Option[String], localDirectory: File, fqrv: FQRV, sslVerify: Boolean, tags : Map[String,String])
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
    def downloadDirectoryImpl(remotePath: String, artifactPath : Option[String], localDirectory: File, fqrv: FQRV, sslVerify: Boolean, tags : Map[String,String])
      (implicit system: ActorSystem, blockingIODispatcher : ExecutionContext, materializer : ActorMaterializer): Unit = {
      val file = Paths.get(new URI(remotePath)).toFile
      val sourceDir = file

      require(sourceDir.isDirectory, s"Supplied remote path: $remotePath : ${sourceDir.getAbsolutePath} is not a directory")

      FileUtils.copyDirectory(sourceDir, localDirectory)
    }
  }


}
