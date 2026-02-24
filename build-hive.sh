#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

ALL_SERVICES=(rabbitmq log-aggregator scenario-manager orchestrator tcp-mock-server ui ui-v2 docs-site prometheus grafana loki wiremock pushgateway redis redis-commander swarm-controller generator request-builder http-sequence moderator processor postprocessor clearing-export trigger)
declare -A DURATIONS=()
TIMING_ORDER=(clean build_base maven_package stage_artifacts docker_build_workers docker_build compose_up restart)
BUILD_START_TIME=0
JAR_MODULES=(
  log-aggregator-service
  scenario-manager-service
  orchestrator-service
  tcp-mock-server
  swarm-controller-service
  generator-service
  request-builder-service
  http-sequence-service
  moderator-service
  processor-service
  postprocessor-service
  clearing-export-service
  trigger-service
)

declare -A MODULE_TO_SERVICE=(
  ["log-aggregator-service"]="log-aggregator"
  ["scenario-manager-service"]="scenario-manager"
  ["orchestrator-service"]="orchestrator"
  ["tcp-mock-server"]="tcp-mock-server"
  ["swarm-controller-service"]="swarm-controller"
  ["generator-service"]="generator"
  ["request-builder-service"]="request-builder"
  ["http-sequence-service"]="http-sequence"
  ["moderator-service"]="moderator"
  ["processor-service"]="processor"
  ["postprocessor-service"]="postprocessor"
  ["clearing-export-service"]="clearing-export"
  ["trigger-service"]="trigger"
)

declare -A SERVICE_TO_MODULE=(
  ["log-aggregator"]="log-aggregator-service"
  ["scenario-manager"]="scenario-manager-service"
  ["orchestrator"]="orchestrator-service"
  ["tcp-mock-server"]="tcp-mock-server"
  ["swarm-controller"]="swarm-controller-service"
  ["generator"]="generator-service"
  ["request-builder"]="request-builder-service"
  ["http-sequence"]="http-sequence-service"
  ["moderator"]="moderator-service"
  ["processor"]="processor-service"
  ["postprocessor"]="postprocessor-service"
  ["clearing-export"]="clearing-export-service"
  ["trigger"]="trigger-service"
)

LOCAL_ARTIFACTS_DIR="${LOCAL_ARTIFACTS_DIR:-.local-jars}"
COMPOSE_FILES=(-f docker-compose.yml)
MAVEN_CLI_OPTS="${MAVEN_CLI_OPTS:-}"
RUNTIME_IMAGE="${POCKETHIVE_RUNTIME_IMAGE:-pockethive-jvm-base:latest}"

SKIP_TESTS=false
MODULE_FILTER=()
SERVICE_FILTER=()
RESTART_TARGETS=()
CLEAN_STACK=false
ONLY_CLEAN=true
MODULES_TO_BUILD=()
SERVICES_TO_BUILD=()
SYNC_SCENARIOS_ONLY=false
PRUNE_IMAGES=false

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Options:
  --quick                 Skip tests during the Maven build (-DskipTests).
  --module <name>         Rebuild a given module (repeatable).
  --service <name>        Rebuild a docker-compose service (repeatable).
  --clean                 Stop the stack and remove stale containers before building.
  --prune-images          Remove local PocketHive application images before rebuilding (orchestrator, scenario-manager, bees, etc).
  --sync-scenarios        Copy local scenario files into the running Scenario Manager container and trigger a reload (no Maven or Docker build).
  --restart <service>     Restart a running service after the build completes (repeatable).
  --restart-all           Restart all compose services after the build completes.
  --help                  Show this help.

Behaviour:
  • No flags → local Maven build with tests, rebuild all services, start the full stack (does NOT run 'mvn clean').
  • --quick  → skips tests for faster iterations.
  • --service generator --module orchestrator-service → rebuild specific services.

Environment:
  LOCAL_ARTIFACTS_DIR   Directory for staged jars (default: ${LOCAL_ARTIFACTS_DIR}).
  POCKETHIVE_RUNTIME_IMAGE  Tag for the shared JVM base image (default: ${RUNTIME_IMAGE}).
  MAVEN_CLI_OPTS        Extra Maven flags appended to the build command.
USAGE
}

compose_cmd() {
  docker compose "${COMPOSE_FILES[@]}" "$@"
}

compose_build_services() {
  local services=("$@")
  (( ${#services[@]} == 0 )) && return

  local built_any=false
  for svc in "${services[@]}"; do
    case "$svc" in
      log-aggregator)
        echo "Building log-aggregator image from log-aggregator-service/Dockerfile.runtime"
        docker build \
          -f log-aggregator-service/Dockerfile.runtime \
          --build-arg RUNTIME_IMAGE="${RUNTIME_IMAGE}" \
          -t log-aggregator:latest .
        built_any=true
        ;;
      scenario-manager)
        echo "Building scenario-manager image from scenario-manager-service/Dockerfile.runtime"
        docker build \
          -f scenario-manager-service/Dockerfile.runtime \
          --build-arg RUNTIME_IMAGE="${RUNTIME_IMAGE}" \
          -t scenario-manager:latest .
        built_any=true
        ;;
      orchestrator)
        echo "Building orchestrator image from orchestrator-service/Dockerfile.runtime"
        docker build \
          -f orchestrator-service/Dockerfile.runtime \
          --build-arg RUNTIME_IMAGE="${RUNTIME_IMAGE}" \
          -t orchestrator:latest .
        built_any=true
        ;;
      tcp-mock-server)
        echo "Building tcp-mock-server image from tcp-mock-server/Dockerfile.runtime"
        docker build \
          -f tcp-mock-server/Dockerfile.runtime \
          --build-arg RUNTIME_IMAGE="${RUNTIME_IMAGE}" \
          -t tcp-mock-server:latest .
        built_any=true
        ;;
      ui)
        echo "Building ui image from ui/Dockerfile"
        docker build \
          -f ui/Dockerfile \
          --build-arg VITE_STOMP_READONLY_USER="${VITE_STOMP_READONLY_USER:-ph-observer}" \
          --build-arg VITE_STOMP_READONLY_PASSCODE="${VITE_STOMP_READONLY_PASSCODE:-ph-observer}" \
          -t ui:latest .
        built_any=true
        ;;
      ui-v2)
        echo "Building ui-v2 image from ui-v2/Dockerfile"
        docker build \
          -f ui-v2/Dockerfile \
          -t ui-v2:latest .
        built_any=true
        ;;
      *)
        # Infrastructure / third-party services (rabbitmq, prometheus, grafana, etc.)
        # use upstream images and are not built locally here.
        ;;
    esac
  done

  if ! $built_any; then
    echo "No application services selected for image build; skipping docker build step."
  fi
}

compose_up_services() {
  local services=("$@")
  (( ${#services[@]} == 0 )) && return
  compose_cmd up -d "${services[@]}"
}

compose_up_full_stack() {
  compose_cmd up -d
}

dedupe_list() {
  local -n result=$1
  shift
  declare -A seen=()
  for svc in "$@"; do
    [[ -z "$svc" ]] && continue
    if [[ -z "${seen[$svc]:-}" ]]; then
      seen["$svc"]=1
      result+=("$svc")
    fi
  done
}

clean_stack() {
  echo "Stopping PocketHive stack and removing stray bees..."
  mapfile -t bee_containers < <(docker ps -a --format '{{.ID}}\t{{.Names}}' | awk -F '\t' '$2 ~ /-bee-/')
  for entry in "${bee_containers[@]}"; do
    IFS=$'\t' read -r cid cname <<<"$entry"
    if [[ -n "$cid" ]]; then
      echo " - Removing bee container ${cname} (${cid})"
      docker rm -f "$cid" >/dev/null || echo "   (failed to remove ${cid})"
    fi
  done
  echo "Running docker compose down --remove-orphans..."
  compose_cmd down --remove-orphans || true

  if $PRUNE_IMAGES; then
    echo "Pruning local PocketHive images..."
    # Target only images built by this repo: core services and bees.
    mapfile -t ph_images < <(docker images --format '{{.Repository}} {{.ID}}' | awk '
      $1 ~ /^(orchestrator|scenario-manager|log-aggregator|tcp-mock-server|ui|swarm-controller|generator|request-builder|http-sequence|moderator|processor|postprocessor|clearing-export|trigger|pockethive-)/ { print $2 }')
    for img in "${ph_images[@]}"; do
      if [[ -n "$img" ]]; then
        echo " - Removing image ${img}"
        docker rmi -f "$img" >/dev/null 2>&1 || echo "   (failed to remove image ${img})"
      fi
    done
    echo "Pruning dangling images created by previous builds..."
    docker image prune -f --filter "dangling=true" >/dev/null 2>&1 || true
  fi
}

require_tools() {
  if ! command -v docker >/dev/null 2>&1; then
    echo "Docker is required but missing from PATH." >&2
    exit 1
  fi
  if ! docker compose version >/dev/null 2>&1; then
    echo "Docker Compose V2 is required (docker compose)." >&2
    exit 1
  fi
  if ! command -v mvn >/dev/null 2>&1; then
    echo "Maven is required for local builds." >&2
    exit 1
  fi
}

build_base_image() {
  echo "Building shared JVM runtime image (${RUNTIME_IMAGE})"
  docker build -f docker/base/Dockerfile -t "${RUNTIME_IMAGE}" docker/base
}

resolve_module_token() {
  local token="${1,,}"
  if [[ -n "${MODULE_TO_SERVICE[$token]:-}" ]]; then
    echo "$token"
    return
  fi
  if [[ -n "${SERVICE_TO_MODULE[$token]:-}" ]]; then
    echo "${SERVICE_TO_MODULE[$token]}"
    return
  fi
  echo "Unknown module/service: $1" >&2
  exit 1
}

determine_targets() {
  if [[ ${#MODULE_FILTER[@]} -eq 0 && ${#SERVICE_FILTER[@]} -eq 0 ]]; then
    MODULES_TO_BUILD=("${JAR_MODULES[@]}")
    SERVICES_TO_BUILD=("${ALL_SERVICES[@]}")
    return
  fi

  local module_acc=()
  local service_acc=()

  for entry in "${MODULE_FILTER[@]}"; do
    local module="$(resolve_module_token "$entry")"
    module_acc+=("$module")
    service_acc+=("${MODULE_TO_SERVICE[$module]}")
  done

  for svc in "${SERVICE_FILTER[@]}"; do
    local token="${svc,,}"
    service_acc+=("$token")
    if [[ -n "${SERVICE_TO_MODULE[$token]:-}" ]]; then
      module_acc+=("${SERVICE_TO_MODULE[$token]}")
    fi
  done

  dedupe_list MODULES_TO_BUILD "${module_acc[@]}"
  dedupe_list SERVICES_TO_BUILD "${service_acc[@]}"

  if (( ${#SERVICES_TO_BUILD[@]} == 0 )); then
    SERVICES_TO_BUILD=("${ALL_SERVICES[@]}")
  fi
}

run_maven_package() {
  local modules=("$@")
  (( ${#modules[@]} == 0 )) && return
  local csv
  csv=$(IFS=,; echo "${modules[*]}")
  local mvn_cmd=(mvn -B -pl "$csv" -am package)
  if $SKIP_TESTS; then
    mvn_cmd+=("-DskipTests")
  fi
  if [[ -n "${MAVEN_CLI_OPTS}" ]]; then
    read -r -a extra_opts <<<"${MAVEN_CLI_OPTS}"
    mvn_cmd+=("${extra_opts[@]}")
  fi

  echo "Packaging modules (${csv}) via local Maven"
  "${mvn_cmd[@]}"
}

stage_artifacts() {
  local modules=("$@")
  (( ${#modules[@]} == 0 )) && return
  rm -rf "${LOCAL_ARTIFACTS_DIR}"
  mkdir -p "${LOCAL_ARTIFACTS_DIR}"
  for module in "${modules[@]}"; do
    local jar_path
    jar_path=$(find "${module}/target" -maxdepth 1 -type f -name '*-exec.jar' \
      ! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -name 'original-*.jar' | head -n 1 || true)
    if [[ -z "$jar_path" ]]; then
      jar_path=$(find "${module}/target" -maxdepth 1 -type f -name '*.jar' \
        ! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -name 'original-*.jar' | head -n 1 || true)
    fi
    if [[ -z "$jar_path" ]]; then
      echo "Unable to locate packaged jar for ${module}" >&2
      exit 1
    fi
    cp "${jar_path}" "${LOCAL_ARTIFACTS_DIR}/${module}.jar"
    echo " - Staged ${module} → ${LOCAL_ARTIFACTS_DIR}/${module}.jar"
  done
}

build_worker_images() {
  local modules=("$@")
  (( ${#modules[@]} == 0 )) && return

  local built_any=false
  for module in "${modules[@]}"; do
    local image=""
    local target=""
    case "$module" in
      swarm-controller-service)
        image="swarm-controller:latest"
        target="swarm-controller"
        ;;
      generator-service)
        image="generator:latest"
        target="generator"
        ;;
      request-builder-service)
        image="request-builder:latest"
        target="request-builder"
        ;;
      http-sequence-service)
        image="http-sequence:latest"
        target="http-sequence"
        ;;
      moderator-service)
        image="moderator:latest"
        target="moderator"
        ;;
      processor-service)
        image="processor:latest"
        target="processor"
        ;;
      postprocessor-service)
        image="postprocessor:latest"
        target="postprocessor"
        ;;
      clearing-export-service)
        image="clearing-export:latest"
        target="clearing-export"
        ;;
      trigger-service)
        image="trigger:latest"
        target="trigger"
        ;;
      *)
        continue
        ;;
    esac
    built_any=true
    echo "Building worker image ${image} from module ${module}"
    docker build \
      -f Dockerfile.bees.local \
      --target "${target}" \
      --build-arg RUNTIME_IMAGE="${RUNTIME_IMAGE}" \
      -t "${image}" .
  done

  if ! $built_any; then
    echo "No worker modules selected for image build; skipping worker images."
  fi
}

sync_scenarios() {
  echo "Syncing scenarios into Scenario Manager container..."
  if ! command -v docker >/dev/null 2>&1; then
    echo "Docker is required to sync scenarios." >&2
    return 1
  fi
  # Find the Scenario Manager container started by docker compose.
  local container
  container=$(docker ps -q --filter "label=com.docker.compose.service=scenario-manager" | head -n 1)
  if [[ -z "$container" ]]; then
    echo "No running scenario-manager container found. Ensure the stack is up before syncing scenarios." >&2
    return 1
  fi

  if [[ -d "scenario-manager-service/capabilities" ]]; then
    echo " - Copying local capabilities to container ${container}:/app/capabilities"
    docker cp "scenario-manager-service/capabilities/." "${container}:/app/capabilities/" >/dev/null
  fi

  local base_url reload_url
  base_url="${SCENARIO_MANAGER_BASE_URL:-http://localhost:8088/scenario-manager}"
  # Trim trailing slash if present.
  base_url="${base_url%/}"
  reload_url="${base_url}/scenarios/reload"

  if ! command -v curl >/dev/null 2>&1; then
    echo "curl is required to trigger scenario reload at ${reload_url}." >&2
    return 1
  fi

  echo " - Triggering scenario reload via ${reload_url}"
  if ! curl -fsS -X POST "${reload_url}" >/dev/null; then
    echo "Scenario reload request to ${reload_url} failed." >&2
    return 1
  fi
  echo "Scenarios synced and reload triggered successfully."
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --help|-h)
        usage
        exit 0
        ;;
      --quick)
        SKIP_TESTS=true
        ONLY_CLEAN=false
        ;;
      --module)
        [[ $# -lt 2 ]] && { echo "--module requires a value" >&2; exit 1; }
        MODULE_FILTER+=("$2")
        shift
        ONLY_CLEAN=false
        ;;
      --module=*)
        MODULE_FILTER+=("${1#*=}")
        ONLY_CLEAN=false
        ;;
      --service)
        [[ $# -lt 2 ]] && { echo "--service requires a value" >&2; exit 1; }
        SERVICE_FILTER+=("$2")
        shift
        ONLY_CLEAN=false
        ;;
      --service=*)
        SERVICE_FILTER+=("${1#*=}")
        ONLY_CLEAN=false
        ;;
      --clean)
        CLEAN_STACK=true
        ;;
      --prune-images)
        PRUNE_IMAGES=true
        ;;
      --sync-scenarios)
        SYNC_SCENARIOS_ONLY=true
        ;;
      --restart)
        [[ $# -lt 2 ]] && { echo "--restart requires a service name" >&2; exit 1; }
        RESTART_TARGETS+=("$2")
        shift
        ONLY_CLEAN=false
        ;;
      --restart=*)
        RESTART_TARGETS+=("${1#*=}")
        ONLY_CLEAN=false
        ;;
      --restart-all)
        RESTART_TARGETS=("${ALL_SERVICES[@]}")
        ONLY_CLEAN=false
        ;;
      *)
        echo "Unknown option: $1" >&2
        usage >&2
        exit 1
        ;;
    esac
    shift
  done
}

format_duration() {
  local seconds=$1
  if (( seconds < 0 )); then
    printf "n/a"
  else
    printf "%dm %02ds" $((seconds / 60)) $((seconds % 60))
  fi
}

measure() {
  local label="$1"
  shift
  local start
  start=$(date +%s)
  set +e
  "$@"
  local status=$?
  set -e
  local end
  end=$(date +%s)
  DURATIONS["$label"]=$((end - start))
  if (( status != 0 )); then
    exit "$status"
  fi
}

print_timing_summary() {
  local total_end
  total_end=$(date +%s)
  local total_duration=$((total_end - BUILD_START_TIME))
  echo
  echo "=== Timing Summary ==="
  for label in "${TIMING_ORDER[@]}"; do
    local pretty_label
    case "$label" in
      build_base) pretty_label="build base";;
      maven_package) pretty_label="maven package";;
      stage_artifacts) pretty_label="stage jars";;
      docker_build_workers) pretty_label="worker images";;
      docker_build) pretty_label="docker build";;
      compose_up) pretty_label="docker up";;
      restart) pretty_label="restart";;
      *) pretty_label="$label";;
    esac
    local duration=${DURATIONS[$label]:--1}
    printf "  %-16s %s\n" "${pretty_label}:" "$(format_duration "$duration")"
  done
  printf "  %-16s %s\n" "total:" "$(format_duration "$total_duration")"
  echo
}

main() {
  parse_args "$@"
  require_tools
  BUILD_START_TIME=$(date +%s)

  # Special case: --sync-scenarios → only copy local scenarios into the running
  # Scenario Manager container and trigger a reload (no Maven or Docker build).
  if $SYNC_SCENARIOS_ONLY; then
    DURATIONS["clean"]=-1
    DURATIONS["build_base"]=-1
    DURATIONS["maven_package"]=-1
    DURATIONS["stage_artifacts"]=-1
    DURATIONS["docker_build_workers"]=-1
    DURATIONS["docker_build"]=-1
    DURATIONS["compose_up"]=-1
    DURATIONS["restart"]=-1
    measure "sync_scenarios" sync_scenarios
    TIMING_ORDER=(sync_scenarios)
    print_timing_summary
    echo
    echo "PocketHive scenario sync complete."
    echo "Finished at: $(date '+%Y-%m-%d %H:%M:%S')"
    exit 0
  fi

  determine_targets

  # Special case: --quick with no module/service filters → reuse existing jars/images,
  # just clean + compose up (no Maven package, no docker build).
  if $SKIP_TESTS && (( ${#MODULE_FILTER[@]} == 0 )) && (( ${#SERVICE_FILTER[@]} == 0 )); then
    MODULES_TO_BUILD=()
    SERVICES_TO_BUILD=()
  fi

  # Special case: just --clean → only tear down containers/stack.
  if $CLEAN_STACK && $ONLY_CLEAN; then
    measure "clean" clean_stack
    print_timing_summary
    echo
    echo "PocketHive stack cleanup complete (no build requested)."
    echo "Finished at: $(date '+%Y-%m-%d %H:%M:%S')"
    exit 0
  fi

  # Always clean before full-stack builds,
  # and honour explicit --clean for partial builds.
  if $CLEAN_STACK || { (( ${#MODULE_FILTER[@]} == 0 )) && (( ${#SERVICE_FILTER[@]} == 0 )) ; }; then
    measure "clean" clean_stack
  else
    DURATIONS["clean"]=-1
  fi

  export LOCAL_ARTIFACT_DIR="${LOCAL_ARTIFACTS_DIR}"
  export POCKETHIVE_RUNTIME_IMAGE="${RUNTIME_IMAGE}"

  if (( ${#MODULES_TO_BUILD[@]} )); then
    measure "build_base" build_base_image
    measure "maven_package" run_maven_package "${MODULES_TO_BUILD[@]}"
    measure "stage_artifacts" stage_artifacts "${MODULES_TO_BUILD[@]}"
    measure "docker_build_workers" build_worker_images "${MODULES_TO_BUILD[@]}"
  else
    DURATIONS["build_base"]=-1
    DURATIONS["maven_package"]=-1
    DURATIONS["stage_artifacts"]=-1
    DURATIONS["docker_build_workers"]=-1
  fi

  if (( ${#SERVICES_TO_BUILD[@]} )); then
    echo "Building Docker images for: ${SERVICES_TO_BUILD[*]}"
    measure "docker_build" compose_build_services "${SERVICES_TO_BUILD[@]}"
  else
    echo "No services selected for build; skipping image build."
    DURATIONS["docker_build"]=-1
  fi

  echo "Starting PocketHive stack via docker compose up -d"
  measure "compose_up" compose_up_full_stack

  if (( ${#RESTART_TARGETS[@]} )); then
    echo "Restarting requested services: ${RESTART_TARGETS[*]}"
    measure "restart" compose_up_services "${RESTART_TARGETS[@]}"
  else
    DURATIONS["restart"]=-1
  fi

  # Convenience: when using --quick with no module/service filters, we reuse
  # existing images but still want local scenario/capability changes to be
  # visible without rebuilding the Scenario Manager image.
  if $SKIP_TESTS && (( ${#MODULE_FILTER[@]} == 0 )) && (( ${#SERVICE_FILTER[@]} == 0 )); then
    sync_scenarios
  fi

  print_timing_summary
  echo
  echo "PocketHive local build complete."
  echo "Finished at: $(date '+%Y-%m-%d %H:%M:%S')"
}

main "$@"
