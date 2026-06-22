#!/usr/bin/env bash

# Shared PocketHive application image manifest.
# Source this file from bash build/deploy tooling; do not execute it directly.

POCKETHIVE_IMAGE_NAMES=()
POCKETHIVE_JAR_EXTRA_MODULES=(tools/scenario-templating-check)

declare -Ag POCKETHIVE_IMAGE_SERVICE=()
declare -Ag POCKETHIVE_IMAGE_MODULE=()
declare -Ag POCKETHIVE_IMAGE_DOCKERFILE=()
declare -Ag POCKETHIVE_IMAGE_CONTEXT=()
declare -Ag POCKETHIVE_IMAGE_TARGET=()
declare -Ag POCKETHIVE_IMAGE_KIND=()
declare -Ag POCKETHIVE_SERVICE_IMAGE=()
declare -Ag POCKETHIVE_MODULE_IMAGE=()

register_pockethive_image() {
  local image="$1"
  local service="$2"
  local module="$3"
  local dockerfile="$4"
  local context="$5"
  local target="$6"
  local kind="$7"

  POCKETHIVE_IMAGE_NAMES+=("${image}")
  POCKETHIVE_IMAGE_SERVICE["${image}"]="${service}"
  POCKETHIVE_IMAGE_MODULE["${image}"]="${module}"
  POCKETHIVE_IMAGE_DOCKERFILE["${image}"]="${dockerfile}"
  POCKETHIVE_IMAGE_CONTEXT["${image}"]="${context}"
  POCKETHIVE_IMAGE_TARGET["${image}"]="${target}"
  POCKETHIVE_IMAGE_KIND["${image}"]="${kind}"

  if [[ -n "${service}" ]]; then
    POCKETHIVE_SERVICE_IMAGE["${service}"]="${image}"
  fi
  if [[ -n "${module}" ]]; then
    POCKETHIVE_MODULE_IMAGE["${module}"]="${image}"
  fi
}

register_pockethive_image jvm-base "" "" docker/base/Dockerfile docker/base "" base
register_pockethive_image log-aggregator log-aggregator log-aggregator-service log-aggregator-service/Dockerfile.runtime . "" runtime
register_pockethive_image auth-service auth-service auth-service auth-service/Dockerfile.runtime . "" runtime
register_pockethive_image scenario-manager scenario-manager scenario-manager-service scenario-manager-service/Dockerfile.runtime . "" runtime
register_pockethive_image network-proxy-manager network-proxy-manager network-proxy-manager-service network-proxy-manager-service/Dockerfile.runtime . "" runtime
register_pockethive_image orchestrator orchestrator orchestrator-service orchestrator-service/Dockerfile.runtime . "" runtime
register_pockethive_image tcp-mock-server tcp-mock-server tcp-mock-server tcp-mock-server/Dockerfile.runtime . "" runtime
register_pockethive_image network-proxy-haproxy haproxy "" network-proxy-haproxy/Dockerfile network-proxy-haproxy "" static
register_pockethive_image ui ui "" ui-v2/Dockerfile . "" ui
register_pockethive_image swarm-controller swarm-controller swarm-controller-service Dockerfile.bees.local . swarm-controller worker
register_pockethive_image generator generator generator-service Dockerfile.bees.local . generator worker
register_pockethive_image request-builder request-builder request-builder-service Dockerfile.bees.local . request-builder worker
register_pockethive_image http-sequence http-sequence http-sequence-service Dockerfile.bees.local . http-sequence worker
register_pockethive_image db-query db-query db-query-service Dockerfile.bees.local . db-query worker
register_pockethive_image moderator moderator moderator-service Dockerfile.bees.local . moderator worker
register_pockethive_image processor processor processor-service Dockerfile.bees.local . processor worker
register_pockethive_image postprocessor postprocessor postprocessor-service Dockerfile.bees.local . postprocessor worker
register_pockethive_image clearing-export clearing-export clearing-export-service Dockerfile.bees.local . clearing-export worker
register_pockethive_image trigger trigger trigger-service Dockerfile.bees.local . trigger worker

pockethive_all_image_names() {
  printf '%s\n' "${POCKETHIVE_IMAGE_NAMES[@]}"
}

pockethive_image_exists() {
  local image="$1"
  [[ -n "${POCKETHIVE_IMAGE_KIND[$image]:-}" ]]
}

pockethive_service_exists() {
  local service="$1"
  [[ -n "${POCKETHIVE_SERVICE_IMAGE[$service]:-}" ]]
}

pockethive_module_exists() {
  local module="$1"
  [[ -n "${POCKETHIVE_MODULE_IMAGE[$module]:-}" ]]
}

pockethive_all_image_services() {
  local image service
  for image in "${POCKETHIVE_IMAGE_NAMES[@]}"; do
    service="${POCKETHIVE_IMAGE_SERVICE[$image]}"
    if [[ -n "${service}" ]]; then
      printf '%s\n' "${service}"
    fi
  done
}

pockethive_all_java_modules() {
  local image module
  for image in "${POCKETHIVE_IMAGE_NAMES[@]}"; do
    module="${POCKETHIVE_IMAGE_MODULE[$image]}"
    if [[ -n "${module}" ]]; then
      printf '%s\n' "${module}"
    fi
  done
  printf '%s\n' "${POCKETHIVE_JAR_EXTRA_MODULES[@]}"
}
