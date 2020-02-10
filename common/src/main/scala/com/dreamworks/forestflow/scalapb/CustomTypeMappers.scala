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
package com.dreamworks.forestflow.scalapb

import akka.actor.ActorRef
import com.typesafe.scalalogging.LazyLogging
import scalapb.TypeMapper

object CustomTypeMappers {

  object ActorTypeMappers extends LazyLogging {
    import com.dreamworks.forestflow.akka.extensions.ExternalAddress
    import com.dreamworks.forestflow.startup.ActorSystemStartup

    def serializeAkkaDefault(ref: ActorRef): String =
      ref.path.toSerializationFormatWithAddress(ExternalAddress(ActorSystemStartup.system).addressForAkka) // toSerializationFormat //

    def deserializeAkkaDefault(refString: String): ActorRef =
      ExternalAddress(ActorSystemStartup.system).akkaActorRefFromString(refString)


    implicit val actorRefTypeMapper: TypeMapper[String, akka.actor.ActorRef] =
      TypeMapper[String, akka.actor.ActorRef] {
        from =>
          // logger.trace(s"Deserializing actor ref string: $from to ${deserializeAkkaDefault(from)}")
          deserializeAkkaDefault(from) } {
        ref =>
          // logger.trace(s"Serializing actor ref $ref to ${serializeAkkaDefault(ref)}")
          serializeAkkaDefault(ref)
      }
  }

}
