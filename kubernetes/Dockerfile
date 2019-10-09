#Dockerfile for ForestFlow Serving image

FROM docker.anim.dreamworks.com/dwadba/dbaas-base-jdk:8u161-centos7.4.1708

#  wget ${SS_PROXY_URL}/Dockerfiles/dwadba/ff-serving/${FORESTFLOW_SERVING_VERSION}--${BUILD_VERSION}/forestflow-${FORESTFLOW_SERVING_VERSION}.jar -O /srv/forestflow/forestflow-${FORESTFLOW_SERVING_VERSION}.jar

LABEL net.dreamworks.db.forestflow-serving.version=0.0.1 \
      net.dreamworks.db.forestflow-serving.build_version=0.1

MAINTAINER Venkata Ahmad Alkilani <ahmad.alkilani@dreamworks.com>

ENV FORESTFLOW_SERVING_VERSION="0.0.1-SNAPSHOT" \
    BUILD_VERSION="0.1" \
    SS_PROXY_URL="http://ss-proxy.anim.dreamworks.com/v1/AUTH_dwadba"

RUN \
  mkdir /srv/forestflow && \
  yum install -y git && \
  curl -s https://packagecloud.io/install/repositories/github/git-lfs/script.rpm.sh | os=centos dist=7 bash && \
  yum install -y git-lfs && \
  git lfs install

ADD src/entrypoint.sh /
ADD src/forestflow-${FORESTFLOW_SERVING_VERSION}.jar /srv/forestflow/

ENTRYPOINT ["/entrypoint.sh"]
CMD ["ff-serving"]
