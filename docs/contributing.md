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
# Contributing

Before contributing code to ForestFlow, we ask that you sign a Contributor License Agreement (CLA) and submit it to 
DreamWorks Animation.

<!--
 - For Corporations, please use [..resouces/Corporate CLA](https://github.com/dreamworksanimation/ForestFlow/tree/master/resources/ForestFlow-Corporate-CLA.pdf)
 - For individuals, please use [..resources/Individual CLA](https://github.com/dreamworksanimation/ForestFlow/tree/master/resources/ForestFlow-Individual-CLA.pdf)
-->

 - For Corporations, please use [Corporate CLA](_static/ForestFlow-Corporate-CLA.pdf "Corporate CLA")
 - For individuals, please use [Individual CLA](_static/ForestFlow-Individual-CLA.pdf "Corporate CLA")

Directions for submission are in CLA.

## Style Guide
Please follow coding convention and style in each file and in each library when adding new files.
Please also refer to the overall [project guidelines](./project-guidelines.md)

## Pull Requests
 - All development on ForestFlow should happen in a separate branch with 'develop' as the base branch.
 
 - We recommend positing issues on GitHub for features or bug fixes that you intent to work on before beginning any
  development. This keeps development open and conversations and feedback more collaborative.
  
 - Pull requests should be rebased on the latest dev commit and squashed to as few logical commits as possible, preferably
one. Each commit should pass tests in isolation.

 - Please make pull requests as small and atomic as possible. We will only consider pull requests that have an 
 associated GitHub ticket.
 
 ## Git Workflow
 
1. Raise a new GitHub issue if one does not already exist. Mention that you intend to work on it.
1. Use GitHub to Fork your own private repository
1. Clone your forked repo locally
1. You can optionally add DreamWork's repo as upstream to make it easier to update your remote and local repos with 
the latest changes

    ```bash
    # cd into your local git copy
    cd ForestFlow
    git remote add upstream https://github.com/dreamworksanimation/ForestFlow.git
    ```
1. Fetch latest from upstream
     ```bash
     git fetch upstream
     ```
 
1. Create a new branch for each feature and provide a descriptive name, preferably the GitHub ticket number.
    This will checkout a new branch and create it simultaneously:
    ```bash
    git checkout -b github-1234 upstream/develop
     ```

1. Write your feature code and update any documentation under ./docs and test it builds without errors

1. Commit and push your code branch
    ```bash
    git commit -m "Descriptive message here"
    git push origin github-1234
     ```
1. Create a pull request targeting the develop branch
