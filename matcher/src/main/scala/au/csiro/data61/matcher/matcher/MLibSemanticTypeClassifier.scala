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

package au.csiro.data61.matcher.matcher

import java.io.{File, PrintWriter}

import au.csiro.data61.matcher.data._
import au.csiro.data61.matcher.matcher.features._
import au.csiro.data61.matcher.matcher.train.TrainAliases._
import org.apache.spark.sql._
import org.apache.spark.sql.types._
import org.apache.spark.ml.PipelineModel
import org.apache.spark.ml.linalg.{DenseVector => NewDenseVector}
import org.apache.spark.ml.feature.StringIndexerModel
import com.typesafe.scalalogging.LazyLogging

import scala.languageFeature.implicitConversions


case class MLibSemanticTypeClassifier(
        classes: List[String],
        model: PipelineModel,
        featureExtractors: List[FeatureExtractor],
        postProcessingConfig: Option[Map[String,Any]] = None,
        derivedFeaturesPath: Option[String] = None)
  extends SemanticTypeClassifier with LazyLogging {

  /**
    * Setting up Spark per each training and testing session.
    * We should move it outside and just indicate the port where spark is running.
    * Too much needs to be configured...
    * @return
    */
  def setUpSpark(): SparkSession = {
    //initialise spark stuff
    val sparkSession = SparkSession.builder
      .master("local")
      .appName("SereneSchemaMatcher")
      .getOrCreate()
    sparkSession.conf.set("spark.executor.cores","8")
    sparkSession
  }

  /**
    * Helper function to calculate predictions for a spark dataframe using a trained model.
    * @param dataDf Spark DataFrame
    * @param spark Implicit SparkSession
    * @return Correctly reordered predicitons with regard to the specified classes
    */
  protected def processPredictions(dataDf: DataFrame)(implicit spark: SparkSession): Array[Array[Double]] ={
    // perform prediction
    val preds = model.transform(dataDf)

    // for debugging, you might want to look at these columns: "rawPrediction","probability","prediction","predictedLabel"
    val predsLocal : Array[Array[Double]] = preds
      .select("probability")
      .rdd
      .map {
        x => x(0).asInstanceOf[NewDenseVector].toArray // in new spark DenseVector changed!
      }
      .collect

    logger.info("***Reordering elements.")
    // we need to reorder the elements of the probability distribution array according to 'classes' and not mlib
    // val mlibLabels = model.getEstimator.asInstanceOf[Pipeline].getStages(0).asInstanceOf[StringIndexerModel].labels
    val mlibLabels : Array[String] = model.stages(0).asInstanceOf[StringIndexerModel].labels
    logger.info(s"***Available mlibLabels: ${mlibLabels.toList}")
    val newOrder : List[Int] = classes.map(mlibLabels.indexOf(_))
    val predsReordered: Array[Array[Double]] = predsLocal
      .map{
        probDist => {
          // 'classes.length' was used previously
          // the problem is that classes with 0 score do not appear in probDist
          classes.indices.map {
            i =>
              if (newOrder(i) >= 0) { probDist(newOrder(i)) }
              else { 0.0 } // probability for this class is missing, so set it to 0
            //probDist(newOrder(i))
          }.toArray
        }
      }

    predsReordered
  }

  /**
    * Helper function to construct Spark DataFrame, then perform prediction for this dataframe using the trained
    * model and perform reordering of the predicitons in accordance with the order of the classes.
    * @param allAttributes List of attributes extracted from the data sources.
    * @param spark Implicit SparkSession
    * @return Triplet with the predictions, list of extracted features, list of feature names.
    */
  protected def constructDF(allAttributes: List[Attribute]
                 )(implicit spark: SparkSession): (Array[Array[Double]], List[List[Double]], List[String]) = {

    //get feature names and construct schema
    val featureNames = featureExtractors.flatMap({
      case x: SingleFeatureExtractor => List(x.getFeatureName())
      case x: GroupFeatureExtractor => x.getFeatureNames()
    })
    val schema = StructType(
      featureNames
        .map { n => StructField(n, DoubleType, false) }
    )

    // extract features
    val features: List[List[Double]] = FeatureExtractorUtil
      .extractFeatures(allAttributes, featureExtractors)
    logger.info(s"   extracted ${features.size} features")

    val data = features
      .map { instFeatures => Row.fromSeq(instFeatures) }
    val dataRdd = spark.sparkContext.parallelize(data)
    val dataDf = spark.createDataFrame(dataRdd, schema)
    val predsReordered = processPredictions(dataDf)

    spark.stop()

    (predsReordered, features, featureNames)
  }

  override def predict(datasets: List[DataModel]): PredictionObject = {
    logger.info("***Prediction initialization...")
    //initialise spark stuff
    implicit val spark = setUpSpark()
    // get all attributes (aka columns) from the datasets
    val allAttributes: List[Attribute] = datasets
      .flatMap { DataModel.getAllAttributes }
    logger.info(s"   obtained ${allAttributes.size} attributes")

    val (predsReordered, features, featureNames) = constructDF(allAttributes)

    val predictions: Predictions = allAttributes zip predsReordered // this was returned previously by this function
    // get the class with the max score per each attribute
    val maxClassPreds: List[(String,String,Double)] = predictions
      .map { case (attr, scores) =>
        val maxIdx = scores.zipWithIndex.maxBy(_._1)._2
        val maxScore = scores(maxIdx)
        val classPred = classes(maxIdx)
        (attr.id, classPred, maxScore)
    }.toList

    // we want to return an array of (Attribute, predictedClassScores, derivedFeatures)
    val predictionsFeatures: PredictionObject = predictions
      .zip(features)
      .map{ case ((a, sc), f) => (a, sc, f)}

    // we save features to file here
    derivedFeaturesPath match {
        case Some(filePath) => saveFeatures(predictionsFeatures, featureNames, maxClassPreds, filePath)
        case None =>
    }

    logger.info("***Prediction finished.")
    predictionsFeatures
    //predictions // this was returned previously
  }

  /**
    * Save derived features to the file together with the maximum confidence score.
    * @param attributes List of attributes from the data sources for which prediction is performed.
    * @param featuresList List of derived features.
    * @param featureNames List of feature names.
    * @param maxClassPreds Predicted labels with maximum confidence.
    * @param path Path where to save the derived features.
    */
  def saveFeatures(attributes: List[Attribute],
                   featuresList: List[List[Any]],
                   featureNames: List[String],
                   maxClassPreds: List[(String,String,Double)], // include manual/predicted labels
                   path: String): Unit = {
    logger.info("***Writing derived features to " + path)
    val out = new PrintWriter(new File(path))

    val header = ("id,label,confidence" :: featureNames).mkString(",")
    out.println(header)

    //write features
    (attributes zip featuresList).foreach {
      case (attr, features) =>
        val id = attr.id
        val classPred = maxClassPreds
          .filter(elem => elem._1 == id).head
        out.println((id :: classPred._2 :: classPred._3 :: features).mkString(","))
    }

    out.close()
  }

  /**
    * Save derived features to the file together with confidence scores for all classes.
    * @param predictionsFeatures PredictionObject calculated for the data sources.
    * @param featureNames List of feature names.
    * @param maxClassPreds Predicted labels with maximum confidence.
    * @param path Path where to save the derived features.
    */
  def saveFeatures(predictionsFeatures: PredictionObject,
                   featureNames: List[String],
                   maxClassPreds: List[(String,String,Double)], // include manual/predicted labels
                   path: String): Unit = {
    logger.info("***Writing derived features to " + path)
    val out = new PrintWriter(new File(path))

    val header = ("id,label,confidence" :: classes ::: featureNames).mkString(",")
    out.println(header)

    //write features
    predictionsFeatures.foreach({
      case (attr, scores, features) =>
        val id = attr.id
        val classPred = maxClassPreds
          .filter(elem => elem._1 == id).head
        out.println((id :: classPred._2 :: classPred._3 :: scores.toList ::: features).mkString(","))
    })

    out.close()
  }
}