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
package au.csiro.data61.modeler

import java.nio.file.Paths

import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging

/**
  * This object loads in the configuration .conf
  * file and parses the values into fields.
  */
object ModelerConfig extends LazyLogging {

  private val conf = ConfigFactory.load()

  // directory where temporary Karma stuff resides...
  val KarmaDir = conf.getString("config.karma-dir")

  // default folder to store alignment graph
  val DefaultAlignmenDir = Paths.get(ModelerConfig.KarmaDir, "alignment-graph/").toString

  logger.debug(s"Karma storage dir $KarmaDir")
}
