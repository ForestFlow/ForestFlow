<!--
    Copyright 2020 DreamWorks Animation L.L.C.
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
# Concepts
## A ForestFlow Cluster

#### Servable
The smallest deployable entity in ForestFlow is called a `Servable`. This closely follows nomenclature used in TensorFlow Serving.
A Servable represents an instance of a model loaded into memory for serving inference requests. Deploying a model means creating a Servable.
Each Servable is uniquely identified by its Fully Qualified Release Version or `FQRV` for short. 

#### Fully Qualified Release Version (FQRV)
The FQRV is one of the most important concepts within ForestFlow because routing and scoring (inference) is based on the FQRV.

The [FQRV](https://github.com/ForestFlow/ForestFlow/tree/master/core/src/main/protobuf/FQRV.proto) consists of a `contract: Contract` and `release_version: String`.

###### Release Version
The `release_version` along with the Contract **MUST** uniquely identify a deployed Servable/Model.
The release version is used to distinguish between Servables with the same features that serve the same use case.
The release version has no requirements for format; it's a simple string however a good candidate might be the date a model was trained and the type of model deployed.
This is how ForestFlow allows for multiple versions of a model to co-exist and serve the same use case.

The ability to produce and simultaneously deploy multiple versions of a model for the same use case allows for
 - Canary deployments: Gradual onboarding of new releases
 - Shadow mode: Deploying models that only listen to inference requests and log their results without contributing to user responses. i.e., models that shadow production work for test and validation.
   This can be used for example to implement a Blue-Green release process.
 - Performance-based routing: Multiple models deployed for the same use case and routing to the model that shows best performance (based on performance metric feedback sent to ForestFlow)
 - Trigger-based onboarding: Routing to deployed models only when a certain criteria is established. A good example for this would be to deploy newer/retrained versions of a model in shadow mode only and use an external approval process to Trigger activation like a Jira ticket approval.
 - Time-based onboarding: Similar to Canary deployments except the onboarding and percentage of traffic a new release gets is automated and time driven.

In addition to deployment (Validity and Phase-In policies) a Contract, details below, defines expiration policies for the group of Servables defined within it.

This approach allows the user to define a myriad of scenarios. A few examples include:

 - Keep Top-K performers: Use a performance-based router and only keep the Top K performing Servables.
   Automatically expire servables that fall out of the range.
   All others still compete for traffic based on a defined performance metric.
 - Route to latest deployed Servable and keep previous K Servables around for shadow mode only.
   This allows for quick rollback to Servables still in Shadow mode in case something unexpected happens with the latest deployment.
 
###### Contract
A [Contract](https://github.com/ForestFlow/ForestFlow/tree/master/core/src/main/protobuf/Contract.proto) is a struct of 3 elements:
 - <a name="organization"></a>organization: String. Top-level organization or namespace the servable belongs to. This of this as your team's name, company, or department name. 
 - <a name="project"></a>project: String. The project under the organization the servable addresses. Think of this as the specific use case this servable addresses.
 - <a name="contract_number"></a>contract_number: Int32. The contract is meant to be used to group multiple model releases. Inference requests are at the contract level.
 Different model versions can be deployed to a single Contract and compete for traffic in some way. An inference request always addresses a Contract number within an Organization and Project.
 The defined Routing, Validity and Phase-In policies determine which specific Servable ends up processing an inference request.
 This is essentially where all the routing happens between Servable releases. For example, you could have 2 models deployed with    

The relationship between Contract to Servable is 1-to-Many. A Contract can have many Servables.
The identifier of each Servable within a Contract is the aforementioned [release version](#release-version).
So there's a parent-child relationship between Contracts and Servables. The relationship is defined by the values of the FQRV.

Example of 2 Servables under the same Contract

FQRV for a Servable named "h2o_regression_**v1**":
```json
{ 
  "fqrv" : {
    "contract" : {
      "organization": "DreamWorks",
      "project": "schedule",
      "contract_number": 0
    },
    "release_version": "h2o_regression_v1"
  }
}
```

FQRV for a Servable named "h2o_regression_**v2**":
```json
{
  "fqrv" : {
    "contract" : {
      "organization": "DreamWorks",
      "project": "schedule",
      "contract_number": 0
    },
    "release_version": "h2o_regression_v2"
  }
}
```

These 2 Servables share the same Contract defined by the organization, project and contract_number.

When we define a router and expiration policy for the Contract. The Contract then uses these settings to understand how it needs to route requests between the deployed Servables
within it. Similarly the expiration policy on the Contract applies to these 2 servables.  

###### FQRV Extraction
The FQRV is defined at the time of model deployment.
ForestFlow has support for automatic FQRV extraction for some protocols when fetching a model.
Git would be a good example. FQRV extraction is supported if a certain tagging convention is used otherwise an FQRV with the Serve deployment request is required. 
See Servable (Model) Deployment for more details.

## Servable (Model) Deployment
Deploying a model and creating a Servable in ForestFlow is a simple REST API call with parameters to configure policies for the Contract and Servable.
If this is a new use case/Contract, the general recommendation is to first define and create the [Contract](https://github.com/ForestFlow/ForestFlow/tree/master/core/src/main/protobuf/Contract.proto) and [Contract Settings](https://github.com/ForestFlow/ForestFlow/tree/master/core/src/main/protobuf/ContractSettings.proto) for the use case the Servable is meant to be deployed to.

#### Creating a Contract
Recall that a Contract consists of potentially more than one Servable. The Contract Settings determine how a Contract routes traffic between its underlying Servables in 
addition to when it considers a Servable expired (expiration policy) and removes it.

 - API Endpoint: contract/[organization](#contract-organization)/[project](#contract-project)/[contract_number](#contract-contract_number)
 - REST Verb: POST for new Contracts. PUT for updating existing Contracts
 - Payload: JSON, as [Contract Settings](https://github.com/ForestFlow/ForestFlow/tree/master/core/src/main/protobuf/ContractSettings.proto)

The payload is a JSON that represents Contract Settings which defines an expiration policy and router.
The `Expiration Policy` governs how and when Servables are marked as expired and removed from ForestFlow and the Contract.
The `Router` controls how traffic is distributed across different Servables, if any, within a Contract.

Example:

```bash
http POST https://forestflow.com/contract/DreamWorks/schedule/1
```
```json
{
  "expirationPolicy": {
    "KeepLatest": {
      "servablesToKeep": 2
    }
  },
  "router": {
    "LatestPhaseInPctBasedRouter": {}
  }
}
```

In this example we define a new Contract with Organization = DreamWorks, Project = schedule, Contract Number = 1
This Contract has a Keep Latest expiration policy set to keep the 2 most recently active Servables.
Anything beyond that is expired and removed. Additionally the Contract sets up the Router such that traffic only goes to
the most recently active Servable assuming it's been fully phased-in based on the Servable's own Phase-In policy.

The effect of this setup is that once a Servable is fully phased-in and made active (active state is based on the Servable's Validity Policy)
it will completely take over the previous Servable's inference requests however because the expiration policy keeps the last 2 active Servables,
the previous Servable, prior to the now most recent one, will remain active in Shadow Mode, essentially replicating the work and logging its results but 
not responding directly to user inference requests.

The following diagram illustrates this scenario with 2 Servables (FOO, and BAR) under the same Contract.

![ForestFlow](static/forestflow_example_contract_settings.png?raw=true "ForestFlow Contract Settings example")


Currently available [Expiration Policy](https://github.com/ForestFlow/ForestFlow/tree/master/core/src/main/protobuf/ExpirationPolicies.proto) implementations are:
 - [KeepLatest](https://github.com/ForestFlow/ForestFlow/tree/master/core/src/main/scala/com/dreamworks/forestflow/serving/impl/ExpirationPolicies.scala)
 
   Keeps a supplied number of valid (active) Servables based on date Servable became active. Keeps most recently active.
   
 - [KeepTopRanked](https://github.com/ForestFlow/ForestFlow/tree/master/core/src/main/scala/com/dreamworks/forestflow/serving/impl/ExpirationPolicies.scala)
 
   Keeps a supplied number of Servables based on a performance metric. 
 
 
Currently available [Router](https://github.com/ForestFlow/ForestFlow/tree/master/core/src/main/protobuf/ContractRouters.proto) implementations are:
 - [FairPhaseInPctBasedRouter](https://github.com/ForestFlow/ForestFlow/tree/master/core/src/main/scala/com/dreamworks/forestflow/serving/impl/ContractRouters/FairPhaseInPctBasedRouter.scala)
   
   Equally distributes traffic between valid (active) Servables based on their Phase In Percent.
   
 - [LatestPhaseInPctBasedRouter](https://github.com/ForestFlow/ForestFlow/tree/master/core/src/main/scala/com/dreamworks/forestflow/serving/impl/ContractRouters/LatestPhaseInPctBasedRouter.scala)
   
   Routes 100% of traffic to the latest (last to become valid) servable.
   If latest servable is not at 100% phase in, this acts a lot like a FairPhaseInPctBasedRouter against the latest 2
   servables, i..e, the latest servable and the one prior to that will share traffic based on their respective Phase In %
   until the latest (last to become valid) Phase In % hits 100% which will then trigger the router to send 100% traffic to latest servable.
   
 
#### Creating a Servable
After [setting up a Contract](#creating-a-contract), deploying a model and creating a Servable is a simple REST call.
The REST call can either reference where the MLmodel yaml file is if using [MLFlow](https://mlflow.org)
In ForestFlow this is referred to as the [MLFlowServeRequest](https://github.com/ForestFlow/ForestFlow/tree/master/core/src/main/protobuf/MLFlowServeRequest.proto).

OR

Simply provide all necessary information as part of the request itself as defined here.
In ForestFlow this is referred to as the [BasicServeRequest](https://github.com/ForestFlow/ForestFlow/tree/master/core/src/main/protobuf/BasicServeRequest.proto). We start off with the BasicServeRequest and then expand on how the MLFlowServeRequest differs.

 - BasicServeRequest
 
   Let's start with an example and then breakdown the various components
 
     ```json
     {
       "path": "file:///my-models/DreamWorks/schedule/0",
       "flavor": {
        "H2OMojo" : {
          "mojoPath": "myRegressionModel_2.0.zip",
          "version": "3.22.0.2"
        }
       },
       "fqrv" : {
         "contract" : {
           "organization": "dreamworks",
           "project": "HTTYD",
           "contract_number": 3
         },
         "release_version": "2.0"
       },
       "servableSettings": {
         "policySettings": {
           "validityPolicy": [
             {
               "ImmediatelyValid": {}
             }
           ],
           "phaseInPolicy": {
             "ImmediatePhaseIn": {}
           }
         },
         "loggingSettings": {
           "logLevel": "FULL",
           "keyFeatures": [
             "someKey",
             "anotherKey"
           ]
         }
       }
     }
     ``` 
 
   - <a name="serve-request-path">**path**</a>: Where is the model artifact stored (excluding the model file name). ForestFlow must be able to access this file path.
     Example:
     Local filesystem: file:///my-models/DreamWorks/schedule/0
     Git: git@github.com:<USER or Org>/<project>.git#v<Numeric Contract Number>.<Release Version>
     
     ForestFlow currently supports the following [StorageProtocols](https://github.com/ForestFlow/ForestFlow/tree/master/core/src/main/scala/com/dreamworks/forestflow/utils/SourceStorageProtocols.scala):
      - local file system
      - git (with support for Git LFS)
      - S3 
      
     ForestFlow is pluggable and there are plans to add support for other protocols
     such as HDFS, HTTP and FTP in the future.
     
   - **artifact_path** (optional): An optional string that further defines the root of the model artifact itself.
     
     In the local filesystem example used above for `path`, we could have set the path to "file:///my-models/DreamWorks" and then further 
     detailed that the artifact_path is "schedule/0".
     
     _Usage:_
     
     For the BASIC REST API, this is more for organizational and future proofing, in addition to closely mimicing the MLModel-based API.
     The real benefit in having an artifact_path is when using the MLModel-based API. You may chose to version control your `MLmodel` yaml files
     in Git but then have your artifacts stored in S3 for example. This allows you to do exactly that. The [path](#serve-request-path) defines 
     where the MLmodel file can be found and the artifact_path in the MLmodel file then describes the root path and protocol of where the artifacts
     can be found which can be something else entirely.

     
   - **[fqrv](https://github.com/ForestFlow/ForestFlow/tree/master/core/src/main/protobuf/FQRV.proto)**: Required if the path doesn't support FQRV extraction and the path provided doesn't follow an extraction pattern.
   
     Currently Git is the only protocol that supports FQRV extraction.
      - Git User/Org maps to fqrv.contract.organization
      - Git project maps to fqrv.contract.project
      - Git Tag format **MUST** follow v<Numeric Contract Number>.<Release Version? format. Example Git tag: v0.h2o_regression_v2
        
        The tag segments map to fqrv.contract.contract_number and fqrv.release_version respectively.
        
        If the Git path supplied does not match this format then an FQRV JSON is required.
        
        An FQRV can always be provided even if a path supports FQRV extraction. The explicitly provided FQRV takes precedence.

   - **[flavor](https://github.com/ForestFlow/ForestFlow/tree/master/core/src/main/protobuf/Flavors.proto)**: The Servable flavor and flavor properties
    
      ForestFlow currently supports:
       - H2O Flavor: The H2O flavor only works with the mojo export format. The H2O flavor has the following format:
       
       ```json
       {
         "flavor": {
            "H2OMojo" : {
              "mojoPath": "mojofilename.zip",
              "version": "3.22.0.2"
            }
         }
       }
       ```
       
       mojoPath: The H2O MOJO file name
       
       version: The H2O version used to generate the MOJO file. This is intended for future compatibility requirements.
       
   - **[servableSettings](https://github.com/ForestFlow/ForestFlow/tree/master/core/src/main/protobuf/ServableSettings.proto)**: Define a validity and phase in policy.
   
      - **[validityPolicy](https://github.com/ForestFlow/ForestFlow/tree/master/core/src/main/protobuf/ValidityRules.proto)**: Tells ForestFlow when to consider this Servable valid (active).
      A Servable is only ever considered for inference requests if it's deemed "active". 
      
        Currently Supported [Validity Policies](https://github.com/ForestFlow/ForestFlow/tree/master/core/src/main/protobuf/ValidityRules.proto):
         - [ImmediatelyValid](https://github.com/ForestFlow/ForestFlow/tree/master/core/src/main/scala/com/dreamworks/forestflow/serving/impl/ValidityRules.scala)
         - [NeverValid](https://github.com/ForestFlow/ForestFlow/tree/master/core/src/main/scala/com/dreamworks/forestflow/serving/impl/ValidityRules.scala)
         - [TimeBasedValidity](https://github.com/ForestFlow/ForestFlow/tree/master/core/src/main/scala/com/dreamworks/forestflow/serving/impl/ValidityRules.scala)
         - [PerformanceBasedValidity](https://github.com/ForestFlow/ForestFlow/tree/master/core/src/main/scala/com/dreamworks/forestflow/serving/impl/ValidityRules.scala)
         - [MinServedEventsBasedValidity](https://github.com/ForestFlow/ForestFlow/tree/master/core/src/main/scala/com/dreamworks/forestflow/serving/impl/ValidityRules.scala)
       
      - **[phaseInPolicy](https://github.com/ForestFlow/ForestFlow/tree/master/core/src/main/protobuf/PhaseInPolicies.proto)**: Tells ForestFlow how to phase-in this Servable, i.e., how to appropriate traffic to this Servable after it becomes valid.
        
        Currently Supported  [Phase In Policies](https://github.com/ForestFlow/ForestFlow/tree/master/core/src/main/protobuf/PhaseInPolicies.proto): 
         - [ImmediatePhaseIn](https://github.com/ForestFlow/ForestFlow/tree/master/core/src/main/scala/com/dreamworks/forestflow/serving/impl/PhaseInPolicies.scala)
         - [LinearTimeBasedPhaseIn](https://github.com/ForestFlow/ForestFlow/tree/master/core/src/main/scala/com/dreamworks/forestflow/serving/impl/PhaseInPolicies.scala)
         
     - **[loggingSettings](https://github.com/ForestFlow/ForestFlow/tree/master/core/src/main/protobuf/LoggingSettings.proto)**: Define when and how a Servable "logs" prediction events which are the result of an inference request.
     
       Logging Settings takes 3 parameters
        - logLevel (defaults to NONE): `NONE`, `FULL`, or `SAMPLE`
        - keyFeatures `array[string]`: Optional list of strings that tell ForestFlow which config values from an inference request to pull in as the key for the logged prediction record.
          If an inference request provides configs in the config map, the logged Prediction record will attempt to locate the list of keys defined here in the config map and 
          uses the matching key values from the config map to formulate a new "Key" for the Prediction record.
          The default [Prediction Logger](https://github.com/ForestFlow/ForestFlow/tree/master/event-subscribers/src/main/scala/com/dreamworks/forestflow/event/subscribers/PredictionLogger.scala) for ForestFlow is Kafka.
          The key extracted here, if any, will be used as the Key in a Kafka `ProducerRecord` 
          
        - keyFeaturesSeparator (optional, defaults to "."): The separator to use if multiple keys are provided and their config values are found. 
