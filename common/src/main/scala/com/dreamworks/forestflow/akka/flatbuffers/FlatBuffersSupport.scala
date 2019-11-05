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
package com.dreamworks.forestflow.akka.flatbuffers

import java.nio.ByteBuffer

import akka.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import akka.http.scaladsl.model.{ContentTypeRange, MediaRange, MediaRanges, MediaType}
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import com.google.flatbuffers.Table

import scala.collection.mutable
import scala.reflect.ClassTag

// application/octet-stream
trait FlatBuffersSupport {
  var myMap : mutable.Map[ClassTag[_], AnyRef => AnyRef] = mutable.Map.empty[ClassTag[_], AnyRef => AnyRef]
  private val applicableMediaTypes: List[ContentTypeRange] = List(
    MediaRanges.`*/*`,
    MediaType.applicationBinary("octet-stream", MediaType.NotCompressible)
  )

  implicit def flatBufferBinaryUnmarshaller[T <: Table](implicit tag: ClassTag[T]): FromEntityUnmarshaller[T]  = {
    val fn = /*myMap.getOrElseUpdate(tag,*/ {
      val clazz = tag.runtimeClass
      val className = clazz.getSimpleName
      val method = clazz.getMethod(s"getRootAs$className", classOf[ByteBuffer])
      param: AnyRef => method.invoke(null, param)
        param
    }/*)*/

    Unmarshaller.byteArrayUnmarshaller.forContentTypes(applicableMediaTypes: _*).map(b => fn(java.nio.ByteBuffer.wrap(b)).asInstanceOf[T])
  }

  implicit def flatBufferBinaryMarshaller[T <: Table](implicit tag: ClassTag[T]): ToEntityMarshaller[T]  = {
    println("marshalling something")
    val result = Marshaller.ByteArrayMarshaller.compose((c: T) => c.getByteBuffer.array())
    println("done")
    result
  }
}
