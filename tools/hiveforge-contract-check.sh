#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STACK_TASKS="${ROOT_DIR}/deploy/hiveforge/components/stack/ansible/swarm-stack.yml"
DEPLOY_PLAYBOOK="${ROOT_DIR}/deploy/hiveforge/components/stack/ansible/deploy.yml"
UPDATE_PLAYBOOK="${ROOT_DIR}/deploy/hiveforge/components/stack/ansible/update.yml"
COMPONENT_MANIFEST="${ROOT_DIR}/deploy/hiveforge/components/stack/hiveforge.yaml"
STACK_TEMPLATE="${ROOT_DIR}/deploy/hiveforge/components/stack/ansible/templates/stack-compose.yml.j2"

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

require_not_contains "${COMPONENT_MANIFEST}" 'POCKETHIVE_AUTH_OAUTH_INTROSPECTION_SECRET'
require_not_contains "${COMPONENT_MANIFEST}" 'POCKETHIVE_AUTH_SERVICE_ACCOUNT_MCP_SECRET'
require_not_contains "${DEPLOY_PLAYBOOK}" 'POCKETHIVE_AUTH_OAUTH_INTROSPECTION_SECRET'
require_not_contains "${DEPLOY_PLAYBOOK}" 'POCKETHIVE_AUTH_SERVICE_ACCOUNT_MCP_SECRET'
require_not_contains "${UPDATE_PLAYBOOK}" 'POCKETHIVE_AUTH_OAUTH_INTROSPECTION_SECRET'
require_not_contains "${UPDATE_PLAYBOOK}" 'POCKETHIVE_AUTH_SERVICE_ACCOUNT_MCP_SECRET'
require_contains "${STACK_TASKS}" 'pockethive_phase_one_dev_auth:'
require_contains "${STACK_TASKS}" 'provider: DEV'
require_contains "${STACK_TASKS}" 'introspection_credential: pockethive-mcp-local-introspection-secret'
require_contains "${STACK_TASKS}" 'service_credential: pockethive-mcp-local-service-secret'
require_contains "${STACK_TEMPLATE}" 'POCKETHIVE_AUTH_SERVICE_ACCOUNT_ORCHESTRATOR_SECRET: orchestrator-local-secret'
require_contains "${STACK_TEMPLATE}" 'POCKETHIVE_AUTH_SERVICE_PRINCIPAL_NAME: orchestrator-service'
require_contains "${STACK_TEMPLATE}" 'POCKETHIVE_AUTH_SERVICE_PRINCIPAL_SECRET: orchestrator-local-secret'
require_contains "${STACK_TEMPLATE}" 'POCKETHIVE_AUTH_SERVICE_PROVIDER: {{ pockethive_phase_one_dev_auth.provider }}'
require_contains "${STACK_TEMPLATE}" 'POCKETHIVE_AUTH_SERVICE_ACCOUNT_MCP_SECRET: {{ pockethive_phase_one_dev_auth.service_credential }}'
require_contains "${STACK_TEMPLATE}" 'POCKETHIVE_AUTH_OAUTH_INTROSPECTION_SECRET: {{ pockethive_phase_one_dev_auth.introspection_credential }}'
require_contains "${STACK_TEMPLATE}" 'PH_MCP_OAUTH_INTROSPECTION_CLIENT_SECRET: {{ pockethive_phase_one_dev_auth.introspection_credential }}'
require_contains "${STACK_TEMPLATE}" 'PH_MCP_DOWNSTREAM_SERVICE_NAME: pockethive-mcp'
require_contains "${STACK_TEMPLATE}" 'PH_MCP_DOWNSTREAM_SERVICE_SECRET: {{ pockethive_phase_one_dev_auth.service_credential }}'
require_not_contains "${STACK_TEMPLATE}" 'pockethive-mcp-local-introspection-secret'
require_not_contains "${STACK_TEMPLATE}" 'pockethive-mcp-local-service-secret'

echo "HiveForge action-root contract check passed."
