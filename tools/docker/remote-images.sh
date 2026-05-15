#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPO_ROOT}"

source "${REPO_ROOT}/tools/docker/image-manifest.sh"

LOCAL_ARTIFACTS_DIR="${LOCAL_ARTIFACTS_DIR:-.local-jars}"
MAVEN_CLI_OPTS="${MAVEN_CLI_OPTS:-}"

REGISTRY=""
NAMESPACE=""
VERSION_TAG=""
SKIP_TESTS=false
SKIP_PACKAGE=false
PUSH_IMAGES=false
DRY_RUN=false
SERVICES=()

usage() {
  cat <<'USAGE'
Usage: tools/docker/remote-images.sh --registry <host:port> --namespace <name> --tag <tag> [options]

Build, tag, and optionally push PocketHive application images for a remote registry.

Required:
  --registry <host:port>   Registry endpoint, e.g. registry.example.lan:5000.
  --namespace <name>       Repository namespace, e.g. pockethive.
  --tag <tag>              Explicit image tag, e.g. dev-20260429-1330-gabc1234.

Options:
  --service <name>         Build one image by image name or compose service alias (repeatable).
                           Supported names come from tools/docker/image-manifest.sh.
  --skip-tests             Package Java modules with -DskipTests.
  --skip-package           Reuse already staged jars in .local-jars.
  --push                   Push built images to the registry.
  --dry-run                Print the planned commands without executing them.
  --help                   Show this help.

Environment:
  LOCAL_ARTIFACTS_DIR      Directory for staged jars (default: .local-jars).
  MAVEN_CLI_OPTS           Extra Maven flags appended to package command.
  VITE_STOMP_READONLY_USER Build arg for ui image (default: ph-observer).
  VITE_STOMP_READONLY_PASSCODE Build arg for ui image (default: ph-observer).
USAGE
}

fail() {
  echo "remote-images: $*" >&2
  exit 1
}

run_cmd() {
  printf '+'
  printf ' %q' "$@"
  printf '\n'
  if ! "${DRY_RUN}"; then
    "$@"
  fi
}

require_tools() {
  command -v docker >/dev/null 2>&1 || fail "docker is required"
  if ! "${SKIP_PACKAGE}"; then
    command -v mvn >/dev/null 2>&1 || fail "mvn is required unless --skip-package is used"
  fi
}

normalize_no_slash() {
  local value="$1"
  value="${value%/}"
  printf '%s' "${value}"
}

image_ref() {
  local image="$1"
  printf '%s/%s/%s:%s' "${REGISTRY}" "${NAMESPACE}" "${image}" "${VERSION_TAG}"
}

resolve_image_token() {
  local candidate="$1"
  if pockethive_image_exists "${candidate}"; then
    printf '%s' "${candidate}"
    return
  fi
  if pockethive_service_exists "${candidate}"; then
    printf '%s' "${POCKETHIVE_SERVICE_IMAGE[$candidate]}"
    return
  fi
  return 1
}

selected_images() {
  if (( ${#SERVICES[@]} > 0 )); then
    printf '%s\n' "${SERVICES[@]}"
  else
    pockethive_all_image_names
  fi
}

needs_java_package() {
  local image
  while IFS= read -r image; do
    case "${image}" in
      jvm-base|network-proxy-haproxy|ui|ui-v2)
        ;;
      *)
        return 0
        ;;
    esac
  done < <(selected_images)
  return 1
}

selected_java_modules() {
  local image module
  while IFS= read -r image; do
    module="${POCKETHIVE_IMAGE_MODULE[$image]:-}"
    if [[ -n "${module}" ]]; then
      printf '%s\n' "${module}"
    fi
  done < <(selected_images)
  if (( ${#SERVICES[@]} == 0 )); then
    printf '%s\n' "${POCKETHIVE_JAR_EXTRA_MODULES[@]}"
  fi
}

run_maven_package() {
  if ! needs_java_package; then
    echo "No Java runtime images selected; skipping Maven package."
    return
  fi

  local -a jar_modules=()
  local csv
  mapfile -t jar_modules < <(selected_java_modules)
  csv=$(IFS=,; echo "${jar_modules[*]}")
  local mvn_cmd=(mvn -B -pl "${csv}" -am clean package)
  if "${SKIP_TESTS}"; then
    mvn_cmd+=("-DskipTests")
  fi
  if [[ -n "${MAVEN_CLI_OPTS}" ]]; then
    read -r -a extra_opts <<<"${MAVEN_CLI_OPTS}"
    mvn_cmd+=("${extra_opts[@]}")
  fi
  run_cmd "${mvn_cmd[@]}"
}

stage_artifacts() {
  if ! needs_java_package; then
    return
  fi

  local -a jar_modules=()
  if "${DRY_RUN}"; then
    echo "+ stage Java artifacts into ${LOCAL_ARTIFACTS_DIR}"
    return
  fi

  rm -rf "${LOCAL_ARTIFACTS_DIR}"
  mkdir -p "${LOCAL_ARTIFACTS_DIR}"
  local module jar_path staged_path
  mapfile -t jar_modules < <(selected_java_modules)
  for module in "${jar_modules[@]}"; do
    jar_path=$(find "${module}/target" -maxdepth 1 -type f -name '*-exec.jar' \
      ! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -name 'original-*.jar' | head -n 1 || true)
    if [[ -z "${jar_path}" ]]; then
      jar_path=$(find "${module}/target" -maxdepth 1 -type f -name '*.jar' \
        ! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -name 'original-*.jar' | head -n 1 || true)
    fi
    [[ -n "${jar_path}" ]] || fail "unable to locate packaged jar for ${module}"
    staged_path="${LOCAL_ARTIFACTS_DIR}/${module}.jar"
    mkdir -p "$(dirname "${staged_path}")"
    cp "${jar_path}" "${staged_path}"
    echo "Staged ${module} -> ${staged_path}"
  done
}

require_staged_artifacts() {
  if ! needs_java_package; then
    return
  fi
  local -a jar_modules=()
  local module staged_path
  mapfile -t jar_modules < <(selected_java_modules)
  for module in "${jar_modules[@]}"; do
    staged_path="${LOCAL_ARTIFACTS_DIR}/${module}.jar"
    [[ -f "${staged_path}" ]] || fail "missing staged jar ${staged_path}; run without --skip-package"
  done
}

build_image() {
  local image="$1"
  local target_ref
  target_ref="$(image_ref "${image}")"
  local runtime_ref
  runtime_ref="$(image_ref jvm-base)"
  local dockerfile="${POCKETHIVE_IMAGE_DOCKERFILE[$image]:-}"
  local context="${POCKETHIVE_IMAGE_CONTEXT[$image]:-}"
  local target="${POCKETHIVE_IMAGE_TARGET[$image]:-}"
  local kind="${POCKETHIVE_IMAGE_KIND[$image]:-}"

  [[ -n "${dockerfile}" ]] || fail "manifest entry for ${image} is missing dockerfile"
  [[ -n "${context}" ]] || fail "manifest entry for ${image} is missing context"
  [[ -n "${kind}" ]] || fail "manifest entry for ${image} is missing kind"

  case "${kind}" in
    base|static)
      run_cmd docker build -f "${dockerfile}" -t "${target_ref}" "${context}"
      ;;
    runtime)
      run_cmd docker build -f "${dockerfile}" --build-arg "RUNTIME_IMAGE=${runtime_ref}" \
        -t "${target_ref}" "${context}"
      ;;
    worker)
      [[ -n "${target}" ]] || fail "worker image ${image} is missing target"
      run_cmd docker build -f "${dockerfile}" --target "${target}" \
        --build-arg "RUNTIME_IMAGE=${runtime_ref}" -t "${target_ref}" "${context}"
      ;;
    ui)
      if [[ "${image}" == "ui" ]]; then
        run_cmd docker build -f "${dockerfile}" \
          --build-arg "VITE_STOMP_READONLY_USER=${VITE_STOMP_READONLY_USER:-ph-observer}" \
          --build-arg "VITE_STOMP_READONLY_PASSCODE=${VITE_STOMP_READONLY_PASSCODE:-ph-observer}" \
          -t "${target_ref}" "${context}"
      else
        run_cmd docker build -f "${dockerfile}" -t "${target_ref}" "${context}"
      fi
      ;;
    *)
      fail "unsupported image kind for ${image}: ${kind}"
      ;;
  esac
}

push_image() {
  local image="$1"
  run_cmd docker push "$(image_ref "${image}")"
}

parse_args() {
  local image
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --registry)
        [[ $# -ge 2 ]] || fail "--registry requires a value"
        REGISTRY="$(normalize_no_slash "$2")"
        shift 2
        ;;
      --registry=*)
        REGISTRY="$(normalize_no_slash "${1#*=}")"
        shift
        ;;
      --namespace)
        [[ $# -ge 2 ]] || fail "--namespace requires a value"
        NAMESPACE="$(normalize_no_slash "$2")"
        shift 2
        ;;
      --namespace=*)
        NAMESPACE="$(normalize_no_slash "${1#*=}")"
        shift
        ;;
      --tag)
        [[ $# -ge 2 ]] || fail "--tag requires a value"
        VERSION_TAG="$2"
        shift 2
        ;;
      --tag=*)
        VERSION_TAG="${1#*=}"
        shift
        ;;
      --service)
        [[ $# -ge 2 ]] || fail "--service requires a value"
        image="$(resolve_image_token "$2")" || fail "unsupported service/image: $2"
        SERVICES+=("${image}")
        shift 2
        ;;
      --service=*)
        local service="${1#*=}"
        image="$(resolve_image_token "${service}")" || fail "unsupported service/image: ${service}"
        SERVICES+=("${image}")
        shift
        ;;
      --skip-tests)
        SKIP_TESTS=true
        shift
        ;;
      --skip-package)
        SKIP_PACKAGE=true
        shift
        ;;
      --push)
        PUSH_IMAGES=true
        shift
        ;;
      --dry-run)
        DRY_RUN=true
        shift
        ;;
      --help|-h)
        usage
        exit 0
        ;;
      *)
        fail "unknown option: $1"
        ;;
    esac
  done
}

validate_config() {
  [[ -n "${REGISTRY}" ]] || fail "--registry is required"
  [[ -n "${NAMESPACE}" ]] || fail "--namespace is required"
  [[ -n "${VERSION_TAG}" ]] || fail "--tag is required"
  [[ "${REGISTRY}" != *"://"* ]] || fail "--registry must be host[:port], not a URL"
  [[ "${NAMESPACE}" != *"://"* ]] || fail "--namespace must be a registry namespace, not a URL"
  [[ "${VERSION_TAG}" != "latest" ]] || fail "--tag must be explicit; latest is not allowed for remote testing"
}

main() {
  parse_args "$@"
  validate_config
  require_tools

  echo "Registry: ${REGISTRY}"
  echo "Namespace: ${NAMESPACE}"
  echo "Tag: ${VERSION_TAG}"
  echo "Images:"
  selected_images | sed 's/^/ - /'

  if ! "${SKIP_PACKAGE}"; then
    run_maven_package
    stage_artifacts
  else
    require_staged_artifacts
  fi

  local image
  while IFS= read -r image; do
    build_image "${image}"
  done < <(selected_images)

  if "${PUSH_IMAGES}"; then
    while IFS= read -r image; do
      push_image "${image}"
    done < <(selected_images)
  else
    echo "Push skipped. Re-run with --push after the registry is available."
  fi
}

main "$@"
