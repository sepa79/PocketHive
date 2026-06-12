#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STACK_TASKS="${ROOT_DIR}/deploy/hiveforge/components/stack/ansible/swarm-stack.yml"
DEPLOY_PLAYBOOK="${ROOT_DIR}/deploy/hiveforge/components/stack/ansible/deploy.yml"
UPDATE_PLAYBOOK="${ROOT_DIR}/deploy/hiveforge/components/stack/ansible/update.yml"

require_contains() {
  local file="$1"
  local pattern="$2"

  if ! grep -Fq -- "$pattern" "$file"; then
    echo "Missing required HiveForge contract text in ${file}: ${pattern}" >&2
    exit 1
  fi
}

require_not_contains() {
  local file="$1"
  local pattern="$2"

  if grep -Fq -- "$pattern" "$file"; then
    echo "Forbidden HiveForge contract text in ${file}: ${pattern}" >&2
    exit 1
  fi
}

require_contains "${DEPLOY_PLAYBOOK}" 'hiveforge_root: /hf'
require_contains "${UPDATE_PLAYBOOK}" 'hiveforge_root: /hf'
require_contains "${STACK_TASKS}" 'hiveforge_root == "/hf"'
require_contains "${STACK_TASKS}" 'path: "{{ hiveforge_root }}/{{ item }}"'
require_contains "${STACK_TASKS}" 'path: "{{ hiveforge_root }}/{{ item.path }}"'
require_not_contains "${STACK_TASKS}" 'path: "{{ pockethive_bind_source_dir }}/{{ item }}"'
require_not_contains "${STACK_TASKS}" 'path: "{{ pockethive_bind_source_dir }}/{{ item.path }}"'

require_contains "${STACK_TASKS}" 'HIVEFORGE_BIND_SOURCE_DIR: "{{ pockethive_bind_source_dir }}"'
require_contains "${STACK_TASKS}" 'when: pockethive_profile == "swarm-reduced"'
require_contains "${STACK_TASKS}" 'when: pockethive_profile == "swarm-full"'

require_contains "${DEPLOY_PLAYBOOK}" 'when: pockethive_profile in ["swarm-reduced", "swarm-full"]'
require_contains "${UPDATE_PLAYBOOK}" 'when: pockethive_profile in ["swarm-reduced", "swarm-full"]'

echo "HiveForge action-root contract check passed."
