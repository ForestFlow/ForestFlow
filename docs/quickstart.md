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
# Quick Start
Let's bring up a single-node local instance of ForestFlow

1. Build ForestFlow

    ```bash
    # Create a "local" build
    chmod +x ./build/build-local.sh
    ./build/build-local.sh
    ``` 
1.  Set application environment to local

    ```bash
    export APPLICATION_ENVIRONMENT_CONFIG=local
    ```
1. Define local working directory
    
    This is where ForestFlow will store its state if using default persistence plugin for local install
    
    ```bash
    mkdir -p ./ff-temp
    export LOCAL_WORKING_DIR_CONFIG=./ff-temp
     ```
1. Bring up ForestFlow
   
   This is where ForestFlow will store its state if using default persistence plugin for local install
   See what the version number is defined as in the property `forestflow-latest.version` in [pom.xml](https://github.com/dreamworksanimation/ForestFlow/tree/master/pom.xml)
   
   Assuming it's 0.2.3, run the following
    
   ```bash
   export FORESTFLOW_VERSION=0.2.3
   chmod +x ./build/run-local.sh
   ./build/run-local.sh
   ```
   This should being up ForestFlow on port `8090`
   If you get any port conflicts feel free to change the ports used for JMX or for ForestFlow by supplying an environment
   variable `APPLICATION_HTTP_PORT_CONFIG` that will override the default 8090 port used.
   
1. Let's verify ForestFlow is running and we can access the API

    ```bash
    curl 127.0.0.1:8090/contracts/list
    ```
    
    You should see something like this:
    ```json
    {
        "Contracts": {
            "contracts": []
        }
    }
    ```
    
    To pretty print from curl, you can pipe to python -m json.tool like so:
    ```bash
    curl 127.0.0.1:8090/contracts/list | python -m json.tool
    ```
    
    You can continue using curl but I prefer using [HTTPie](https://httpie.org/) from the command line as it simplifies making REST API calls.
    
    See [installation](https://httpie.org/#installation) for HTTPie
    
    Using HTTPie
    
    ```bash
    http 127.0.0.1:8090/contracts/list
    ```
     
1. Let's deploy a model to ForestFlow
   
   The git repo comes with a simple H2O model that we can use to test with.
   This sample H2O model uses the [Combined Cycle Power Plant](http://archive.ics.uci.edu/ml/datasets/Combined+Cycle+Power+Plant) dataset and was created
   based on an online H2O tutorial that you can find [here](https://github.com/h2oai/h2o-tutorials/blob/master/h2o-world-2017/automl/Python/automl_regression_powerplant_output.ipynb).
   
   As the tutorial explains, the goal is to predict the energy output given some input features.
   
   Have a look at the servable deployment definition [https://github.com/dreamworksanimation/ForestFlow/tree/master/tests/basicapi-local-h2o.json](https://github.com/dreamworksanimation/ForestFlow/tree/master/tests/basicapi-local-h2o.json)
   ```json
    {
      "path": "file://<local path for repo>/tests",
      "fqrv" : {
        "contract" : {
          "organization": "samples",
          "project": "energy_output",
          "contract_number": 0
        },
        "release_version": "StackedEnsemble_AllModels_AutoML_20191002_103122"
      },
      "flavor": {
        "H2OMojo" : {
          "mojoPath": "StackedEnsemble_AllModels_AutoML_20191002_103122.zip",
          "version": "3.22.0.2"
        }
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
            "reqid"
          ]
        }
      }
    }
    ```
  
   This JSON payload is an example of what a model/servable deployment definition looks like.
   Notice how we specify validity, phase-in, and logging settings for the servable we're about to create.
   
   Let's go ahead and create this Servable in ForestFlow, again, using HTTPie as a command-line HTTP client.
   We need to replace the <local path for repo> in the file with the path for where the file is located.
   We do this using `sed` and passing the result to `httpie`. Alternatively, edit the file and supply the correct path.
   
   ```bash
   export IP=127.0.0.1
   export PORT=8090
    
   # Deploy a model as a Servable to ForestFlow
   echo $(sed 's:<local path for repo>:'`pwd`':' ./tests/basicapi-local-h2o.json) | http POST http://${IP}:${PORT}/servable
   ```
   
   If all goes well, you should see a ForestFlow response indicating the Servable was created successfully.
   ```json
    {
        "ServableCreatedSuccessfully": {
            "fqrv": {
                "contract": {
                    "contractNumber": 0, 
                    "organization": "samples", 
                    "project": "energy_output"
                }, 
                "releaseVersion": "StackedEnsemble_AllModels_AutoML_20191002_103122"
            }
        }
    }
   ```
   
   Now if we inspect the list of Contracts again we see the Servable and Contract we just deployed
   ```bash
   http http://${IP}:${PORT}/contracts/list
   ```
   
   ```json
    {
        "Contracts": {
            "contracts": [
                {
                    "contractNumber": 0, 
                    "organization": "samples", 
                    "project": "energy_output"
                }
            ]
        }
    }
   ```
   
   We didn't explicitly deploy a Contract here as is typically [recommended](./concepts.md#creating-a-contract).
   ForestFlow will automatically setup a Contract with default routing and expiration policy settings if you don't provide one.
   Replying on this behavior is not recommended as it sets you up with ForestFlow defaults which might change in future releases.
   
   It's best to [setup the Contract](./concepts.md#creating-a-contract) first and then deploy the Servable. Nonetheless, we have one setup and we can use it to 
   score against.

   You can also inspect the Servables under a Contract. In this case, we'll see a single Servable deployed
   ```bash
   http http://${IP}:${PORT}/samples/energy_output/0/list
   ``` 
   
   ```json
    [
        {
            "FQRV": {
                "contract": {
                    "contractNumber": 0, 
                    "organization": "samples", 
                    "project": "energy_output"
                }, 
                "releaseVersion": "StackedEnsemble_AllModels_AutoML_20191002_103122"
            }
        }
    ]
   ```
   
1. Let's score against the Servable we've deployed

   Let's get some metadata about the Servable we have deployed
   ```bash
   http http://${IP}:${PORT}/samples/energy_output/0/StackedEnsemble_AllModels_AutoML_20191002_103122/metadata
   ```
  
    ```json
    {
        "BasicMetaData": {
            "description": "ServableSettings(PolicySettings(List(ImmediatelyValid()),ImmediatePhaseIn()),Some(LoggingSettings(FULL,List(reqid),None)))", 
            "fqrv": {
                "contract": {
                    "contractNumber": 0, 
                    "organization": "samples", 
                    "project": "energy_output"
                }, 
                "releaseVersion": "StackedEnsemble_AllModels_AutoML_20191002_103122"
            }, 
            "inputs": [
                {
                    "description": "[0:TemperatureCelcius,1:ExhaustVacuumHg,2:AmbientPressureMillibar,3:RelativeHumidity]", 
                    "name": "numeric", 
                    "shape": [
                        "4"
                    ], 
                    "type": "Float64"
                }
            ], 
            "name": "prediction", 
            "server": "ForestFlow"
        }
    }
    ```
  
    Notice the Servable takes an input Tensor of type Float64 with 4 fields: TemperatureCelcius, ExhaustVacuumHg, AmbientPressureMillibar, and RelativeHumidity
  
    Scoring against the model represented by the Servable we deployed is fairly simply.    
    Have a look at the contents of [tests/basicapi-score-1.json ](https://github.com/dreamworksanimation/ForestFlow/tree/master/tests/basicapi-score-1.json)
  
    ```json
    {
      "schema": [
        { "type": "Float64", "fields": ["TemperatureCelcius", "ExhaustVacuumHg", "AmbientPressureMillibar", "RelativeHumidity"] }
      ],
      "datum": [
        {
          "tensors": [
            { "Float64": { "data": [33.2, 60.5, 1010.7, 30]  } }
          ]
        },
        {
          "tensors": [
            { "Float64": { "data": [29.6, 62, 998.5, 40.62]  } }
          ]
        }
      ],
      "configs" : {"reqid":  "123-456-789"}
    }
    ```
    
    We define the schema, essentially telling ForestFlow in what order to expect the provided features
    and then this particular request provides 2 datum records for inference, this is essentially a batch inference requests
    of 2 rows. The first supplies the Float64 tensor with the data points for each field starting with 33.2 for the Temperature in Celcius.
    The 2nd row does the same thing supplying 29.6 for the temp and so and so forth.
   
    Scoring against the model is as simple as passing this as the body to the score API
    ```bash
    http http://${IP}:${PORT}/samples/energy_output/0/score < tests/basicapi-score-1.json
    ```
  
    ```json
    {
        "Prediction": {
            "datum": [
                {
                    "tensors": [
                        {
                            "Float64": {
                                "data": [
                                    438.18238719825354
                                ]
                            }
                        }
                    ]
                }, 
                {
                    "tensors": [
                        {
                            "Float64": {
                                "data": [
                                    437.5805895467613
                                ]
                            }
                        }
                    ]
                }
            ], 
            "fqrv": {
                "contract": {
                    "contractNumber": 0, 
                    "organization": "samples", 
                    "project": "energy_output"
                }, 
                "releaseVersion": "StackedEnsemble_AllModels_AutoML_20191002_103122"
            }, 
            "schema": [
                {
                    "fields": [
                        "Regression"
                    ], 
                    "type": "Float64"
                }
            ]
        }
    }
    ```
   
    ForestFlow returns a prediction for each row in addition to the FQRV of the Servable that responded with this prediction.
  
    ForestFlow allows for multiple Servables to be deployed under the same Contract and for a routing strategy to determine which
  Servable responds to user requests and if the remaining Servables shadow the inference request for logging and performance 
  monitoring purposes. See the section on [Creating a Contract](./concepts.md#creating-a-contract) and routing for more details. 

1. We can also inspect some stats ForestFlow collects about the use of Servables within a Contract
    ```bash
    http http://${IP}:${PORT}/samples/energy_output/0/stats
    ```
  
    ```json
    [
        {
            "ServableMetrics": {
                "becameValidAtMS": "1570054677902", 
                "createdAtMS": "1570054677899", 
                "currentPhaseInPct": 100, 
                "fqrv": {
                    "contract": {
                        "contractNumber": 0, 
                        "organization": "samples", 
                        "project": "energy_output"
                    }, 
                    "releaseVersion": "StackedEnsemble_AllModels_AutoML_20191002_103122"
                }, 
                "scoreCount": "1", 
                "shadeCount": "0"
            }
        }
    ]
    ```

Finally, you can also build and run ForestFlow in a container (docker, podman) and we provide scripts to help with this process.
See [Creating an OCI-compliant Image](./buildconfig.md#creating-an-oci-compliant-image)