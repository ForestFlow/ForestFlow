#!/usr/bin/env bash

# Set additional local variables
builddir=$(dirname "${BASH_SOURCE[0]}")
source ${builddir}/vars

mvn clean package -f ${builddir}/../pom.xml -P local -Dforestflow-latest.version=$FORESTFLOW_VERSION
