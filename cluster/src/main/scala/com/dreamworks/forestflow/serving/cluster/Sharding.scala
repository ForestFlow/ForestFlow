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
package com.dreamworks.forestflow.serving.cluster

import akka.cluster.sharding.ShardRegion
import com.dreamworks.forestflow.serving.interfaces.Protocol.Command

object Sharding {

  case object Shutdown

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case command: Command => (command.id, command)
  }

  val extractShardId: Int => ShardRegion.ExtractShardId= (maximumNumberOfShards: Int) => {

    def computeShardId(id: ShardRegion.EntityId): ShardRegion.ShardId = (math.abs(id.hashCode) % maximumNumberOfShards).toString

    {
      case command: Command => computeShardId(command.id)
      case ShardRegion.StartEntity(id) => computeShardId(id)
    }
  }
}
