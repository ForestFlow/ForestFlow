/**
 * Copyright 2019 DreamWorks Animation L.L.C.
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
package com.dreamworks.forestflow.utils

import java.io.File
import java.net.URI
import java.nio.file.Paths

import com.dreamworks.forestflow.domain.{Contract, FQRV}
import com.typesafe.scalalogging.StrictLogging
import org.apache.commons.io.FileUtils

/**
  * The different protocols we can understand and support
  */
object SourceStorageProtocols extends StrictLogging {

  sealed trait EnumVal {
    protected def downloadDirectoryImpl(remotePath: String, localDirectory: File, fqrv: FQRV): Unit

    def downloadDirectory(remotePath: String, localDirectory: File, fqrv: FQRV): Unit = {
      require(localDirectory.isDirectory, s"Local file path supplied isn't a directory: ${localDirectory.getPath}")
      require(localDirectory.canWrite && localDirectory.canRead, s"Cannot ${List(if (localDirectory.canRead) "read" else "",if (localDirectory.canWrite) "write" else "").mkString(" and ")} to the local directory provided: ${localDirectory.getPath}")
      require(localDirectory.listFiles().length == 0, s"Local directory provided must be empty. Contains ${localDirectory.listFiles().length} files.")

      downloadDirectoryImpl(remotePath, localDirectory, fqrv)
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
    def downloadDirectoryImpl(remotePath: String, localDirectory: File, fqrv: FQRV): Unit = ???
  }

  case object HTTPS extends EnumVal {
    def downloadDirectoryImpl(remotePath: String, localDirectory: File, fqrv: FQRV): Unit = ???
  }

  case object FTP extends EnumVal {
    def downloadDirectoryImpl(remotePath: String, localDirectory: File, fqrv: FQRV): Unit = ???
  }

  case object S3 extends EnumVal {
    def downloadDirectoryImpl(remotePath: String, localDirectory: File, fqrv: FQRV): Unit = ???
  }

  case object HDFS extends EnumVal {
    def downloadDirectoryImpl(remotePath: String, localDirectory: File, fqrv: FQRV): Unit = ???
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

    def downloadDirectoryImpl(remotePath: String, localDirectory: File, fqrv: FQRV): Unit = {
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
      /*        val sshSessionFactory = new JschConfigSessionFactory() {
              override def configure(host: OpenSshConfig.Host, session: Session): Unit = {
                session.setConfig("StrictHostKeyChecking", "no")
              }
            }*/

      val gitCommand: Git = Git.cloneRepository
        .setURI(repo)
        /*          .setTransportConfigCallback((transport: Transport) => {
                    val sshTransport = transport.asInstanceOf[SshTransport]
                    sshTransport.setSshSessionFactory(sshSessionFactory)
                  })*/
        .setDirectory(localDirectory)
        .call()

      val tag = versionFromFQRV(fqrv)
      logger.debug(s"Checking out tag: $tag")
      gitCommand.checkout().setName(tag).call()
      logger.debug(s"Checkout complete for tag: $tag")

    }
  }

  case object LOCAL extends EnumVal {
    def downloadDirectoryImpl(remotePath: String, localDirectory: File, fqrv: FQRV): Unit = {
      val file = Paths.get(new URI(remotePath)).toFile
      val sourceDir = file

      require(sourceDir.isDirectory, s"Supplied remote path: $remotePath : ${sourceDir.getAbsolutePath} is not a directory")

      FileUtils.copyDirectory(sourceDir, localDirectory)
    }
  }


}
