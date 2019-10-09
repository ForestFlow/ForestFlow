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

import java.nio.{ByteBuffer, ByteOrder}

import com.dreamworks.forestflow.serving.interfaces.HasHTTPStatusCode
import com.google.flatbuffers.FlatBufferBuilder
import com.dreamworks.forestflow.utils.ByteBufferImplicits._
import graphpipe.{Error, InferResponse, Type}
import scalapb.GeneratedSealedOneof

object GraphPipe {
  def getNativeType(bbuffer: ByteBuffer, dataType: Int) = {
    dataType match {
      case Type.Float16 => bbuffer.asCharBuffer().safeArray.map(_.asInstanceOf[AnyRef])
      case Type.Float32 => bbuffer.asFloatBuffer().safeArray.map(_.asInstanceOf[AnyRef])
      case Type.Float64 => bbuffer.asDoubleBuffer().safeArray.map(_.asInstanceOf[AnyRef])
      case Type.Int8 => bbuffer.safeArray.map(_.asInstanceOf[AnyRef])
      case Type.Int16 => bbuffer.asShortBuffer().safeArray.map(_.asInstanceOf[AnyRef])
      case Type.Int32 => bbuffer.asIntBuffer().safeArray.map(_.asInstanceOf[AnyRef])
      case Type.Int64 => bbuffer.asLongBuffer().safeArray.map(_.asInstanceOf[AnyRef])
      case Type.Uint8 => bbuffer.safeArray.map(_.asInstanceOf[AnyRef])
      case Type.Uint16 => bbuffer.asShortBuffer().safeArray.map(_.asInstanceOf[AnyRef])
      case Type.Uint32 => bbuffer.asIntBuffer().safeArray.map(_.asInstanceOf[AnyRef])
      case Type.Uint64 => bbuffer.asLongBuffer().safeArray.map(_.asInstanceOf[AnyRef])
    }
  }

  def getTypedByteBuffer(rows: Int, dataType: Int): (ByteBuffer, Any => ByteBuffer) = {
    dataType match {
      case Type.Float16 | Type.Float32 =>
        val bb = ByteBuffer.allocate(rows * 4).order(ByteOrder.LITTLE_ENDIAN)
        (bb, (data: Any) => bb.putFloat(data.asInstanceOf[Float]))
      case Type.Float64 =>
        val bb = ByteBuffer.allocate(rows * 8).order(ByteOrder.LITTLE_ENDIAN)
        (bb, (data: Any) => bb.putDouble(data.asInstanceOf[Double]))
      case Type.Uint8 | Type.Int8 =>
        val bb = ByteBuffer.allocate(rows * 1).order(ByteOrder.LITTLE_ENDIAN)
        (bb, (data: Any) => bb.putChar(data.asInstanceOf[Char]))
      case Type.Uint16 | Type.Int16 =>
        val bb = ByteBuffer.allocate(rows * 2).order(ByteOrder.LITTLE_ENDIAN)
        (bb, (data: Any) => bb.putShort(data.asInstanceOf[Short]))
      case Type.Uint32 | Type.Int32 =>
        val bb = ByteBuffer.allocate(rows * 4).order(ByteOrder.LITTLE_ENDIAN)
        (bb, (data: Any) => bb.putInt(data.asInstanceOf[Int]))
      case Type.Uint64 | Type.Int64 =>
        val bb = ByteBuffer.allocate(rows * 8).order(ByteOrder.LITTLE_ENDIAN)
        (bb, (data: Any) => bb.putLong(data.asInstanceOf[Long]))
    }
  }

  def createErrorInferResponse(errors: Array[String])(implicit fbb: FlatBufferBuilder): Array[Byte] = {
    val e = errors.map { eMessage =>
      val eIndex = fbb.createString(eMessage)
      Error.startError(fbb)
      Error.addMessage(fbb, eIndex)
      Error.endError(fbb)
    }
    val errorsVector = InferResponse.createErrorsVector(fbb, e)
    createInferResponseWithErrorsVector(errorsVector)
  }

  def createErrorInferResponse(error: Any)(implicit fbb: FlatBufferBuilder): Array[Byte] = {

    val eMsgIndex = fbb.createString(error.toString)
    Error.startError(fbb)
    Error.addMessage(fbb, eMsgIndex)
    error match {
      case httpCode: GeneratedSealedOneof with HasHTTPStatusCode =>
        Error.addCode(fbb, httpCode.getStatusCode.toLong)
      case _ => // NOOP
    }

    val e = Error.endError(fbb)

    val errorsVector = InferResponse.createErrorsVector(fbb, Array(e))
    createInferResponseWithErrorsVector(errorsVector)
  }

  private def createInferResponseWithErrorsVector(eVector: Int)(implicit fbb: FlatBufferBuilder) = {
    InferResponse.startInferResponse(fbb)
    InferResponse.addErrors(fbb, eVector)
    val inferResponse = InferResponse.endInferResponse(fbb)
    fbb.finish(inferResponse)

    val responseBuf: Array[Byte] = fbb.sizedByteArray()
    responseBuf
  }

  def createInferResponse(tensors: Array[Int])(implicit fbb: FlatBufferBuilder): Array[Byte] = {
    val outputTensors = InferResponse.createOutputTensorsVector(fbb, tensors)

    InferResponse.startInferResponse(fbb)
    InferResponse.addOutputTensors(fbb, outputTensors)

    val inferResponse = InferResponse.endInferResponse(fbb)
    fbb.finish(inferResponse)

    val responseBuf: Array[Byte] = fbb.sizedByteArray()
    responseBuf
  }


}
