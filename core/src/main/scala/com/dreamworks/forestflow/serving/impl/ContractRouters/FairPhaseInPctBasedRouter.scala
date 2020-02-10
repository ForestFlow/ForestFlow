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
package com.dreamworks.forestflow.serving.impl.ContractRouters

import com.dreamworks.forestflow.serving.interfaces.{ContractRouter, RouterType}
import com.dreamworks.forestflow.utils.RFWeightedCollection.WeightedItem
import com.dreamworks.forestflow.domain.FQRV
import com.dreamworks.forestflow.serving.impl.ServableMetricsImpl
import com.dreamworks.forestflow.utils.RFWeightedCollection
import com.typesafe.scalalogging.StrictLogging

import scala.collection.mutable.ArrayBuffer


trait FairPhaseInPctBasedRouterImpl {
  this: RouterType =>
  override def create(servableStats: List[(FQRV, ServableMetricsImpl)]): ContractRouter = FairPhaseInPctBasedRouter.create(servableStats)
}

/** *
  * Factory pattern allows us to specify which router type we want at request time and then
  * instantiate the ContractRouter later providing additional runtime information
  */
object FairPhaseInPctBasedRouter extends StrictLogging {
  def create(servableStats: List[(FQRV, ServableMetricsImpl)]): ContractRouter = {
    logger.debug(s"Creating FairPhaseInPctBasedRouter with $servableStats")
    FairPhaseInPctBasedRouter(servableStats)
  }

  def getWeightedItems(servableStats: List[(FQRV, ServableMetricsImpl)]): ArrayBuffer[WeightedItem[FQRV]] = {
    servableStats.map { case (fqrv, stats) => WeightedItem(stats.currentPhaseInPct, fqrv) }.to[ArrayBuffer]
  }

  /** *
    * We don't consider the case where we're only updating weights for current items. Every time we re-evaluate,
    * Traffic gets distributed until it settles again
    *
    * This probably makes sense because you don't want a new item to try and "Catch up" to historical load of a
    * previous item until it all balances out again
    */
  def apply(servableStats: List[(FQRV, ServableMetricsImpl)]): FairPhaseInPctBasedRouter = {
    logger.debug(s"Creating RFWeightedCollection from $servableStats")
    FairPhaseInPctBasedRouter(RFWeightedCollection(getWeightedItems(servableStats))())
  }
}

@SerialVersionUID(0L)
final case class FairPhaseInPctBasedRouter(private val collection: RFWeightedCollection[FQRV]) extends ContractRouter {
  import FairPhaseInPctBasedRouter._

  override def next(): Option[FQRV] = collection.next().map(_.item)

  override def merge(servableStats: List[(FQRV, ServableMetricsImpl)]): ContractRouter = {
    // If only weights are being updated to existing collection list
    if (collection.items.map(_.item).toSet == servableStats.map(_._1).toSet) {
      FairPhaseInPctBasedRouter(collection.updateWeights(getWeightedItems(servableStats)))
    }
    else // new or deleted items, create anew.
      FairPhaseInPctBasedRouter(servableStats)
  }
}

