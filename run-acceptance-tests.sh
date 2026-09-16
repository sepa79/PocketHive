#!/usr/bin/env bash
set -euo pipefail
if [[ $# != 2 ]]; then
  echo "Usage: $0 <target.properties> <JUnit tag expression>" >&2
  exit 2
fi
runner_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
target_file="$(realpath -- "$1")"
test_group="$2"
if [[ ! -f "$target_file" || -z "$test_group" ]]; then
  echo "An existing target file and nonempty test group are required." >&2
  exit 2
fi
cd "$runner_root"
exec ./mvnw -B -ntp -pl acceptance-tests -am -Pacceptance \
  "-Dacceptance.target=$target_file" "-Dacceptance.group=$test_group" verify
