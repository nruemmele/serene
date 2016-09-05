/**
 * Copyright (C) 2015-2016 Data61, Commonwealth Scientific and Industrial Research Organisation (CSIRO).
 * See the LICENCE.txt file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package au.csiro.data61.matcher

import au.csiro.data61.matcher.storage.{DatasetStorage, ModelStorage}
import types.ColumnTypes.ColumnID
import types.ModelTypes.{Model, ModelID, Status, TrainState}
import types._
import DataSetTypes._
import au.csiro.data61.matcher.api._
import java.nio.file.Path

import com.github.tototoshi.csv.CSVReader
import com.typesafe.scalalogging.LazyLogging
import org.joda.time.DateTime

import scala.util.{Failure, Random, Success, Try}
import scala.language.postfixOps
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * IntegrationAPI defines the interface through which requests
 * can access the underlying system. The responsibilities are
 * to parse the requests and translate into instructions for the
 * system. The return values of the functions should be simple
 * types for the web layer to translate into JSON - this includes
 * case classes as well as numbers, strings, simple maps,
 * simple arrays.
 *
 * Errors can be thrown here and they will be translated into
 * server errors or bad request errors.
 */
object MatcherInterface extends LazyLogging {

  val MissingValue = "unknown"

  val DefaultSampleSize = 15

  val BlankRequest = ModelRequest(
    description = None,
    modelType = None,
    classes = None,
    features = None,
    costMatrix = None,
    labelData = None,
    resamplingStrategy= None
  )

  /**
    * DataReference object used for passing back information
    * on how the user's label data corresponds with existing
    * dataset references.
    *
    * @param validColumns The valid columnIDs
    * @param invalidColumns The columnIDs that do not appear in the dataset
    * @param refDataSets
    * @param cleanLabels
    */
  private case class DataRef(validColumns: Set[Int],
                             invalidColumns: Set[Int],
                             refDataSets: Set[Int],
                             cleanLabels: Map[Int, String])

  /**
    * Takes the labelData field from the modelRequest and determines
    * which keys are valid and which datasets are referred to. This
    * is used for updating and creating Models.
    *
    * @param labelData The labels used for creating model requests.
    * @return
    */
  private def validKeys(labelData: Option[Map[Int, String]]) : DataRef = {

    val labelMap = labelData.getOrElse(Map.empty[Int, String])

    val colMap = DatasetStorage.columnMap

    val (keysIn, keysOut) = labelMap.keySet.partition(colMap.keySet.contains)

    DataRef(
      keysIn,
      keysOut,
      colMap.filterKeys(keysIn).values.map(_.datasetID).toSet,
      labelMap.filterKeys(keysIn)
    )
  }

  /**
    * createModel builds a new Model object from a ModelRequest
    *
    * @param request
    * @return
    */
  def createModel(request: ModelRequest): Model = {

    val id = genID
    val dataRef = validKeys(request.labelData)

    // build the model from the request, adding defaults where necessary
    val modelOpt = for {
      colMap <- Some(DatasetStorage.columnMap)

      // build up the model request, and use defaults if not present...
      model <- Try {
        Model(
          id = id,
          description = request.description.getOrElse(MissingValue),
          modelType = request.modelType.getOrElse(ModelType.RANDOM_FOREST),
          classes = request.classes.getOrElse(List()),
          features = request.features.getOrElse(FeaturesConfig(Set.empty[String], Set.empty[String], Map.empty[String, Map[String, String]])),
          costMatrix = request.costMatrix.getOrElse(List()),
          resamplingStrategy = request.resamplingStrategy.getOrElse(SamplingStrategy.RESAMPLE_TO_MEAN),
          labelData = dataRef.cleanLabels,
          refDataSets = dataRef.refDataSets.toList,
          state = TrainState(Status.UNTRAINED, "", DateTime.now, DateTime.now),
          dateCreated = DateTime.now,
          dateModified = DateTime.now)
      }.toOption
      _ <- ModelStorage.add(id, model)

    } yield {
      if (dataRef.invalidColumns.nonEmpty) {
        logger.warn(s"Following column keys do not exist: ${dataRef.invalidColumns.mkString(",")}")
      }
      model
    }
    modelOpt getOrElse { throw InternalException("Failed to create resource.") }
  }


  /**
    * Parses a model request to construct a model object
    * for updating. The index is searched for in the database,
    * and if update is successful, returns the case class response
    * object.
    *
    * @param request POST request with model information
    * @return Case class object for JSON conversion
    */
  def updateModel(id: ModelID, request: ModelRequest): Model = {

    val labelsUpdated = request.labelData.isDefined

    val modelOpt = for {
      old <- ModelStorage.get(id)
      dataRef = validKeys(request.labelData)
      // build up the model request, and use existing if field is not present...
      updatedModel <- Try {
        Model(
          id = id,
          description = request.description.getOrElse(old.description),
          modelType = request.modelType.getOrElse(old.modelType),
          classes = request.classes.getOrElse(old.classes),
          features = request.features.getOrElse(old.features),
          costMatrix = request.costMatrix.getOrElse(old.costMatrix),
          resamplingStrategy = request.resamplingStrategy.getOrElse(old.resamplingStrategy),
          labelData = if (labelsUpdated) dataRef.cleanLabels else old.labelData,
          refDataSets = if (labelsUpdated) dataRef.refDataSets.toList else old.refDataSets,
          state = TrainState(Status.UNTRAINED, "", old.state.dateCreated, DateTime.now),
          dateCreated = old.dateCreated,
          dateModified = DateTime.now)
      }.toOption
      _ <- ModelStorage.add(id, updatedModel)

    } yield {
      if (dataRef.invalidColumns.nonEmpty) {
        logger.warn(s"Following column keys do not exist: ${dataRef.invalidColumns.mkString(",")}")
      }
      updatedModel
    }
    modelOpt getOrElse { throw InternalException("Failed to update resource.") }
  }

  /**
   * Returns the public facing model from the storage layer
   *
   * @param id The model id
   * @return
   */
  def getModel(id: ModelID): Option[Model] = {
    ModelStorage.get(id)
  }

  /**
   * Trains the model
   *
   * @param id The model id
   * @return
   */
  def trainModel(id: ModelID): Option[TrainState] = {
    val state = ModelStorage.get(id).map(_.state)
    val status = state.map(_.status)

    if (ModelStorage.isConsistent(id)) {
      logger.info(s"Model $id does not need training, it is done!")
      state // instead of launching training we return the current model state
    }
    else {
      // crude concurrency
      status.flatMap {

        case Status.BUSY =>
          // if it is complete or pending, just return the value
          logger.info("Returning cached state")
          state

        case Status.COMPLETE | Status.UNTRAINED | Status.ERROR => {
          // in the background we launch the training...
          logger.info("Launching training.....")
          // first we set the model state to training....
          val s = ModelStorage.updateTrainState(id, Status.BUSY)
          launchTraining(id)
          s
        }
      }
    }
  }

  /**
    * Asynchronously launch the training process, and write
    * to storage once complete. The actual state will be
    * returned from the above case when re-read from the
    * storage layer.
    *
    * @param id Model key for the model to be launched
    */
  private def launchTraining(id: ModelID)(implicit ec: ExecutionContext): Unit = {

    Future {
      // proceed with training...
      ModelTrainer.train(id).map {
        ModelStorage.writeModel(id, _)
      }
    } onComplete {
      case Success(Some(true)) =>
        // we update the status, the state date and do not delete the model.rf file
        ModelStorage.updateTrainState(id, Status.COMPLETE, deleteRF = false)
      case Success(Some(false)) =>
        // we update the status, the state date and delete the model.rf file
        logger.error(s"Failed to write trained model for $id.")
        ModelStorage.updateTrainState(id, Status.ERROR, s"Failed to write trained model.")
      case Success(None) =>
        // we update the status, the state date and delete the model.rf file
        logger.error(s"Failed to identify model paths for $id.")
        ModelStorage.updateTrainState(id, Status.ERROR, s"Failed to identify model paths.")
      case Failure(err) =>
        // we update the status, the state date and delete the model.rf file
        val msg = s"Failed to train model $id: ${err.getMessage}."
        logger.error(msg)
        ModelStorage.updateTrainState(id, Status.ERROR, msg)
    }
  }

  /**
    * Perform prediction using the model
    *
    * @param id The model id
    * @param datasetID Optional id of the dataset
    * @return
    */
  def predictModel(id: ModelID, datasetID : DataSetID): DataSetPrediction = { //List[ColumnPrediction] = {
    if (ModelStorage.isConsistent(id)) {
      // do prediction
      logger.info(s"Launching prediction for model $id...")

      ModelPredictor.predict(id, datasetID)

      //// crude concurrency
      //launchPrediction(id, datasetID)
      //// first we set the model state to busy, learnt model should not be deleted
      //ModelStorage.updateTrainState(id, Status.BUSY, deleteRF = false, changeDate = false)
      //true // prediction has been started
      //Some(ModelPredictor.getDatasetPrediction(id, datasetID))
    } else {
      val msg = s"Prediction failed. Model $id is not trained."
      // prediction is impossible since the model has not been trained properly
      logger.warn(msg)
      throw BadRequestException(msg)
    }
  }

  /**
    * Asynchronously launch the prediction process, and write
    * to storage once complete.
    *
    * @param id Model key for the model to be used for prediction
    * @param datasetID Optional id of the dataset
    */
//  private def launchPrediction(id: ModelID, datasetID : Option[DataSetID] = None)(implicit ec: ExecutionContext): Unit = {
//    Future {
//      // proceed with prediction...
//      ModelPredictor.predict(id, datasetID)
//
//    } onComplete {
//      case Success(_) =>
//        // we do not delete model.rf file and do not change dateModified, but we change the status
//        ModelStorage.updateTrainState(id, Status.COMPLETE, deleteRF = false, changeDate = false)
//      case Failure(err) =>
//        // we do not delete model.rf file and do not change dateModified, but we change the status
//        val msg = s"Failed to perform prediction: ${err.getMessage}"
//        logger.error(msg)
//        ModelStorage.updateTrainState(id, Status.COMPLETE, msg, deleteRF = false, changeDate = false)
//    }
//  }

  /**
    * Obtain predicted classes and derived features for the datasets in the repository
    * using the specified model.
    * This method does not raise errors if predictions are not available.
    * It returns an empty list.
    *
    * @param id Model key for the model which provides predictions
    * @param datasetID Optional id of the dataset
    * @return List of predicted classes with corresponding confidence measures and derived features
    *         per each column in the dataset repository (provided predictions are available).
    */
//  def getPrediction(id: ModelID, datasetID: DataSetID) : List[ColumnPrediction] = {
//
//
//    ModelPredictor.predict(id, datasetID)
//    ModelPredictor.getDatasetPrediction(id, List(datasetID))
////    datasetID match {
////
////      case Some(dsID) =>
////        logger.info(s"Getting predicitons for the dataset $dsID.")
////        ModelPredictor.getDatasetPrediction(id, List(dsID))
////
////      case None =>
////        logger.info(s"Getting predicitons for all available datasets.")
////        ModelPredictor.getDatasetPrediction(id, DatasetStorage.keys)
////    }
//  }

  /**
   * Parses a servlet request to get a dataset object
   * then adds to the database, and returns the case class response
   * object.
   *
   * @param request Servlet POST request
   * @return Case class object for JSON conversion
   */
  def createDataset(request: DataSetRequest): DataSet = {

    if (request.file.isEmpty) {
      throw ParseException(s"Failed to read file request part")
    }

    val typeMap = request.typeMap getOrElse Map.empty[String, String]
    val description = request.description getOrElse MissingValue
    val id = genID

    val dataSet = for {
      fs <- request.file
      path <- DatasetStorage.addFile(id, fs)
      ds <- Try(DataSet(
              id = id,
              columns = getColumns(path, id, typeMap),
              filename = fs.name,
              path = path,
              typeMap = typeMap,
              description = description,
              dateCreated = DateTime.now,
              dateModified = DateTime.now
            )).toOption
      _ <- DatasetStorage.add(id, ds)
    } yield ds

    dataSet getOrElse { throw InternalException(s"Failed to create resource $id") }
  }

  def datasetKeys: List[DataSetID] = {
    DatasetStorage.keys
  }

  def modelKeys: List[ModelID] = {
    ModelStorage.keys
  }

  /**
   * Returns the public facing dataset from the storage layer
   *
   * @param id The dataset id
   * @return
   */
  def getDataSet(id: DataSetID, colSize: Option[Int]): Option[DataSet] = {
    if (colSize.isEmpty) {
      DatasetStorage.get(id)
    } else {
      DatasetStorage.get(id).map(ds =>
        ds.copy(columns = getColumns(ds.path, ds.id, ds.typeMap, colSize.get))
      )
    }
  }


  /**
   * Updates a single dataset with id key. Note that only the typemap
   * and description can be updated
   *
   * @param description Optional description for update
   * @param typeMap Optional typeMap for update
   * @param key ID corresponding to a dataset element
   * @return
   */
  def updateDataset(key: DataSetID, description: Option[String], typeMap: Option[TypeMap]): DataSet = {

    if (!DatasetStorage.keys.contains(key)) {
      throw ParseException(s"Dataset $key does not exist")
    }

    val newDS = for {
      oldDS <- Try {
        DatasetStorage.get(key).get
      }
      ds <- Try {
        oldDS.copy(
          description = description getOrElse oldDS.description,
          typeMap = typeMap getOrElse oldDS.typeMap,
          columns = if (typeMap.isEmpty) oldDS.columns else getColumns(oldDS.path, oldDS.id, typeMap.get),
          dateModified = DateTime.now
        )
      }
      id <- Try {
        DatasetStorage.update(key, ds).get
      } recover {
        case _ =>
          InternalException("Could not create database")
      }
    } yield ds

    newDS match {
      case Success(ds) =>
        ds
      case Failure(err) =>
        logger.error(s"Failed in UpdateDataSet for key $key")
        logger.error(err.getMessage)
        throw InternalException(err.getMessage)
    }
  }

  /**
   * Deletes the data set
   *
   * @param key Key for the dataset
   * @return
   */
  def deleteDataset(key: DataSetID): Option[DataSetID] = {

    for {
      ds <- DatasetStorage.get(key)
      badColumns = ds.columns.map(_.id)
      // now we need to make sure the model doesn't refer to this
      // either. The columns must also be removed from the model.
      updatedModels = ModelStorage.listValues
        .filter(_.refDataSets.contains(key))
        .map { case model =>

          val newLabels = model.labelData.filterKeys(!badColumns.contains(_))

          updateModel(
            model.id,
            BlankRequest.copy(labelData = Some(newLabels))
          )
        }
      id <- DatasetStorage.remove(key)
    } yield id

  }

  /**
   * Deletes the model
   *
   * @param key Key for the model
   * @return
   */
  def deleteModel(key: ModelID): Option[ModelID] = {
    ModelStorage.remove(key)
  }
  /**
   * Return some random column objects for a dataset
   *
   * @param filePath Full path to the file
   * @param dataSetID ID of the parent dataset
   * @param n Number of samples in the sample set
   * @param headerLines Number of header lines in the file
   * @return A list of Column objects
   */
  protected def getColumns(filePath: Path,
                           dataSetID: DataSetID,
                           typeMap: TypeMap,
                           n: Int = DefaultSampleSize,
                           headerLines: Int = 1): List[Column[Any]] = {
    // TODO: Get this out of memory!
    val csv = CSVReader.open(filePath.toFile)
    val columns = csv.all.transpose
    val headers = columns.map(_.take(headerLines).mkString("_"))
    val data = columns.map(_.drop(headerLines))
    val size = columns.headOption.map(_.size).getOrElse(0)

    // generate random samples...
    val rnd = new scala.util.Random(0)

    // we create a set of random indices that will be consistent across the
    // columns in the dataset.
    val indices = Array.fill(n)(rnd.nextInt(size))

    // now we recombine with the headers and an index to create the
    // set of column objects...
    (headers zip data).zipWithIndex.map { case ((header, col), i) =>

      val logicalType = typeMap.get(header).flatMap(LogicalType.lookup)
      val typedData = retypeData(col, logicalType)

      Column[Any](
        i,
        filePath,
        header,
        genID,
        col.size,
        dataSetID,
        indices.map(typedData(_)).toList,
        logicalType getOrElse LogicalType.STRING)
    }
  }

  /**
   * Changes the type of the csv data, very crude at the moment
   *
   * @param data The original csv data
   * @param logicalType The optional logical type. It will be cast to string if none.
   * @return
   */
  protected def retypeData(data: List[String], logicalType: Option[LogicalType]): List[Any] = {
    logicalType match {

      case Some(LogicalType.BOOLEAN) =>
        data.map(_.toBoolean)

      case Some(LogicalType.FLOAT) =>
        data.map(s => Try(s.toDouble).toOption getOrElse Double.NaN)

      case Some(LogicalType.INTEGER) =>
        data.map(s => Try(s.toInt).toOption getOrElse Int.MinValue)

      case Some(LogicalType.STRING) =>
        data

      case _ =>
        data
    }
  }

  /**
   * Generate a random positive integer id
   *
   * @return Returns a random positive integer
   */
  protected def genID: Int = Random.nextInt(Integer.MAX_VALUE)

}
