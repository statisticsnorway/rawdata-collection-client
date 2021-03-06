#!/usr/bin/env bash

WORKDIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

#
# In prodsone create 'rawdata-cli-config.env' with SUDO and NETWORK
#
if [ -f "$WORKDIR/rawdata-cli-config.env" ]; then
  set -a
  source "$WORKDIR/rawdata-cli-config.env"
  set +a
fi

RELEASE_IMAGE="statisticsnorway/rawdata-collection-client:0.1"
LOCAL_IMAGE="rawdata-collection-client:dev"
CONTAINER_IMAGE="$LOCAL_IMAGE"

if [ -z "$LOCAL_CONF_FOLDER" ]; then
  LOCAL_CONF_FOLDER="$WORKDIR/conf"
fi
if [ -z "$PROPERTY_FILE" ]; then
  PROPERTY_FILE="application-defaults.properties"
fi
LOCAL_DATABASE_VOLUME="source_database"
LOCAL_AVRO_VOLUME="avro_folder"
DEBUG_LOGGING=false

# Show usage
usage() {
  if [ -n "$ACTION" ]; then
    return
  fi

  cat <<EOP

Variables:

  ACTION               action                     (mandatory)
  TARGET               target                     (optional)
  RAWDATA_SECRET_FILE  full file path             (optional: ENCRYPTION_KEY & ENCRYPTION_SALT)
  BUCKET_SA_FILE       gcs service account json   (optional: json full filepath)
  BUCKET_NAME          gcs bucket name            (mandatory)
  TOPIC_NAME           rawdata topic              (mandatory)
  PROPERTY_FILE        filename under './conf'    (mandatory)
  SPECIFICATION_FILE   filename under './spec'    (optional)
  LOCAL_SECRET_FOLDER  local secret mount folder  (mandatory)
  LOCAL_CONF_FOLDER    local conf mount folder    (mandatory)
  LOCAL_SPEC_FOLDER    local spec mount folder    (optional)
  LOCAL_SOURCE_FOLDER  local import folder        (mandatory)
  CSV_FILES            import csv files           (optional)
  LOCAL_AVRO_FOLDER    local avro export folder   (optional: overrides rawdata client provider to filesystem)

Example:

  ACTION=action TARGET=target $0

EOP

  exit 0
}

log() {
  if [ "$DEBUG_LOGGING" = true ]; then
    echo "$1"
  fi
}

validate() {
  if [ -n "$LOCAL_AVRO_FOLDER" ]; then
    USE_LOCAL_CONFIGURATION=true
  fi

  LOCAL_RAWDATA_SECRET_FILEPATH="$LOCAL_SECRET_FOLDER/$RAWDATA_SECRET_FILE"
  if [ -z "$USE_LOCAL_CONFIGURATION" ] && [ -n "$LOCAL_RAWDATA_SECRET_FILEPATH" ] && [ ! -f "$LOCAL_RAWDATA_SECRET_FILEPATH" ]; then
    echo "Secret file is NOT found!"
    exit 0
  fi

  if [ -z "$USE_LOCAL_CONFIGURATION" ] && ([ -z "$LOCAL_SECRET_FOLDER" ] || [ ! -d "$LOCAL_SECRET_FOLDER" ]); then
    echo "Local secret directory NOT found!"
    exit 0
  fi

  LOCAL_BUCKET_SA_FILEPATH="$LOCAL_SECRET_FOLDER/$BUCKET_SA_FILE"
  if [ -z "$USE_LOCAL_CONFIGURATION" ] && [ -n "$LOCAL_BUCKET_SA_FILEPATH" ] && [ ! -f "$LOCAL_BUCKET_SA_FILEPATH" ]; then
    echo "Service account json file NOT found!"
    exit 0
  fi

  if [ -z "$USE_LOCAL_CONFIGURATION" ] && [ -z "$BUCKET_NAME" ]; then
    echo "Bucket name is NOT set!"
    exit 0
  fi

  if [ -z "$TOPIC_NAME" ]; then
    echo "Rawdata topic is NOT set!"
    exit 0
  fi

  PROPERTY_FULL_FILE_PATH="$WORKDIR/conf/$PROPERTY_FILE"
  if [ -z "$PROPERTY_FULL_FILE_PATH" ] || [ ! -f "$PROPERTY_FULL_FILE_PATH" ]; then
    echo "Property file NOT found!"
    exit 0
  fi

  if [ -n "$LOCAL_CONF_FOLDER" ] && [ ! -d "$LOCAL_CONF_FOLDER" ]; then
    echo "Local conf directory '$LOCAL_CONF_FOLDER' NOT found!"
    exit 0
  fi

  if [ -n "$LOCAL_SPEC_FOLDER" ] && [ ! -d "$LOCAL_SPEC_FOLDER" ]; then
    echo "Local conf directory '$LOCAL_SPEC_FOLDER' NOT found!"
    exit 0
  fi

  if [ -n "$LOCAL_SOURCE_FOLDER" ] && [ ! -d "$LOCAL_SOURCE_FOLDER" ]; then
    echo "Local source import directory NOT found!"
    exit 0
  fi
}

#
# Set bucket encryption keys
#
evaluateRawdataSecrets() {
  if [[ -f "$LOCAL_RAWDATA_SECRET_FILEPATH" ]]; then
    echo "Set encryption environment variables from: $LOCAL_RAWDATA_SECRET_FILEPATH"
    set -a
    source "$LOCAL_RAWDATA_SECRET_FILEPATH"
    set +a
  fi
}

#
# Read property file and expand as docker environment variables
#
evaluateDockerEnvironmentVariables() {
  DOCKER_ENV_VARS=""

  if [ -n "$TARGET" ]; then
    DOCKER_ENV_VARS="$DOCKER_ENV_VARS -e BONG_target=$TARGET"
  fi

  if [ -n "$ENCRYPTION_KEY" ] && [ -n "$ENCRYPTION_SALT" ]; then
    DOCKER_ENV_VARS="$DOCKER_ENV_VARS -e BONG_target.rawdata.encryptionKey=$ENCRYPTION_KEY -e BONG_target.rawdata.encryptionSalt=$ENCRYPTION_SALT "
  fi

  if [ -n "$PROXY_HOST" ] && [ -n "$PROXY_PORT" ]; then
    DOCKER_PROXY_VARS="-e PROXY_HTTPS_HOST=$PROXY_HOST -e PROXY_HTTPS_PORT=$PROXY_PORT"
    DOCKER_ENV_VARS="$DOCKER_ENV_VARS $DOCKER_PROXY_VARS"
  fi

  if [ -n "$BUCKET_SA_FILE" ]; then
    CONTAINER_BUCKET_SA_FILEPATH="/secret/$BUCKET_SA_FILE"
    DOCKER_ENV_VARS="$DOCKER_ENV_VARS -e BONG_target.gcs.service-account.key-file=$CONTAINER_BUCKET_SA_FILEPATH "
  fi

  while read -r line; do
    ENV_VAR=$(eval echo "$line")
    if [[ ! "$ENV_VAR" == "#*" ]] && [[ -n "$ENV_VAR" ]]; then
      log "ENV_VAR: $ENV_VAR"
      DOCKER_ENV_VARS="$DOCKER_ENV_VARS -e BONG_${ENV_VAR} "
    fi
  done <"$WORKDIR/conf/$PROPERTY_FILE"

  # if local avro folder is set, use filesystem rawdata provider
  DOCKER_VOLUME_VARS=""
  if [ -n "$LOCAL_AVRO_FOLDER" ]; then
    DOCKER_ENV_VARS="$DOCKER_ENV_VARS -e BONG_target.rawdata.client.provider=filesystem"
    DOCKER_VOLUME_VARS="$DOCKER_VOLUME_VARS -v $LOCAL_AVRO_FOLDER:/export:Z "
  fi

  if [ -n "$DRY_RUN" ]; then
    DOCKER_ENV_VARS="$DOCKER_ENV_VARS -e BONG_source.csv.dryRun=$DRY_RUN"
  fi

  log "DOCKER_ENV_VARS: $DOCKER_ENV_VARS"
}

#
# Create Source Database and Target Avro docker volumes
#
createDockerVolumes() {
  LOCAL_DATABASE_VOLUME_EXISTS="$($SUDO docker volume ls -f "name=$LOCAL_DATABASE_VOLUME" -q)"
  if [ -z "$LOCAL_DATABASE_VOLUME_EXISTS" ]; then
    echo "Create Volume: $LOCAL_DATABASE_VOLUME"
    $SUDO docker volume create "$LOCAL_DATABASE_VOLUME"
  fi

  LOCAL_AVRO_VOLUME_EXISTS="$($SUDO docker volume ls -f "name=$LOCAL_AVRO_VOLUME" -q)"
  if [ -z "$LOCAL_AVRO_VOLUME_EXISTS" ]; then
    echo "Create Volume: $LOCAL_AVRO_VOLUME"
    $SUDO docker volume create "$LOCAL_AVRO_VOLUME"
  fi
}

test_run() {
  if [ "$ACTION" = "test-gcs-write" ]; then
    $SUDO docker run -it ${NETWORK} \
      -e BONG_action="$ACTION" \
      -e BONG_target="$TARGET" \
      ${DOCKER_ENV_VARS} \
      -v "$LOCAL_SECRET_FOLDER":/secret:Z \
      ${DOCKER_VOLUME_VARS} \
      ${CONTAINER_IMAGE}
    exit 0
  fi
}

run() {
  set +x
  $SUDO docker run -it ${NETWORK} \
    -e BONG_action="$ACTION" \
    ${DOCKER_ENV_VARS} \
    -v "$LOCAL_SECRET_FOLDER":/secret:Z \
    -v "$LOCAL_CONF_FOLDER":/conf:Z \
    -v "$LOCAL_SPEC_FOLDER":/spec:Z \
    -v "$LOCAL_SOURCE_FOLDER":/source:Z \
    -v "$LOCAL_DATABASE_VOLUME":/database \
    -v "$LOCAL_AVRO_VOLUME":/avro \
    ${DOCKER_VOLUME_VARS} \
    ${CONTAINER_IMAGE}
}

#
# Execute
#
usage
validate
evaluateRawdataSecrets
evaluateDockerEnvironmentVariables
createDockerVolumes
test_run
run
