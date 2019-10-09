#!/usr/bin/env bash

podman_container=$(podman run -d \
-e "KAFKA_BOOTSTRAP_SERVERS_CONFIG=[FILL ME]" \
-e "KAFKA_PREDICTION_LOGGER_BASIC_TOPIC_CONFIG=[FILL ME]" \
-e "KAFKA_PREDICTION_LOGGER_GRAPHPIPE_TOPIC_CONFIG=[FILL ME]" \
\
-e "AKKA_PERSISTENCE_JOURNAL_PLUGIN=jdbc-journal" \
-e "AKKA_PERSISTENCE_SNAPSHOT_STORE_PLUGIN=jdbc-snapshot-store" \
-e "POSTGRES_HOST_CONFIG=[FILL ME]" \
-e "POSTGRES_USER_CONFIG=[FILL ME]" \
-e "POSTGRES_PASSWORD_CONFIG=[FILL ME]" \
-e "POSTGRES_DATABASE_CONFIG=[FILL ME]" \
\
-e "APPLICATION_ENVIRONMENT_CONFIG=local" \
--net=host \
--name=ff-serving localhost/com.dreamworks.forestflow-serving:0.2.1)
podman logs -f ${podman_container}
