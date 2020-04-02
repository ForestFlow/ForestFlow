#!/bin/bash

set -xe
# Set additional local variables
builddir=$(dirname "${BASH_SOURCE[0]}")
source ${builddir}/vars

export APPLICATION_ENVIRONMENT_CONFIG=local
java -Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=9999 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false -jar ${builddir}/serving/target/forestflow-serving-${FORESTFLOW_VERSION}.jar
