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
<img src="./resources/forestflow_logo_text.png" alt="ForestFlow Logo" width="478" height="139"/>

ForestFlow is a scalable policy-based cloud-native machine learning model server. ForestFlow strives to strike a balance between the flexibility it offers data scientists and the adoption of standards while reducing friction between Data Science, Engineering and Operations teams.

ForestFlow is policy-based because we believe automation for Machine Learning/Deep Learning operations is critical to scaling human resources. ForestFlow lends itself well to workflows based on automatic retraining, version control, A/B testing, Canary Model deployments, Shadow testing, automatic time or performance-based model deprecation and time or performance-based model routing in real-time.

Our aim with ForestFlow is to provide data scientists a simple means to deploy models to a production system with minimal friction accelerating the development to production value proposition.

To achieve these goals, ForestFlow looks to address the proliferation of model serving formats and standards for inference API specifications by adopting, what we believe, are currently, or are becoming widely adopted open source frameworks, formats, and API specifications. We do this in a pluggable format such that we can continue to evolve ForestFlow as the industry and space matures and we see a need for additional support.



# Contents

  - [Overview](./docs/overview.md)
      - [Why ForestFlow?](./docs/overview.md#why-forestflow)
      - [Model Deployment](./docs/overview.md#model-deployment)
      - [Inference](./docs/overview.md#inference)
      - [Currently Supported model formats](./docs/overview.md#currently-supported-model-formats)
  - [Quick Start Guide](./docs/quickstart.md)
  - [Building and Configuration](./docs/buildconfig.md)
      - [Building ForestFlow](./docs/buildconfig.md#building-forestflow)
         - [Create JAR without Kubernetes dependencies](./docs/buildconfig.md#create-jar-without-kubernetes-dependencies)
         - [Create a JAR with Kubernetes dependencies](./docs/buildconfig.md#create-a-jar-with-kubernetes-dependencies)
      - [ForestFlow Configuration](./docs/buildconfig.md#forestflow-configuration)
      - [Creating an OCI-compliant Image](./docs/buildconfig.md#creating-an-oci-compliant-image)
  - [Inference](./docs/inference.md)
      - [Servable implementation interface support matrix](./docs/inference.md#servable-implementation-interface-support-matrix)
      - [Inference - Using the BASIC REST API](./docs/inference.md#inference---using-the-basic-rest-api)
      - [Inference - Using the GraphPipe API](./docs/inference.md#inference---using-the-graphpipe-api)
  - [Concepts](./docs/concepts.md)
      - [A ForestFlow Cluster](./docs/concepts.md#a-forestflow-cluster)
          - [Servable](./docs/concepts.md#servable)
          - [Fully Qualified Release Version (FQRV)](./docs/concepts.md#fully-qualified-release-version-fqrv)
      - [Servable (Model) Deployment](./docs/concepts.md#servable-model-deployment)
          - [Creating a Contract](./docs/concepts.md#creating-a-contract)
          - [Creating a Servable](./docs/concepts.md#creating-a-servable)
  - [Contributing to ForestFlow](#contributing)

# Overview
## Why ForestFlow?
Continuous deployment and lifecycle management of Machine Learning/Deep Learning models is currently widely accepted as a primary bottleneck for gaining value out of ML projects.

We first set out to find a solution to deploy our own models. The model server implementations we found were either proprietary, closed-source solutions or had too many limitations in what we wanted to achieve.
The main concerns for creating ForestFlow can be summarized as:
   - We wanted to reduce friction between our data science, engineering and operations teams
   - We wanted to give data scientists the flexibility to use the tools they wanted (H2O, TensorFlow, Spark export to PFA etc..)
   - We wanted to automate certain lifecycle management aspects of model deployments like automatic performance or time-based routing and retirement of stale models
   - We wanted a model server that allows easy A/B testing, Shadow (listen only) deployments and and Canary deployments. This allows our Data Scientists to experiment with real production data without impacting production and using the same tooling they would when deployment to production. 
   - We wanted something that was easy to deploy and scale for different deployment scenarios (on-prem local data center single instance, cluster of instances, Kubernetes managed, Cloud native etc..)
   - We wanted the ability to treat inference requests as a stream and log predictions as a stream. This allows us to test new models against a stream of older infer requests.
   - We wanted to avoid the "super-hero" data scientist that knows how to dockerize an application, apply the science, build an API and deploy to production. This does not scale well and is difficult to support and maintain.
   - Most of all, we wanted repeatability. We didn't want to re-invent the wheel once we had support for a specific framework. 

## Model Deployment
For model deployment, ForestFlow supports models described via [MLFLow Model](https://mlflow.org/docs/latest/models.html) format which allows for different flavors i..e, frameworks & storage formats.

ForestFlow also supports a BASIC REST API for model deployment as well that mimics the MLFLow Model format but does not require it.

## Inference
For inference, we’ve adopted a similar approach. ForestFlow standardizes on the [GraphPipe](https://oracle.github.io/graphpipe) [API specification](https://oracle.github.io/graphpipe/#/guide/user-guide/spec) for inference while also providing a basic REST API option as well for maximum flexibility.

Relying on standards, for example using GraphPipe’s specification means immediate availability of client libraries in a variety of languages that already support working with ForestFlow; see [GraphPipe clients](https://oracle.github.io/graphpipe/#/guide/clients/overview).

## Currently Supported model formats (MLFLow flavors)
 - H2O - Mojo Model
 - TensorFlow - Planned
 - PFA - Planned
 - Spark ML Models and Pipelines via [Aardpfark](https://github.com/CODAIT/aardpfark) and PFA - Planned

Go to the [Quick Start Guide](./docs/quickstart.md) to get started then dive a little deeper and 
learn about [ForestFlow Concepts](./docs/concepts.md) and how you can 
[tailor](./docs/concepts.md#servable-model-deployment) it to fit your own use-cases.


# Contributing
While ForestFlow has already delivered tremendous value for us in production, it's still in early phases of development
as there are plenty of features we have planned and this continues to evolve at a rapid pace. 
We appreciate and consistently, make use of and, contribute open source projects back to the community. 
We realize the problems we're facing aren't unique to us so we welcome feedback, ideas and contributions from the 
community to help develop our roadmap and implementation of ForestFlow.

Check out [Contribution Guide](./docs/contributing.md) for more details on contributing to ForestFlow.

