# Dedicated fresh local deployment (SM-2)

Use the existing built images and canonical root Compose file. This overlay only
isolates the services needed for platform startup; it does not alter startup policy.
The existing local deployment remains running. If images need rebuilding, use
`build-hive.sh` first; this recipe is a separate provision, not a rebuild shortcut.

Run the complete block from the repository root, choosing an unused ingress port.
The parentheses keep all project/runtime settings in a subshell, so the calling
terminal retains its previous environment on both success and failure. Always create
a new project, runtime directory and named volumes; restarting a deployment or
observing an empty registry is not evidence of freshness.

```bash
(
  set -euo pipefail
  export COMPOSE_PROJECT_NAME="ph-sm2-$(cat /proc/sys/kernel/random/uuid)"
  export SM2_INGRESS_PORT=18088
  SM2_RUN_DIRECTORY="$(mktemp -d /tmp/ph-sm2.XXXXXXXX)"
  export POCKETHIVE_SCENARIOS_RUNTIME_ROOT="$SM2_RUN_DIRECTORY/runtime"
  printf 'SM-2 evidence and recovery settings: %s\n' "$SM2_RUN_DIRECTORY"
  printf 'export COMPOSE_PROJECT_NAME=%q\nexport SM2_INGRESS_PORT=%q\nexport POCKETHIVE_SCENARIOS_RUNTIME_ROOT=%q\n' \
    "$COMPOSE_PROJECT_NAME" "$SM2_INGRESS_PORT" "$POCKETHIVE_SCENARIOS_RUNTIME_ROOT" \
    > "$SM2_RUN_DIRECTORY/deployment.env"
  install -d -m 0777 "$POCKETHIVE_SCENARIOS_RUNTIME_ROOT"
  # The disposable bind mount must be writable by the container runtime user.

  docker compose -f docker-compose.yml -f acceptance-tests/deployments/fresh-local.compose.yml \
    up -d --wait --wait-timeout 180 ui auth-service orchestrator artemis \
    > "$SM2_RUN_DIRECTORY/deploy.log" 2>&1

  cat > "$SM2_RUN_DIRECTORY/api.properties" <<EOF
ingress=http://localhost:${SM2_INGRESS_PORT}/
username=local-admin
requestTimeout=PT10S
evidenceDirectory=${PWD}/acceptance-tests/target/runs
EOF
  cp "$SM2_RUN_DIRECTORY/api.properties" "$SM2_RUN_DIRECTORY/fresh.properties"
  printf 'deploymentId=%s\n' "$COMPOSE_PROJECT_NAME" >> "$SM2_RUN_DIRECTORY/fresh.properties"

  ./run-acceptance-tests.sh "$SM2_RUN_DIRECTORY/api.properties" smoke
  ./run-acceptance-tests.sh "$SM2_RUN_DIRECTORY/fresh.properties" fresh-deployment

  docker compose -f docker-compose.yml -f acceptance-tests/deployments/fresh-local.compose.yml \
    down --volumes > "$SM2_RUN_DIRECTORY/down.log" 2>&1
)
```

Select exactly the services above; their dependencies are inherited from the canonical
Compose file. The overlay does not isolate unrelated services' published ports.
The new project owns its network and named volumes, including Postgres and both brokers.
The host Docker daemon is shared; this is a startup smoke with no swarm mutations.

`deploymentId` is the operator's declaration, not a proof or a runtime registry.
Keep the deployment creation log with the test artifacts. The test verifies the exact
deployment administrator grant before accepting the Orchestrator's empty swarm list.
No refresh, RESET, CREATE or REMOVE is sent.

On failure the block stops and leaves the owned project available for diagnosis. The
calling terminal's variables are still unchanged. Inspect through the declared public
ingress and preserve evidence; do not clear the registry to obtain a passing result.
After diagnosis, replace the example path below with the directory printed by the run
and remove only that owned project, again within a subshell:

```bash
(
  set -euo pipefail
  source /tmp/ph-sm2.XXXXXXXX/deployment.env
  docker compose -f docker-compose.yml -f acceptance-tests/deployments/fresh-local.compose.yml \
    down --volumes
)
```

Retain the temporary run directory until its deployment evidence has been saved.
NW-4 still requires the separate remote Swarm/NFS environment.
