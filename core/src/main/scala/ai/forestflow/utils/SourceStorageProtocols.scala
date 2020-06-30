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
import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.alpakka.s3.{ObjectMetadata, S3Attributes, S3Ext, S3Settings, scaladsl}
import akka.stream.{ActorMaterializer, IOResult}
import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString
import com.typesafe.scalalogging.StrictLogging
import org.apache.commons.io.FileUtils
import org.eclipse.jgit.api.CloneCommand
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.{CredentialItem, CredentialsProvider, URIish}

import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions
import scala.util.{Failure, Success}

/**
  * The different protocols we can understand and support
  */
object SourceStorageProtocols extends StrictLogging {

  sealed trait EnumVal {
    protected def downloadDirectoryImpl(remotePath: String, localDirectory: File, fqrv: FQRV, sslVerify: Boolean): Unit

    def downloadDirectory(remotePath: String, localDirectory: File, fqrv: FQRV, sslVerify: Boolean): Unit = {
      require(localDirectory.isDirectory, s"Local file path supplied isn't a directory: ${localDirectory.getPath}")
      require(localDirectory.canWrite && localDirectory.canRead, s"Cannot ${List(if (localDirectory.canRead) "read" else "",if (localDirectory.canWrite) "write" else "").mkString(" and ")} to the local directory provided: ${localDirectory.getPath}")
      require(localDirectory.listFiles().length == 0, s"Local directory provided must be empty. Contains ${localDirectory.listFiles().length} files.")

      downloadDirectoryImpl(remotePath, localDirectory, fqrv, sslVerify)
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
    (SourceStorageProtocols.S3, "^(s3://.*)".r),
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
    def downloadDirectoryImpl(remotePath: String, localDirectory: File, fqrv: FQRV, sslVerify: Boolean): Unit = ???
  }

  case object HTTPS extends EnumVal {
    def downloadDirectoryImpl(remotePath: String, localDirectory: File, fqrv: FQRV, sslVerify: Boolean): Unit = ???
  }

  case object FTP extends EnumVal {
    def downloadDirectoryImpl(remotePath: String, localDirectory: File, fqrv: FQRV, sslVerify: Boolean): Unit = ???
  }

  case object S3 extends EnumVal {

    private implicit val actorSystem: ActorSystem = ActorSystem("Application-s3-stream")
    private implicit val executionContext: ExecutionContext = actorSystem.dispatcher
    private implicit val materializer: ActorMaterializer = ActorMaterializer()

    private val s3PathStylePathPattern = "/(.*)/(.*)".r

    def downloadDirectoryImpl(remotePath: String, localDirectory: File, fqrv: FQRV, sslVerify: Boolean): Unit = {

      val uri = URI.create(remotePath)

      val (bucket, bucketKey) = uri.getPath match {
        case s3PathStylePathPattern(bucket, bucketKey) => (bucket, bucketKey)
        case _ => throw new IllegalArgumentException(s"Invalid url $remotePath, bucket and/or key name information couldn't be extracted from the url's path")
      }

      val localFilePath = Paths.get(localDirectory.getAbsolutePath, bucketKey)

      val s3Settings = S3Ext(actorSystem).settings
        .withEndpointUrl(uri.getScheme + "://" + uri.getAuthority)
        .withPathStyleAccess(true) //this is stop gap solution until we get access to virtual hosting of buckets

      val s3File: Source[Option[(Source[ByteString, NotUsed], ObjectMetadata)], NotUsed] = scaladsl.S3
        .download(bucket, bucketKey)
        .withAttributes(S3Attributes.settings(s3Settings))

      //an unsuccessful download operation like bucket and/or object not found would lead to removal of the file from the given local directory
      val result: Future[IOResult] = s3File
        .flatMapConcat {
          case Some((data: Source[ByteString, _], metadata)) => data
          case _ => Source.empty
        }.runWith(FileIO.toPath(localFilePath))

      result onComplete {
        case Success(value) => logger.debug(s"s3 download complete and successfully saved the file locally, written ${value.count / (1024.0 * 1024.0)} mb")
        case Failure(error) => logger.error(s"s3 download unsuccessful with: ${error.printStackTrace()}")
      }
    }
  }

  case object HDFS extends EnumVal {
    def downloadDirectoryImpl(remotePath: String, localDirectory: File, fqrv: FQRV, sslVerify: Boolean): Unit = ???
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

    def downloadDirectoryImpl(remotePath: String, localDirectory: File, fqrv: FQRV, sslVerify: Boolean): Unit = {
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
    def downloadDirectoryImpl(remotePath: String, localDirectory: File, fqrv: FQRV, sslVerify: Boolean): Unit = {
      val file = Paths.get(new URI(remotePath)).toFile
      val sourceDir = file

      require(sourceDir.isDirectory, s"Supplied remote path: $remotePath : ${sourceDir.getAbsolutePath} is not a directory")

      FileUtils.copyDirectory(sourceDir, localDirectory)
    }
  }


}
