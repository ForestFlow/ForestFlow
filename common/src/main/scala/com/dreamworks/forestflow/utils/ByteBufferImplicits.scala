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

import java.nio._

import scala.language.implicitConversions

object ByteBufferImplicits {
/*  implicit def safeToByteArray(b: ByteBuffer): Array[Byte] = {
    if (b.hasArray)
      b.array()
    else {
      val bArray = new Array[Byte](b.remaining())
      b.get(bArray)
      bArray
    }
  }*/


  implicit class RichBuffer(b: CharBuffer) {
    def safeArray: Array[Char] = {
      if (b.hasArray)
        b.array()
      else {
        val bArray = new Array[Char](b.remaining())
        b.get(bArray)
        bArray
      }
    }
  }


  implicit class RichFloatBuffer(b: FloatBuffer) {
    def safeArray: Array[Float] = {
      if (b.hasArray)
        b.array()
      else {
        val bArray = new Array[Float](b.remaining())
        b.get(bArray)
        bArray
      }
    }
  }


  implicit class RichDoubleBuffer(b: DoubleBuffer) {
    def safeArray: Array[Double] = {
      if (b.hasArray)
        b.array()
      else {
        val bArray = new Array[Double](b.remaining())
        b.get(bArray)
        bArray
      }
    }
  }

  implicit class RichByteBuffer(b: ByteBuffer) {
    def safeArray: Array[Byte] = {
      if (b.hasArray)
        b.array()
      else {
        val bArray = new Array[Byte](b.remaining())
        b.get(bArray)
        bArray
      }
    }
  }


  implicit class RichShortBuffer(b: ShortBuffer) {
    def safeArray: Array[Short] = {
      if (b.hasArray)
        b.array()
      else {
        val bArray = new Array[Short](b.remaining())
        b.get(bArray)
        bArray
      }
    }
  }


  implicit class RichIntBuffer(b: IntBuffer) {
    def safeArray: Array[Int] = {
      if (b.hasArray)
        b.array()
      else {
        val bArray = new Array[Int](b.remaining())
        b.get(bArray)
        bArray
      }
    }
  }


  implicit class RichLongBuffer(b: LongBuffer) {
    def safeArray: Array[Long] = {
      if (b.hasArray)
        b.array()
      else {
        val bArray = new Array[Long](b.remaining())
        b.get(bArray)
        bArray
      }
    }
  }

}
