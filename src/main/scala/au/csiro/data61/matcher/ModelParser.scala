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

import com.nicta.dataint.data.DataModel
import com.nicta.dataint.ingestion.loader.SemanticTypeLabelsLoader
import com.nicta.dataint.matcher.features.FeatureSettings
import com.nicta.dataint.matcher.train.TrainMlibSemanticTypeClassifier

import types._


case class ModelRequest(description: Option[String],
                        modelType: Option[ModelType],
                        labels: List[String],
                        features: Option[List[Feature]],
                        training: Option[KFold],
                        costMatrix: Option[List[List[Double]]],
                        resamplingStrategy: Option[SamplingStrategy])

object ModelParser {

//  parse with regard to Modelype
//  conversion to dataint types will occur in the training step

//  parsing step
//  val labelsLoader = SemanticTypeLabelsLoader()
//  val labels = labelsLoader.load(appConfig.labelsPath)
//  val datasets = servicesConfig.dataSetRepository.getDataModels(appConfig.rawDataPath)
//  val featuresConfig = FeatureSettings.load(appConfig.featuresConfigPath, appConfig.repoPath)

// training step!
//  val trainSettings = TrainingSettings(resamplingStrategy, featuresConfig, costMatrixConfigOption)
//  val trainingData = new DataModel("", None, None, Some(datasets))
//  val trainer = new TrainMlibSemanticTypeClassifier(classes, false)
//  val randomForestSchemaMatcher = trainer.train(trainingData, labels, trainSettings, postProcessingConfig)

}
