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
# Inference
ForestFlow currently exposes 2 APIs for inference; namely the BASIC REST API and the GraphPipe API. Both APIs support a similar set of features.
Servable type implementations in ForestFlow (model types), as in H2O or TensorFlow, have the option to support the BASIC REST API, the GraphPipe API or both.
This is a server implementation detail for each servable and it's meant to offer flexibility for onboarding servable types quickly that may not adhere to every interface ForestFlow supports.
This also keeps ForestFlow's API flexible enough to incrementally adapt more interfaces in the future.  

###### Servable implementation interface support matrix:

| Servable Type |  BASIC REST API  |  GraphPipe API  |
|---|---|---|
| H2O  | YES  | YES  |
 

## Inference - Using the BASIC REST API
The BASIC REST API relies on ProtoBuf definitions but exposes a JSON interface for ease of use.
The proto schema is defined in [InferenceRequest.proto](https://github.com/dreamworksanimation/ForestFlow/tree/master/core/src/main/protobuf/InferenceRequest.proto)

A BASIC REST API Inference Request takes:
  - A Tensor Schema which maps Tensors and their types to a list of field names
  - An Array of [Datums](https://github.com/dreamworksanimation/ForestFlow/tree/master/core/src/main/protobuf/Tensor.proto) which in turn is an Array of Tensors
  - The Tensors themselves carrying features. A Tensor per data type.
  - A map<string, string> of configs. Configs can be used to supply data used when logging model predictions or to supply additional configuration parameters to Servables for inference requests.
    The keys in the config map are matched on with logging settings keys for logging predictions.
  
Each element in the Datum array represents an inference request record with a full feature set for an inference request.
A Datum with multiple array elements represents a batch prediction for multiple records. Every Datum record represents an inference request.
This is how the BASIC API allows for batch inference requests for optimized client-server round-trip performance.

Note that routing to different models is done at the entire batch level. So the entire array of Datums is sent to a single model for inference.
This is a  tradeoff the user has to make when between # of inference requests in a single batch vs routing requirements.
For example if you had 3 models competing for inference requests, all the records in a single inference request (i.e., all the elements in the Datum array) will route to a single model.
Subsequent inference requests, and their associated Datum record(s) will route to a model based on the routing criteria and phase-in percentages.
This isn't something to ponder too much about. Simply be aware of it in case your use case requires different behavior.

Here's a contrived inference request example on a gaming dataset 
```json
{
  "schema": [
    { "type": "String", "fields": ["name", "category"] },
    { "type": "Float64",  "fields": ["hp", "attack", "defence"] }
  ],
  "datum": [
    {
      "tensors": [
        { "String": { "data": ["human1", "human"]  } },
        { "Float64": { "data": [67.37, 25.2, 80.6]  } }
      ]
    },
    {
      "tensors": [
        { "String": { "data": ["mon16", "monster"]  } },
        { "Float64": { "data": [76.5, 62, 33.9]  } }
      ]
    }
  ],
  "configs" : {"gameid":  "some-uuid", "playerid":  "player-uuid"}
}
```

Notice how this example defines a schema of 2 tensors because the features the inference request for the model where this is used has both String and Float64 features.
The schema further identifies the positional field names for each supplied feature column. For example in the first datum, human1 represents the `name` field. Similarly 80.6 represents the value for the `defence` field.
Also notice that this is a batch inference request since there are 2 datums provided, each with its full list of features matching the schema provided.
There's no fundamental difference in structure with a a single inference request vs a batch request. A single inference request is just a batch request with 1 datum record.

######Performance considerations for batch size
While larger inference batches are generally a good thing, if applicable, depending on how ForstFlow is configured and deployed, 
the number of batch records in each request may introduce some undesirable performance or latency implications.
For example, in shadow mode, where a model is simply shadowing the execution of inference requests and logging results, these models will pickup shadow requests when there are no pending
high priority user-facing inference requests in their queue. However if the model is then busy with a very large batch shadow mode inference request and it is then required to serve a high priority user-facing
inference request, it does not stop processing the in-hand shadow request. i.e., it doesn't preempt its work. The high-priority inference request waits until the next available slot is available.
This can be avoided by balancing the size of batches in addition to deployment considerations. Planned versions of ForestFlow will add more flexibility here and bring in the concept of model replicas that can balance and distribute work.  

## Inference - Using the GraphPipe API
