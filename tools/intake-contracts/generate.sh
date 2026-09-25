#!/usr/bin/env bash
# Responsibility: build canonical value owners and invoke the intake projection exporter.
# Must not: parse source files, select alternate owners or reseal the intake package.
# Contract: RESP-INTAKE-RUNTIME-VOCABULARY in docs/architecture/runtime-responsibilities.md.
set -euo pipefail

if [[ $# -gt 1 || ( $# -eq 1 && "$1" != "--check" ) ]]; then
  echo "Usage: tools/intake-contracts/generate.sh [--check]" >&2
  exit 2
fi

intake_repository_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd -- "$intake_repository_root"
"${intake_repository_root}/mvnw" -B -ntp -f "${intake_repository_root}/pom.xml" \
  -pl common/request-templates -am -DskipTests compile

java --class-path "${intake_repository_root}/common/auth-contracts/target/classes:${intake_repository_root}/common/request-templates/target/classes" \
  "${intake_repository_root}/tools/intake-contracts/ExportRuntimeVocabulary.java" \
  "${intake_repository_root}/.agents/skills/pockethive-intake/contract/schemas/runtime-vocabulary.schema.json" "$@"
