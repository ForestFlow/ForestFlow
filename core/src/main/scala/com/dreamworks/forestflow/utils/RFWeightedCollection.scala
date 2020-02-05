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
package com.dreamworks.forestflow.utils

import com.dreamworks.forestflow.utils.RFWeightedCollection.WeightedItem

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

object RFWeightedCollection {

  case class WeightedItem[I](
    weight: Long,
    item: I,
    final var served: Long = 0) /*(createdAt: CreatedTimeNS = System.nanoTime())*/
    extends Ordered[WeightedItem[I]] {

    override def compare(that: WeightedItem[I]): Int = {
      if (this.item == that.item)
        0
      else if (this.weight > that.weight)
        1
      else
        -1
    }
  }

}
/***
  * Read Fast Weighted Collection
  * @param items
  * @param nextItemIndex
  * @param elementsServed
  * @tparam A
  */
case class RFWeightedCollection[A](items: mutable.ArrayBuffer[WeightedItem[A]] = mutable.ArrayBuffer.empty[WeightedItem[A]])(
  private var nextItemIndex: Int = 0,
  private var elementsServed: Long = 0L
) {

  private val totalWeight = items.map(_.weight).sum

  private val totalItems = items.size

  type T = RFWeightedCollection[A]


  /**
    * Calls updateWeights
    *
    * @param newItems
    * @return
    */
  def +(updates: ArrayBuffer[WeightedItem[A]]): RFWeightedCollection[A] = updateWeights(updates)

  /**
    * Returns new instance of RFWeightedCollected based on existing list and updated weights from new items
    * This cannot be used to add new items. Only update existing ones. new items that do not match existing items
    * will simply be discarded and they will have no effect.
    *
    * @param newItems
    * @return Returns new instance of RFWeightedCollected based on new item list and serve history for matched items
    */
  def updateWeights(updates: ArrayBuffer[WeightedItem[A]]): RFWeightedCollection[A] = {
    val newItemMap = updates.map(wi => (wi.item, wi)).toMap
    copy(
      items = items.map(wi => {
        newItemMap.get(wi.item) match {
          case Some(nItem) => wi.copy(weight = nItem.weight)
          case None => wi
        }
      }).sortBy(_.weight))(
      nextItemIndex = 0,
      elementsServed = this.elementsServed
    )
  }


  def next(): Option[WeightedItem[A]] = {
    if (totalItems <= 0)
      return None

    if (elementsServed == Long.MaxValue)
      elementsServed = 0
    else
      elementsServed += 1

    val item = items(nextItemIndex)
    item.served += 1 // TODO We currently don't protect against overflow here!

    items.update(nextItemIndex, item)

    if (item.served / elementsServed.toDouble > item.weight / totalWeight.toDouble)
      nextItemIndex = (nextItemIndex + 1) % totalItems

    Some(item)
  }

}
