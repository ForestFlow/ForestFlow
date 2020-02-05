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
package worksheets

object Definitions {

  object TensorType {

    sealed trait EnumVal

    case object Boolean extends EnumVal

    case object Char extends EnumVal

    case object Byte extends EnumVal

    case object Short extends EnumVal

    case object Int extends EnumVal

    case object Long extends EnumVal

    case object Float extends EnumVal

    case object Double extends EnumVal

    case object Unit extends EnumVal


  }

  /**
    * Server required to respond to 'Request types.
    * Can be either 'InferRequest or 'MetadataRequest
    */
  trait Request {} // request
  //  case class Request(req: Request)
  case class Error(code: Long, message: String)

  /**
    *
    * @param rows Think of rows as an input pixel in an image. Columns as features for each pixel
    * @param columns
    */
  case class Shape(rows: Long, columns: Long)

  /**
    *
    * @param tensorType The type of each element of the tensor.
    * @param shape      Array of int64 values representing the size of each dimension of the tensor
    * @param data       The raw data of the tensor in row-major order
    * @param string_val An array of raw byte tensors (only used for the String type
    */
  case class Tensor(tensorType: TensorType.EnumVal, shape: Shape, data: Array[Byte], string_val: Array[String])

  /*
  shape: Shape(5, 4)
  [4.8 3.1 1.6 0.2,    7.7 3.8 6.7 2.2,   7.7 3.  6.1 2.3,   4.6 3.1 1.5 0.2,  5.  3.5 1.6 0.6]

  Shape(1, 1)

   */

  /**
    *
    * @param config        Config data for the model server
    * @param input_names   List of strings representing inputs
    * @param input_tensors List of input tensors
    * @param output_names  List of strings representing outputs
    */
  case class InferTensorRequest(config: String, input_names: Option[Array[String]], input_tensors: Array[Tensor], output_names: Option[Array[String]]) extends Request


  case class InferDatumRequest(config: String, columns: Array[String], input_tensors: Array[Tensor], output_names: Option[Array[String]]) extends Request


  case class MetadataRequest() extends Request

  /**
    * The InferResponse contains one output tensor per requested output name
    * (or may return one or more default output tensors), or an error.
    * If no input names are included, the server may apply the input tensors to default inputs
    *
    * @param output_tensors The type of each element of the tensor.
    * @param errors         Array of Error messages
    */
  case class InferResponse(output_tensors: Array[Tensor], errors: Array[Error])

  /**
    *
    * @param name        Name of the model being served
    * @param version     Version of the model server
    * @param server      Name of the model server
    * @param description Description of the model being served
    * @param inputs      Array of IOMetadata about the inputs
    * @param outputs     Array of IOMetadata (defined below) about the outputs
    */
  case class MetadataResponse(name: Option[String], version: Option[String], server: Option[String], description: Option[String], inputs: Array[IOMetadata], outputs: Array[IOMetadata])

  /**
    * IOMetadata contains information about a given input or output.
    * The name is the identifier for this particular input or output.
    * This name is used when specifying input_names and output_names as part of an InferRequest.
    * A negative one (-1) in the shape means the model accepts an arbitrary size input in that dimension.
    * This is generally useful if the model supports a batch dimension.
    *
    * @param name        Name of the input or output
    * @param description Description of the input or output
    * @param shape       Shape of the input or output (-1 represents any size)
    * @param IOType      Type of the input or output
    */
  case class IOMetadata(name: String, description: Option[String], shape: Array[Long], IOType: String)

}
