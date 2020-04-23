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
package ai.forestflow.scalapb

import akka.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import akka.http.scaladsl.model.{ContentTypeRange, MediaRanges, MediaType}
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import scalapb.GeneratedSealedOneof

import scala.reflect.ClassTag

// application/octet-stream
trait SealedOneofSupport {
  private val applicableMediaTypes: List[ContentTypeRange] = List(
    MediaRanges.`*/*`,
    MediaType.applicationBinary("octet-stream", MediaType.NotCompressible)
  )

  implicit def GeneratedSealedOneofBinaryUnmarshaller[T <: GeneratedSealedOneof]: FromEntityUnmarshaller[GeneratedSealedOneof]  = {
    Unmarshaller.byteArrayUnmarshaller.forContentTypes(applicableMediaTypes: _*).map(b =>  b.asInstanceOf[GeneratedSealedOneof].asMessage.companion.parseFrom(b).asInstanceOf[T])
  }

  implicit def GeneratedSealedOneofBinaryMarshaller[T <: GeneratedSealedOneof](implicit tag: ClassTag[T]): ToEntityMarshaller[T]  = {
    val result = Marshaller.ByteArrayMarshaller.compose((c: T) => c.asMessage.toByteArray)
    result
  }
}
