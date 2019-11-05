#!/bin/bash

set -ex

# Set local variables
os_family="centos"
os_version="7.6.1810"
os_short_version=$(echo ${os_version} | cut -d'.' -f 1-2)
os_major_version=$(echo ${os_version} | cut -d'.' -f 1)
from_image="docker.io/${os_family}:${os_version}"

user="fflow"
group="fflow"
uid="30080"
gid="30080"
dumbinit_version="1.2.2"
jdk_type="openjdk"
jdk_version="11.0.2.7-0.el7_6"
jdk_major_version="11"

# Start build container and load parent image environment variables into local environment
container=$(buildah from --tls-verify=false docker://${from_image})

# Load parent image vars
for env in $(buildah inspect -f "{{range .OCIv1.Config.Env}}{{println .}}{{end}}" $from_image); do
  eval $env
done

# Set additional local variables
builddir=$(dirname "${BASH_SOURCE[0]}")
source ${builddir}/vars

# Configure image labels
buildah config --label net.dreamworks.db.forestflow.version=${FORESTFLOW_VERSION} ${container}

# Configure image environment labels
buildah config --env OS_FAMILY=${os_family} ${container}
buildah config --env OS_VERSION=${os_version} ${container}
buildah config --env OS_SHORT_VERSION=${os_short_version} ${container}
buildah config --env OS_MAJOR_VERSION=${os_major_version} ${container}

buildah config --env FORESTFLOW_VERSION=${FORESTFLOW_VERSION} ${container}
buildah config --env FORESTFLOW_FILENAME=${FORESTFLOW_FILENAME} ${container}
buildah config --env FORESTFLOW_HOME=${FORESTFLOW_HOME} ${container}
buildah config --env FORESTFLOW_BIN=${FORESTFLOW_BIN} ${container}
buildah config --env FORESTFLOW_DATA=${FORESTFLOW_DATA} ${container}
buildah config --env PATH=${PATH}:${FORESTFLOW_BIN}:${PATH_UTILS} ${container}

# Configure application environment-specific environment variables
buildah config --env LOCAL_WORKING_DIR_CONFIG=${FORESTFLOW_DATA} ${container}

# Configure default image working directory, entrypoint, and command
buildah config --workingdir $PATH_HOME ${container}
buildah config --entrypoint '["dumb-init"]' ${container}
buildah config --cmd "${PATH_UTILS}/entrypoint.sh" ${container}

# Create user and group
buildah run ${container} groupadd -g $gid -r $group
buildah run ${container} useradd -s /bin/bash -r -g $group -u $uid -m -d $PATH_HOME $user

# Install base tools
buildah run ${container} yum install -y bind-utils git iputils net-tools nmap-ncat wget
buildah run ${container} yum autoremove -y
buildah run ${container} yum install -y hostname

# Install ForestFlow requirements
buildah run ${container} sh -c "curl -s https://packagecloud.io/install/repositories/github/git-lfs/script.rpm.sh | bash"
buildah run ${container} yum install -y git-lfs
buildah run ${container} git lfs install
buildah	run	${container} yum install -y java-${jdk_major_version}-openjdk-devel

# Cleanup yum cache
buildah run ${container} yum clean all
buildah run ${container} rm -rf /var/cache/yum


# Create directories
buildah run ${container} mkdir -p $PATH_UTILS
buildah run ${container} mkdir -p $PATH_CONFIG
buildah run ${container} mkdir -p $FORESTFLOW_DATA
buildah run ${container} mkdir -p $FORESTFLOW_BIN

buildah run  ${container} chmod 755 $PATH_UTILS

# Install dumb-init process manager
buildah add ${container} https://github.com/Yelp/dumb-init/releases/download/v${dumbinit_version}/dumb-init_${dumbinit_version}_amd64 ${PATH_UTILS}/dumb-init
buildah run ${container} chmod +x ${PATH_UTILS}/dumb-init

# Setup ForestFlow JAR
buildah add ${container} ${builddir}/../serving/target/${FORESTFLOW_FILENAME} ${FORESTFLOW_BIN}/${FORESTFLOW_FILENAME}

# Entrypoint
buildah add ${container} ${builddir}/src/entrypoint.sh ${PATH_UTILS}/entrypoint.sh

# Access
buildah run ${container} chown -R $gid:$uid ${PATH_HOME}

# Commit container
buildah commit ${container} com.dreamworks.forestflow-serving:${FORESTFLOW_VERSION}

# Cleanup
buildah umount ${container}
buildah rm ${container}
