<!--
    Copyright 2019 DreamWorks Animation L.L.C.
    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
-->
# Getting started
We provide scripts to build ForestFlow for use in local, or custom distributed clusters, or specifically for [Kubernetes](https://kubernetes.io/).
The result of a build is a JAR with all dependencies included. We also provide [Buildah](https://buildah.io/) [scripts](https://github.com/dreamworksanimation/ForestFlow/tree/master/buildah/buildah.sh) for building OCI-compatible images that you can run
using container engines such as [podman](https://podman.io/) and [docker](https://www.docker.com/) etc..

<a name="build-requirements">Build requirements:</a>
 - Java 11 (tested with [openjdk](https://openjdk.java.net/))
 - [Maven](https://www.apache.org/)
 
<a name="image-build-requirements">Container (OCI) image build requirements:</a>
 - [Buildah](https://buildah.io/) 
 - [Podman](https://podman.io/)

#### Building ForestFlow
Have a look at [Build Requirements](#build-requirements) for essential build dependencies.

###### Create JAR without Kubernetes dependencies

```bash
./../buildah/.build-local.sh
``` 

###### Create a JAR with Kubernetes dependencies
This will include Kubernetes-specific dependencies for cluster discovery
```bash
./../buildah/.build-kubernetes.sh
``` 

There is no harm in using the Kubernetes-specific build locally however note that your JAR file will have more
dependencies and is subsequently larger in size.

#### ForestFlow Configuration

Controlling what ForestFlow does and how it looks for nodes to join as a cluster, or simply come up with a single local
instance is all based on configuration properties defined in [application.conf](https://github.com/dreamworksanimation/ForestFlow/tree/master/serving/src/main/resources/application.conf)

ForestFlow uses [Lightbend Config](https://github.com/lightbend/config), formerly known as Typesafe Config, for all its configuration properties.
The configuration defined in the [application.conf](https://github.com/dreamworksanimation/ForestFlow/tree/master/serving/src/main/resources/application.conf) controls how 
ForestFlow clustering works (custom/local vs K8s), where it persists data, and where ([Kafka cluster](https://kafka.apache.org/)) predictions are logged. 


The configuration has 3 main sections (defaults, local, and K8s). ForestFlow will always load configuration values from the defaults section.
ForstFlow will conditionally load the `local` configs or `K8s` based on the value of the environment variable `APPLICATION_ENVIRONMENT_CONFIG`

Example: ```APPLICATION_ENVIRONMENT_CONFIG=local``` will load local configs.

Description of configuration sections:
 - defaults:
 
    Always loaded.
    
    This provides necessary defaults for most ForestFlow configurations.
    Some of these can be changed based on your use case. Others are necessary and cannot be removed or changed.
    We recommend only adjusting these after you're comfortable running and operating ForestFlow and AKKA Clusters.
    We provide sensible defaults for most parameters.
    We also provide environment variable overrides for the parameters that are meant to be user-configurable. 
    
 - local
 
    Conditionally loaded if  ```APPLICATION_ENVIRONMENT_CONFIG=local```
    
    This is meant to simplify a local ForestFlow instance or a custom deployment without much opinion around discovery.
    You can just as easily bring up a single node on a single machine or multiple nodes on a single host or across multiple
    servers. The options are fairly open. Local does take some assumption about where data is persisted that you'll have
    to override for production deployments in this mode. The K8s section provides an example using the `jdbc-journal` and 
    `jdbc-snapshot-store` plugins for persistence. This is a good approach.  If using JDBC for persistence, the JDBC 
    properties are defined in `defaults.slick` in the application.conf.
                       
    
 - K8s
 
    Conditionally loaded if  ```APPLICATION_ENVIRONMENT_CONFIG=K8s```
        
    This section is custom-tailored for a Kubernetes-based deployment. Note that you must have built the JAR using the 
    K8s maven profile (or via the build-kubernetes.sh script which does exactly that to get the necessary dependencies for
    Kubernetes API-based node discovery for cluster deployments.
    
    This also assumes a JDBC-based persistence model for preserving cluster state. The JDBC properties are defined in `defaults.slick`
    in the application.conf.

You have a lot of control over how you want to run ForestFlow but we provide a few defaults:
 - Logging
 
   ForestFlow uses Kafka for prediction logging. To use this, you must supply the following environment variables
   so ForestFlow knows where to log [Prediction](https://github.com/dreamworksanimation/ForestFlow/tree/master/core/src/main/protobuf/Prediction.proto) messages to:
   
    - KAFKA_BOOTSTRAP_SERVERS_CONFIG
    
      List of Kafka brokers
      
    - KAFKA_PREDICTION_LOGGER_BASIC_TOPIC_CONFIG
    
      Kafka topic to use when logging BASIC REST API-based inference requests and responses.
      
    - KAFKA_PREDICTION_LOGGER_GRAPHPIPE_TOPIC_CONFIG
      
      Kafka topic to use when logging GraphPipe-based inference requests and responses.
    
 - Persistence
 
    Any AKKA persistene plugin can be used. 
    
    For local, ```APPLICATION_ENVIRONMENT_CONFIG=local```, installs we default to a local leveldb using `akka.persistence.journal.leveldb`
    This is good for quick tests but doesn't really offer any cluster-external persistence storage guarantees.
    We recommend overriding this by supplying values for the environment variables controlling `journal` and `snapshot` persistence.
 
     Exxample: 
     
     ```
     AKKA_PERSISTENCE_JOURNAL_PLUGIN=jdbc-journal
     AKKA_PERSISTENCE_SNAPSHOT_STORE_PLUGIN=jdbc-snapshot-store
     ```
 
    This is the default for K8s, ```APPLICATION_ENVIRONMENT_CONFIG=K8s```
    
    The specifics of which database to connect to and how is defined in `defaults.slick` in application.conf
     
 
  - Clustering
  
    You can run ForestFlow as a single instance, or scale it horizontally across multiple nodes. To form a Cluster of nodes
    , nodes have to be able to "find", aka. discover, each other. This can be done in a myriad of different ways; most common
    being DNS, Kubernetes API, Consul, or a simple list of IPs.
     
    ForestFlow uses Lightbend's Akka Cluster Bootstrap libraries to offer the most flexibility and battle tested APIs.
    As of this writing, Lightbend's Akka Discovery supports Simple IP list, DNS, Kubernetes, Consul, Marathon, and AWS.
     
    See [here](https://doc.akka.io/docs/akka-management/current/discovery/index.html) for more details.
    
    ForestFlow defaults to `local-cluster` when the application environment is set to `local` and defaults to `kubernetes-api` 
    when the application environment is set to `K8s` while providing sensible configuration defaults for each.
    
    This means you can easily bring up a local, single-node, instance of ForestFlow with very little configuration changes, if any.
    
  - Clustering Split-Brain Resolver
  
    For distributed systems, network partitions are a way of life and must be handled appropriately. Lightbend 
    [describe this problem eloquently](https://doc.akka.io/docs/akka-enhancements/current/split-brain-resolver.html#the-problem).
    
    Lightbend also provides a commercial implementations for a Split-Brain resolver however in an effort to keep this as open as possible,
    ForestFlow uses an Open Source Split-Brain Resolver called [simple-akka-downing](https://github.com/arnohaase/simple-akka-downing)
    
    We again provide sensible defaults in the `defaults` section of the application.conf file but feel free to customize based on your
    needs. Some examples for customization could be changing the strategy to "static-quorum" or "keep-majority". See 
    [simple-akka-downing](https://github.com/arnohaase/simple-akka-downing) for more details on customizing this and 
    Lightbend's [Split Brain Resolver](https://doc.akka.io/docs/akka-enhancements/current/split-brain-resolver.html#akka-split-brain-resolver) documentation.


#### Creating an OCI-compliant Image
You can build and run ForestFlow in a container (docker, podman) and we provide scripts to help with this process.
See [Image Build Requirements](#image-build-requirement) for details on requirements for building a ForestFlow image.
We may supply a standard image in a container registry like docker.io at some point in the future. 
 
ForestFlow comes bundled with a [Buildah](https://github.com/dreamworksanimation/ForestFlow/tree/master/buildah/buildah.sh) script that assumes a successful build is available in 
the target directory of the serving module. Using either the [build-local.sh](https://github.com/dreamworksanimation/ForestFlow/tree/master/buildah/build-local.sh) or 
[build-kubernetes.sh](https://github.com/dreamworksanimation/ForestFlow/tree/master/buildah/build-kubernetes.sh) scripts will provide just that. 

```bash
# Create a "local" build
./../buildah/build-local.sh

# Compile into an OCI-compliant image using Buildah
./../buildah/buildah.sh

# Use podman (or docker) to run ForestFlow in a container locally
podman_container=$(podman run -d \
-e "APPLICATION_ENVIRONMENT_CONFIG=local" \
--net=host \
--name=ff-serving localhost/com.dreamworks.forestflow-serving:0.2.2)

podman logs -f ${podman_container}
```

See [https://github.com/dreamworksanimation/ForestFlow/tree/master/buildah/run-local-container.sh](https://github.com/dreamworksanimation/ForestFlow/tree/master/buildah/run-local-container.sh) for another example of using Podman (similar to docker) and 
supplying some overrides for Persistence and Kafka logging.
