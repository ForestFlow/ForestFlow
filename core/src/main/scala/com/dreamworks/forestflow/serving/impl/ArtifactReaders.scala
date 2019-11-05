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
package com.dreamworks.forestflow.serving.impl

import java.io.File
import java.net.URI
import java.nio.file.{Files, Paths}

import com.dreamworks.forestflow.serving.interfaces.ArtifactReader

//noinspection ScalaFileName
/***
  *
  * @param providedBasePath The providedBasePath is an option.. this is NOT the servable name.. This just means the MLmodel file has a path attribute
  */
case class LocalFileArtifactReader(providedBasePath: String) extends ArtifactReader {

  private def getFile(absoluteArtifactPath: String): File = {
    val file = new File(absoluteArtifactPath)
    require(file.exists, s"Supplied path does not exist: $absoluteArtifactPath")
    require(file.isFile, s"${this.getClass.getName.stripSuffix("$")} requires path to a single file, not a directory")
    file
  }


  override def getArtifact(artifactName: String, localDirAbsolutePath: String): Array[Byte] = {
    println(s"artifactName: $artifactName localDirAbsolutePath: $localDirAbsolutePath providedBasePath: $providedBasePath")
    val absolutePath = {
      if (providedBasePath.startsWith("file://")){
        Paths.get(Paths.get(new URI(providedBasePath)).toString, artifactName)
      }
      else
        Paths.get(localDirAbsolutePath, providedBasePath, artifactName)
    }.toString
    Files.readAllBytes(getFile(absolutePath).toPath)
  }
}

/*
case class HTTPArtifactReader (artifactPath: String) extends ArtifactReader {

  private def getFile(absoluteArtifactPath: String): File = {
    val file = new File(absoluteArtifactPath)
    require(file.exists, s"Supplied path does not exist: $absoluteArtifactPath")
    require(file.isFile, s"${this.getClass.getName.stripSuffix("$")} requires path to a single file, not a directory")
    file
  }

  def getArtifact(absoluteBasePath: String,relativeArtifactPath: String): Array[Byte] = {
    require(absoluteBasePath.nonEmpty, s"absoluteBasePath property required for ${this.getClass.getName.stripSuffix("$")}.getArtifact")
    getArtifact(Paths.get(absoluteBasePath, relativeArtifactPath).toString)
  }

  override def getArtifact(absolutePath: String): Array[Byte] = Files.readAllBytes(getFile(absolutePath).toPath)
}*/
