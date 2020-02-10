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

import com.dreamworks.forestflow.domain.FQRV
import com.dreamworks.forestflow.serving.impl.ServableMetricsImpl
import com.dreamworks.forestflow.serving.interfaces.{ContractRouter, RouterType}
import com.dreamworks.forestflow.utils.RFWeightedCollection
import com.dreamworks.forestflow.utils.RFWeightedCollection.WeightedItem
import com.typesafe.scalalogging.StrictLogging

import scala.collection.mutable.ArrayBuffer


trait LatestPhaseInPctBasedRouterImpl {
  this: RouterType =>
  override def create(servableStats: List[(FQRV, ServableMetricsImpl)]): ContractRouter = LatestPhaseInPctBasedRouter.create(servableStats)
}

/** *
  * Routes 100% of traffic to the latest (last to become valid) servable.
  * If latest servable is not at 100% phase in, this acts a lot like a FairPhaseInPctBasedRouter against the latest 2
  * servables, i..e, the latest servable and the one prior to that will share traffic based on the latest Phase In %
  * until the latest Phase In % hits 100% which will then trigger the router to send 100% traffic to latest servable.
  */
object LatestPhaseInPctBasedRouter extends StrictLogging {
  def create(servableStats: List[(FQRV, ServableMetricsImpl)]): ContractRouter = {
    logger.debug(s"Creating LatestPhaseInPctBasedRouter with $servableStats")
    LatestPhaseInPctBasedRouter(servableStats)
  }

  def getFilteredServableStats(servableStats: List[(FQRV, ServableMetricsImpl)]): List[(FQRV, ServableMetricsImpl)] = {
    if (servableStats.length < 2){
      servableStats
    } else {
      val latest2 = servableStats
        .sortWith { (a, b) =>
          a._2.becameValidAtMS.get > a._2.becameValidAtMS.get ||
            (a._2.becameValidAtMS.get == a._2.becameValidAtMS.get && a._2.createdAtMS > b._2.createdAtMS)
        }.take(2)

      if (latest2.head._2.currentPhaseInPct >= 100)
        List(latest2.head) // only routes to latest
      else
        latest2 // fair pct-based allocation between last 2
    }
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
  def apply(servableStats: List[(FQRV, ServableMetricsImpl)]): LatestPhaseInPctBasedRouter = {
    logger.debug(s"Creating RFWeightedCollection from $servableStats")
    LatestPhaseInPctBasedRouter(RFWeightedCollection(getWeightedItems(getFilteredServableStats(servableStats)))())
  }
}

@SerialVersionUID(2965108754223283566L)
final case class LatestPhaseInPctBasedRouter(private val collection: RFWeightedCollection[FQRV]) extends ContractRouter {
  import LatestPhaseInPctBasedRouter._

  override def next(): Option[FQRV] = collection.next().map(_.item)

  override def merge(servableStats: List[(FQRV, ServableMetricsImpl)]): ContractRouter = {
    // If only weights are being updated to existing collection list
    val filteredServables = getFilteredServableStats(servableStats)
    if (collection.items.map(_.item).toSet == filteredServables.map(_._1).toSet) {
      LatestPhaseInPctBasedRouter(collection.updateWeights(getWeightedItems(filteredServables)))
    }
    else // new or deleted items, create anew.
      LatestPhaseInPctBasedRouter(filteredServables)
  }
}
