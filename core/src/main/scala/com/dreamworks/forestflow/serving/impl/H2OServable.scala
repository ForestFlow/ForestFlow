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
package com.dreamworks.forestflow.serving.impl

import java.nio.{ByteBuffer, ByteOrder}

import com.dreamworks.forestflow.domain.{BasicIOMetadata, BasicMetaData, Datum, FQRV, Float32Data, Float64Data, InferenceRequest, Int32Data, Prediction, ServableSettings, StringData, TensorSchema, TensorType, Tensor => BasicTensor}
import com.dreamworks.forestflow.serving.interfaces.{HasBasicSupport, HasGraphPipeSupport, Servable, TensorData}
import com.google.flatbuffers.FlatBufferBuilder
import graphpipe.{IOMetadata, InferRequest, MetadataResponse, Tensor => GPTensor, Type => GPType}
import hex.genmodel.MojoModel
import hex.genmodel.easy.{EasyPredictModelWrapper, RowData}
import com.dreamworks.forestflow.utils.ThrowableImplicits._
import com.dreamworks.forestflow.utils.GraphPipe._
import hex.ModelCategory

import scala.collection.JavaConverters._
import scala.util.Try


case class H2OServable(
  mojo: MojoModel,
  override val fqrv: FQRV,
  override var settings: ServableSettings) extends Servable with HasBasicSupport with HasGraphPipeSupport {

  private val modelCategory = mojo.getModelCategory
  private val easyModel = new EasyPredictModelWrapper(new EasyPredictModelWrapper.Config()
    .setModel(mojo)
    .setConvertUnknownCategoricalLevelsToNa(true)
    .setConvertInvalidNumbersToNa(true)
  )

  val server = "ForestFlow" // TODO move to constants
  private val (categorical, numeric) = {
    val (cat, num) = mojo
      .getNames.filter(_ != mojo.getResponseName)
      .zip(mojo.getDomainValues)
      .map {
        case (name: String, domainValues: Array[String]) => (name, true)
        case (name: String, null) => (name, false)
      }.zipWithIndex
      .sortWith { case (((_, a_categorical), a_origPos), ((_, b_categorical), b_origPos)) =>
        (a_categorical && !b_categorical) || (a_categorical == b_categorical && a_origPos < b_origPos)
      }.toList
      .partition(_._1._2)

    (cat.map(_._1._1), num.map(_._1._1))
  }

  private val allFeatures = categorical ++ numeric
  private val numInputs = (if (categorical.nonEmpty) 1 else 0) + (if (numeric.nonEmpty) 1 else 0)
  require(numInputs >= 1, "Model with no inputs. H2O model must have some features")
  implicit private val fbb: FlatBufferBuilder = new FlatBufferBuilder(1024)

  private lazy val FQRVTensor = {
    val str = fqrv.toString
    val stringVal = fbb.createString(str)

    val strings = GPTensor.createStringValVector(fbb, Array(stringVal))
    val shape = GPTensor.createShapeVector(fbb, Array(1L, 1L))

    GPTensor.startTensor(fbb)
    GPTensor.addStringVal(fbb, strings)
    GPTensor.addType(fbb, GPType.String)
    GPTensor.addShape(fbb, shape)
    GPTensor.endTensor(fbb)
  }

  lazy val getMetaData: BasicMetaData = {

    def getFeatureDescription(inputFeatures: List[String]) = {
      inputFeatures
        .zipWithIndex.map { case (name, pos: Int) => s"$pos:$name" }.mkString("[", ",", "]")
    }

    BasicMetaData(
      fqrv,
      Some("prediction"),
      Some(server),
      Some(settings.toString),
      {if (categorical.nonEmpty) List(
        BasicIOMetadata(
          "categorical",
          Some(getFeatureDescription(categorical)),
          List(categorical.length.toLong), // categorical.indices.map(_ => 1L).toList,
          TensorType.String
        )
      ) else List.empty} ++ {if (numeric.nonEmpty) List(BasicIOMetadata(
        "numeric",
        Some(getFeatureDescription(numeric)),
        List(numeric.length.toLong), // numeric.indices.map(_ => 1L).toList,
        TensorType.Float64
      )) else List.empty}
    )
  }

  def score(inferenceRequest: InferenceRequest): Try[Prediction] = {

    def getPrediction(rows: IndexedSeq[RowData]): Prediction = {
      modelCategory match {
        case cat @ ModelCategory.Regression =>
          val predictions = rows
            .map(easyModel.predictRegression(_).value)

          Prediction(
            fqrv,
            Seq(TensorSchema(TensorType.Float64, Seq(cat.toString))),
            predictions.map(p => Datum(Seq(Float64Data(Seq(p)))))
          )

        case cat @ ModelCategory.Clustering =>
          val predictions = rows
            .map(easyModel.predictClustering(_).cluster)

          Prediction(
            fqrv,
            Seq(TensorSchema(TensorType.Int32, Seq(cat.toString))),
            predictions.map(p => Datum(Seq(Int32Data(Seq(p)))))
          )

        case cat @ ModelCategory.AutoEncoder =>
          val predictions = rows
            .map(easyModel.predictAutoEncoder(_).reconstructed)

          Prediction(
            fqrv,
            Seq(TensorSchema(TensorType.Float64, Seq(cat.toString))),
            predictions.map(p => Datum(Seq(Float64Data(p))))
          )

        case cat @ ModelCategory.Binomial =>
          val predictions = rows
            .map(easyModel.predictBinomial(_).label)

          Prediction(
            fqrv,
            Seq(TensorSchema(TensorType.String, Seq(cat.toString))),
            predictions.map(p => Datum(Seq(StringData(Seq(p)))))
          )

        case cat @ ModelCategory.Multinomial =>
          val predictions = rows
            .map(easyModel.predictMultinomial(_).label)

          Prediction(
            fqrv,
            Seq(TensorSchema(TensorType.String, Seq(cat.toString))),
            predictions.map(p => Datum(Seq(StringData(Seq(p)))))
          )

        case cat @ ModelCategory.DimReduction =>
          val predictions = rows
            .map(easyModel.predictDimReduction(_).dimensions)

          Prediction(
            fqrv,
            Seq(TensorSchema(TensorType.String, Seq(cat.toString))),
            predictions.map(p => Datum(Seq(Float64Data(p))))
          )

        case cat @ ModelCategory.AnomalyDetection =>
          val predictions = rows
            .map{ row =>
              val anomDetPrediction = easyModel.predictAnomalyDetection(row)
              (anomDetPrediction.score, anomDetPrediction.normalizedScore)
            }

          Prediction(
            fqrv,
            Seq(TensorSchema(TensorType.Float64, Seq("score", "normalizedScore"))),
            predictions.map(p => Datum(Seq(Float64Data(List(p._1, p._2)))))
          )

        case cat =>
          throw new UnsupportedOperationException(s"H2O model category $cat is not supported.")

        /***
          * TODO Can't do WordEmbeddings yet until we have named Tensor support for input and output data. Specifically output data
      case cat @ ModelCategory.WordEmbedding =>
        val predictions = rows
          .map{ row =>
            val word2VeCPrediction = easyModel.predictWord2Vec(row)
            word2VeCPrediction.wordEmbeddings
          }

        predictions.map{p =>
          val words = p.keySet().iterator().asScala.toSeq
          val tensorSchema = words.map{word =>

          }
        }

        Prediction(
          fqrv,
          Seq(
            TensorSchema(TensorType.String, Seq("words")),
            TensorSchema(TensorType.Float64, Seq("embeddings"))
          ),
          predictions.map(p => Datum(
            Seq(
              StringData(p.keySet().iterator().asScala.toSeq),
              Float32Data(p.values().iterator().asScala.flatMap(_.flatten(f => Some(f))).toList)
          ))
        )
          */

      }
    }

    def SeqTStoMap(stf: Seq[TensorSchema]): Map[TensorType, Seq[String]] = {
      stf.map{ts =>
        ts.`type` -> ts.fields
      }.toMap
    }

    val inferResponse = {
      for {
        dataCheck <- Try(require(inferenceRequest.datum.nonEmpty && inferenceRequest.datum.head.tensors.nonEmpty, "No input data provided"))

        expectedNumInputs <- Try {
          case class DatumStats(minInputs: Int, maxInputs: Int)
          val stats = inferenceRequest.datum.foldLeft(DatumStats(inferenceRequest.datum.head.tensors.length, inferenceRequest.datum.head.tensors.length)) {
            (stats, d) =>
              DatumStats({
                if (d.tensors.length < stats.minInputs) d.tensors.length else stats.minInputs
              }, {
                if (d.tensors.length > stats.minInputs) d.tensors.length else stats.maxInputs
              })
          }
          //          inferenceRequest.datum.forall(datum => datum.dataRecord.length == numInputs
          require(stats.minInputs == stats.maxInputs && stats.minInputs == numInputs,
          s"Number of inputs (Found min: ${stats.minInputs} max: ${stats.maxInputs}) does not match expected number for a request $numInputs"
          )
        }

        predictionResponse <- Try {
          val numRecords = inferenceRequest.datum.length
          val inputData = inferenceRequest.datum.map(_.tensors)

          val dataRows = (0 until numRecords).map { recordIndex =>

            val row = new RowData()
            val datum = inputData(recordIndex)

            val schemaTypeMap = SeqTStoMap(inferenceRequest.schema)

            datum.foreach { tensor =>
              val td = tensor.asInstanceOf[TensorData[_]]
              // TODO ensure this is checked (i.e., feature types and number of features)
              schemaTypeMap.get(td.getTensorType).foreach{features =>
                row.putAll(
                  features
                    .zip(td.data.asInstanceOf[Seq[AnyRef]])
                    .toMap
                    .asJava
                )
              }
            }

            row
          }

          getPrediction(dataRows)
        }
      } yield predictionResponse
    }

    inferResponse
  }


  /***
    * The output from an H2OServable always contains 2 tensors
    * Tensors[0] is always of type String and shape [1, 1] containing a single item that is the FQRV of the model that
    * served the request.
    *
    * Tensor[1] will have predictions and is of shape [#of predictions, 1] where number of predictions is the number
    * of records corresponding to the number of inputs in a batch request
    */
  lazy val getGPMetaData: Array[Byte] = {
    /**
      * @return Returns IOMetadata flatbuffers index and a the list of features including their positional index (Maybe put this somewhere else)
      */
    def createIOMetadata(inputName: String, gpType: Int, inputFeatures: List[String], featureLength: Long) = {
      val input = fbb.createString(inputName)
      val featuresDescription = inputFeatures.zipWithIndex.map { case (name, pos: Int) =>
        s"$pos:$name"
      }.mkString("[", ",", "]")
      val description = fbb.createString(featuresDescription)
      val shape = IOMetadata.createShapeVector(fbb, inputFeatures.indices.map(_ => featureLength).toArray)

      IOMetadata.startIOMetadata(fbb)
      IOMetadata.addName(fbb, input)
      IOMetadata.addDescription(fbb, description)
      IOMetadata.addShape(fbb, shape)
      IOMetadata.addType(fbb, gpType)
      IOMetadata.endIOMetadata(fbb)
    }

    val categoricalFeatures = if (categorical.isEmpty)
      None
    else
      Some(createIOMetadata("categorical", GPType.String, categorical, 1L))

    val numericFeatures = if (numeric.isEmpty)
      None
    else
      Some(createIOMetadata("numeric", GPType.Float64, numeric, 1L))

    val inputFeatures = Array(
      categoricalFeatures.map(List(_)),
      numericFeatures.map(List(_))
    ).flatten
      .reduceOption(_ ++ _)
      .map { inputs =>
        MetadataResponse.createInputsVector(fbb, inputs.toArray)
      }

    val serverFB = fbb.createString(server)
    val version = fbb.createString(fqrv.toString)
    val description = fbb.createString(settings.toString)
    val responseName = fbb.createString("Prediction")

    MetadataResponse.startMetadataResponse(fbb)
    MetadataResponse.addName(fbb, responseName)
    MetadataResponse.addVersion(fbb, version)
    MetadataResponse.addServer(fbb, serverFB)
    MetadataResponse.addDescription(fbb, description)
    inputFeatures.foreach(MetadataResponse.addInputs(fbb, _))
    //    MetadataResponse.addOutputs(fbb, inputs)
    val meta = MetadataResponse.endMetadataResponse(fbb)
    fbb.finish(meta)

    val responseBuf: Array[Byte] = fbb.sizedByteArray()

    responseBuf
  }

  override def scoreGP(infer: InferRequest): Array[Byte] = {

    val inputNames = (0 until infer.inputNamesLength()).map(infer.inputNames)
    val inputTensors = (0 until infer.inputTensorsLength()).map(infer.inputTensors)
    def createPredictionTensor(data: Int, shape: Int, dataType: Int): Int = {
      GPTensor.startTensor(fbb)
      GPTensor.addData(fbb, data)
      GPTensor.addType(fbb, dataType)
      GPTensor.addShape(fbb, shape)
      GPTensor.endTensor(fbb)
    }


    val inferResponse = {
      for {
        namesLength <- Try(
          require(
            if (infer.inputNamesLength() > 0) infer.inputNamesLength() == infer.inputTensorsLength() else true,
            s"Provided input names length ${infer.inputNamesLength()} doesn't match input tensors length ${infer.inputTensorsLength()}"
          ))

        tensorCheck <- Try(require(inputTensors.nonEmpty, "No input tensors provided"))

        expectedNumInputs <- Try(
          require(
            numInputs == infer.inputTensorsLength(),
            s"Number of input tensors ${infer.inputTensorsLength()} does not match expected number for a request $numInputs"))

        tensorShape <- Try(
          require(
            inputTensors.forall(t => t.shapeLength() > 1 && t.shape(0) == inputTensors(0).shape(0) || t.shape(0) == -1),
            s"Found input Tensor with shape length for shape(0) that does not match expected record count from tensor(0).shape(0) ${inputTensors(0).shape(0)} or -1"
          ))

        recordCount <- Try(
          // Assumption: All input tensors' first shape record represents the number of records and they all match for H2O
          require(inputTensors(0).shape(0) <= Int.MaxValue, s"Cannot process more than ${Int.MaxValue} records at once")
        )

        numRecords <- Try(inputTensors(0).shape(0).toInt)



        predictionResponse <- Try {
          val inputDataTensors = inputTensors.flatMap { t =>
            if (t.`type`() == GPType.String) {
              val stringRecords = (0 until t.stringValLength()).map(t.stringVal)
                .zipWithIndex
                .map(wIndex => (wIndex._2 / (t.stringValLength / numRecords), wIndex._1)) // get a row number
                .groupBy(_._1).map(v => (v._1, categorical.zip(v._2.unzip._2))) // group by row number
              stringRecords
            }
            else {
              val nativeTypes = getNativeType(t.dataAsByteBuffer(), t.`type`())
              val numericRecords = nativeTypes.toIndexedSeq
                .zipWithIndex
                .map(wIndex => (wIndex._2 / (nativeTypes.length / numRecords), wIndex._1)) // get a row number
                .groupBy(_._1).map(v => (v._1, numeric.zip(v._2.unzip._2)))
              numericRecords
            }
          }

          // need to combine tensors of different types into a single row to score against
          val inputData = {
            inputDataTensors
              .groupBy(_._1) // group by row number
              .map( dr => (dr._1, dr._2.unzip._2.flatten)) // flatten items in the group and discard row number
              .toIndexedSeq
              .sortBy(_._1) // need to maintain order of input
          }

          val dataRows = (0 until numRecords).map { recordIndex =>
            val row = new RowData()
            val datum = inputData(recordIndex)._2

            datum.foreach { case (key: String, value: AnyRef) =>
              row.put(key, value.toString)
            }
            row
          }

          val predictionTensors: IndexedSeq[Int] = modelCategory match {
            case cat@ModelCategory.Regression =>
              val dataType = GPType.Float64
              val predictions = dataRows
                .map(easyModel.predictRegression(_).value)

              val (bb, put) = getTypedByteBuffer(predictions.length, dataType)
              predictions.foreach(put)

              val shape = GPTensor.createShapeVector(fbb, Array(predictions.length.toLong, 1L))
              val data = GPTensor.createDataVector(fbb, bb.array())

              IndexedSeq(createPredictionTensor(data, shape, dataType))

            case cat@ModelCategory.Clustering =>
              val dataType = GPType.Int32
              val predictions = dataRows
                .map(easyModel.predictClustering(_).cluster)

              val (bb, put) = getTypedByteBuffer(predictions.length, dataType)
              predictions.foreach(put)

              val shape = GPTensor.createShapeVector(fbb, Array(predictions.length.toLong, 1L))
              val data = GPTensor.createDataVector(fbb, bb.array())

              IndexedSeq(createPredictionTensor(data, shape, dataType))

            case cat @ ModelCategory.AutoEncoder =>
              val dataType = GPType.Float64
              dataRows
                .map{ p =>
                  val prediction = easyModel.predictAutoEncoder(p).reconstructed
                  val (bb, put) = getTypedByteBuffer(prediction.length, dataType)
                  prediction.foreach(put)
                  val shape = GPTensor.createShapeVector(fbb, Array(1L, prediction.length.toLong))
                  val data = GPTensor.createDataVector(fbb, bb.array())
                  createPredictionTensor(data, shape, dataType)
                }

            case cat@ModelCategory.Binomial =>
              val dataType = GPType.String
              val predictions = dataRows
                .map{ p =>
                  fbb.createString(easyModel.predictBinomial(p).label)
                }

              val shape = GPTensor.createShapeVector(fbb, Array(predictions.length.toLong, 1L))
              val data = GPTensor.createStringValVector(fbb, predictions.toArray)

              IndexedSeq(createPredictionTensor(data, shape, dataType))

            case cat@ModelCategory.Multinomial =>
              val dataType = GPType.String
              val predictions = dataRows
                .map{ p =>
                  fbb.createString(easyModel.predictMultinomial(p).label)
                }

              val shape = GPTensor.createShapeVector(fbb, Array(predictions.length.toLong, 1L))
              val data = GPTensor.createStringValVector(fbb, predictions.toArray)

              IndexedSeq(createPredictionTensor(data, shape, dataType))

            case cat @ ModelCategory.DimReduction =>
              val dataType = GPType.Float64
              dataRows
                .map{ p =>
                  val prediction = easyModel.predictDimReduction(p).dimensions
                  val (bb, put) = getTypedByteBuffer(prediction.length, dataType)
                  prediction.foreach(put)
                  val shape = GPTensor.createShapeVector(fbb, Array(1L, prediction.length.toLong))
                  val data = GPTensor.createDataVector(fbb, bb.array())
                  createPredictionTensor(data, shape, dataType)
                }

            case cat@ModelCategory.AnomalyDetection =>
              val dataType = GPType.Float64
              val predictions = dataRows
                .map{ p =>
                  val anomDetPrediction = easyModel.predictAnomalyDetection(p)
                  (anomDetPrediction.score, anomDetPrediction.normalizedScore)
                }

              val (bb, put) = getTypedByteBuffer(predictions.length, dataType)
              predictions.foreach{
                case (score, normalizedScore) =>
                  put(score)
                  put(normalizedScore)
              }

              val shape = GPTensor.createShapeVector(fbb, Array(predictions.length.toLong, 2L))
              val data = GPTensor.createDataVector(fbb, bb.array())

              IndexedSeq(createPredictionTensor(data, shape, dataType))
          }


          val tensors = Array(FQRVTensor) ++ predictionTensors
          val inferResponse = createInferResponse(tensors)
          inferResponse
        }
      } yield predictionResponse
    } recover {
      case e: Throwable =>
        createErrorInferResponse(Array(e.printableStackTrace))
    }

    inferResponse.get
  }
}
