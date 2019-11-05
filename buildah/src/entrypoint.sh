#!/bin/bash

set -xe

exec java -Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=9999 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false ${JVM_OPTS} -jar ${FORESTFLOW_BIN}/${FORESTFLOW_FILENAME}
