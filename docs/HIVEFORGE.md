# PocketHive installation with HiveForge

This is the current installation and deployment guide for PocketHive's `stack`
component on Docker Swarm. The examples use PocketHive `v0.15.36` / image tag
`0.15.36` and the HiveForge 0.5.9 MCP interface. They describe configuration,
not a request to deploy to any particular environment.

If the installation has already been upgraded, compare its stored runtime
configuration with the requirements below and follow [verification](#verify-the-installation).
A completed upgrade does not need to be repeated to use this guide.
Version-to-version changes are indexed in [Upgrading PocketHive](UPGRADING.md).

The authoritative inputs and behavior are in the
[project manifest](https://github.com/sepa79/PocketHive/blob/v0.15.36/hiveforge.yaml),
[component manifest](https://github.com/sepa79/PocketHive/blob/v0.15.36/deploy/hiveforge/components/stack/hiveforge.yaml),
[shared deploy/update render tasks](https://github.com/sepa79/PocketHive/blob/v0.15.36/deploy/hiveforge/components/stack/ansible/swarm-stack.yml)
and [Stack template](https://github.com/sepa79/PocketHive/blob/v0.15.36/deploy/hiveforge/components/stack/ansible/templates/stack-compose.yml.j2).
This guide explains those contracts; it does not introduce alternative inputs.

## Current supported profiles

| Profile | Runtime and storage | Installation path |
| --- | --- | --- |
| `swarm-reduced` | Docker Swarm; RabbitMQ, Postgres and ClickHouse data beneath the shared HiveForge project root. Redis uses a Docker named volume local to its scheduled node. | The Swarm procedure below, with seven common runtime inputs. Redis data is not thereby protected against relocation to another node. |
| `swarm-full` | Docker Swarm; RabbitMQ, Postgres, ClickHouse and Redis use four explicit service-owned host directories and matching placement labels. Remaining shared state uses the HiveForge project root. | The same procedure, with the seven common inputs plus four dedicated roots. |
| `single-full` | Declared in the manifest, but the current HiveForge deploy/update playbooks explicitly reject it. | For a local development installation use [the canonical local setup](USAGE.md#local-mcp-and-vs-code-quick-start) and `build-hive.sh`. |

HiveForge consumes already-published images for both Swarm profiles. It does not
build or push PocketHive images during installation. The component's `deploy`
and `update` actions share the same Swarm render and validation tasks. Removal
is owned by HiveForge's deployment lifecycle; the PocketHive `remove.yml`
playbook rejects direct project-side Docker removal.

## Before installation

1. Install and connect HiveForge to the intended Docker Swarm environment using
   [HiveForge's installation guide](https://github.com/sepa79/HiveForge/blob/v0.5.9/docs/install/docker-compose.md).
   Its configured executor owns deployment, whether it is `docker-direct` or
   `portainer-stack`. Connect the HiveForge MCP client using its documented
   transport; PocketHive's public `/mcp` endpoint is a separate product endpoint.
2. Select one profile, one repository/ref, one image registry/tag and one
   public origin. The source ref and image tag must identify the intended build.
   The examples use Git ref `v0.15.36` and image tag `0.15.36`; the image tag has
   no leading `v`. Verify publication and registry access before deployment.
   See [image publication and registry access](GHCR_SETUP.md).
3. Ensure every eligible node can access the intended shared HiveForge project
   storage. Matching directory names alone do not prove that nodes see the same
   filesystem or that service users can write. Have the infrastructure owner
   verify the backing mount, storage identity and required access. HiveForge's
   current shared-root verification limitation is tracked in
   [HiveForge #10](https://github.com/sepa79/HiveForge/issues/10).
4. For `swarm-full`, prepare the four dedicated directories and placement labels
   described below. HiveForge does not create or change ownership of these host
   directories. Ensure selected nodes can pull all required PocketHive and
   infrastructure images, including through any required outbound proxy.
5. Confirm the public address resolves to the ingress and clients can reach it.
   The shipped ingress publishes HTTP on 8088 and HTTPS on 8443. A public HTTPS
   origin on 443 requires an explicitly configured external ingress/proxy.
   The selected hostname/IP must also match the TLS certificate's Subject
   Alternative Name; changing the origin does not replace the shipped certificate.

The current PocketHive Swarm adapter uses the `DEV` authentication provider and
fixed, known development service credentials. The bundled certificate/key under
`deploy/hiveforge/runtime/dev-tls` is also a public development fixture. This
installation path does not provide production authentication or a production
TLS identity. Non-`DEV` deployment requires the separate approved secret-provider
contract; do not put service credentials in `set_project_runtime_env`.

### Existing scenario data

Before deploy, update or same-version redeploy of an installation containing
uploaded or edited bundles, retain recoverable exports through the supported
bundle download interface. The current writable `/app/scenarios` mount points
at `artifacts/runtime/scenarios`, which is replace-managed. Artifact replacement
can discard uploaded originals; `state/scenarios-runtime` holds generated
execution material and does not protect those originals.

This guide does not fix that storage defect or guarantee Scenario Manager stays
on one node. Those changes belong to
[PocketHive #525](https://github.com/sepa79/PocketHive/issues/525) and
[HiveForge #8](https://github.com/sepa79/HiveForge/issues/8).
Do not treat an already successful upgrade as evidence that a future redeploy
will preserve user bundles.

## Declared runtime requirements

The `stack` component declares exactly seven common non-secret inputs in
`requirements.environment`. Store all seven explicitly in the selected
project/profile's HiveForge runtime configuration before validation or action
execution. A value present only in the HiveForge server process may pass the
current presence check without reaching the isolated action runner; do not rely
on server-process environment inheritance.

| Input | Accepted value and meaning | Example |
| --- | --- | --- |
| `DOCKER_REGISTRY` | Registry and namespace with a trailing `/`; used to construct application image references. | `ghcr.io/sepa79/pockethive/` |
| `POCKETHIVE_VERSION` | Explicit published image tag matching the selected source/build. No version or `latest` is inferred. | `0.15.36` |
| `POCKETHIVE_WORK_TYPE` | Exactly `RABBITMQ` or `ARTEMIS`; selects deployment-wide WORK transport. | `ARTEMIS` |
| `POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_IMAGE_REPOSITORY_PREFIX` | Same registry/namespace as `DOCKER_REGISTRY`, without its final `/`; used for launched controller/worker images. | `ghcr.io/sepa79/pockethive` |
| `POCKETHIVE_PUBLIC_INGRESS` | Exact external origin, including an explicit non-default port, with no path or trailing `/`. This is shared Auth/MCP identity. | `https://pockethive.example:8443` |
| `POCKETHIVE_PUBLIC_HOST` | The origin's exact host value, including its non-default port; no scheme or path. | `pockethive.example:8443` |
| `POCKETHIVE_ALLOW_REMOTE_HTTP` | Explicit string `"false"` for HTTPS; `"true"` is required to permit an HTTP origin. | `"false"` |

`DOCKER_REGISTRY` must equal
`POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_IMAGE_REPOSITORY_PREFIX + "/"`.
An intentional `latest` image selection is accepted by the renderer, but the
examples use a versioned tag to keep the installation tied to a release.

### WORK, CONTROL and public identity

`POCKETHIVE_WORK_TYPE` selects WORK, not CONTROL. CONTROL remains RabbitMQ for
both selections. Selecting Artemis deploys the release-matched PocketHive
`artemis` image, including its diagnostic-copy transformer. Substituting the
plain upstream broker image does not satisfy that capture contract. Artemis
has one replica, stop-first updates and shared state under
`state/artemis/data`; the image uses UID/GID 1001:1001. This is not broker HA.

Selecting Rabbit WORK does not deploy Artemis. The renderer supplies explicit
Rabbit WORK settings separately from CONTROL. Selecting the deployment
WorkPlane does not silently rewrite existing scenarios' input/output adapters.
See the [Work Plane boundaries](architecture/work-plane-boundaries.md).

For public origin `https://pockethive.example:8443`, Auth Service's issuer is
`https://pockethive.example:8443/auth-service` and PocketHive's MCP resource is
`https://pockethive.example:8443/mcp`. The browser, companion and agent must use
that public identity and trust the selected TLS certificate. For the shipped
Dev fixture, explicitly trust its certificate; do not disable TLS verification.
That fixture covers the development Swarm IPs `192.168.88.50`–`192.168.88.53`,
not the illustrative `pockethive.example` domain. Use the installation's verified
address/certificate pairing; the example domain is not a supplied TLS identity.
The transport effects are owned by the
[public endpoint transport policy](architecture/AUTH_SERVICE_API_SPEC.md#public-endpoint-transport-policy).

### Only swarm-full: dedicated roots

The shared playbook validates these four additional inputs only when
`HIVEFORGE_PROFILE=swarm-full`. Each is an absolute Docker-daemon host directory,
used exactly as supplied; PocketHive does not append `/data`.

| Input | Example host directory | Required placement constraint |
| --- | --- | --- |
| `POCKETHIVE_RABBITMQ_ROOT` | `/data/rabbitmq` | `node.labels.pockethive.rabbitmq == true` |
| `POCKETHIVE_POSTGRES_ROOT` | `/data/postgres` | `node.labels.pockethive.postgres == true` |
| `POCKETHIVE_CLICKHOUSE_ROOT` | `/data/clickhouse` | `node.labels.pockethive.clickhouse == true` |
| `POCKETHIVE_REDIS_ROOT` | `/data/redis` | `node.labels.pockethive.redis == true` |

Prepare each directory on every node eligible under its service constraint,
with the intended backing storage and permissions for that service image.
A matching label is not proof that the directory exists or has the right data.
Changing roots or eligible nodes for an existing installation requires a
separate, explicit storage migration; changing the profile is not a migration.

HiveForge 0.5.9's component requirement list is flat, not conditional by profile.
The four roots therefore remain playbook-validated rather than globally declared;
adding them to `requirements.environment` would incorrectly require them for
`swarm-reduced`. A green prerequisites/presence result does not establish these
conditional checks or host access. The wider prerequisites/runner parity work
is tracked in [HiveForge #9](https://github.com/sepa79/HiveForge/issues/9).

### Platform inputs and optional inputs

HiveForge injects `HIVEFORGE_PROFILE` and `HIVEFORGE_BIND_SOURCE_DIR` into the
action context. The latter is the absolute Docker-visible managed project root;
do not supply an action-container path in its place. The Ansible action root
is the fixed `/hf`. These are platform-owned inputs, not additional PocketHive
component runtime requirements.

`POCKETHIVE_STACK_NAME` is not a supported PocketHive adapter input and is not
required by the component manifest. Select the stack identity with HiveForge's
`start_action.deploymentName`; preserve the existing deployment name on updates.
Changing this name does not migrate an existing stack.

Optional runtime inputs:

- `POCKETHIVE_GRAFANA_ROOT_URL`: set explicitly for a remote installation to
  the public origin plus `/grafana/`, including the trailing slash, for example
  `https://pockethive.example:8443/grafana/`. The Stack template maps it to
  `GF_SERVER_ROOT_URL`; the shared Auth/MCP `POCKETHIVE_PUBLIC_INGRESS` does not
  set Grafana's URL. If omitted, the existing template uses
  `http://localhost:8088/grafana/`, which is not the remote installation's address.
  This setting is separate from the component's seven common requirements.
- `HTTP_PROXY`, `HTTPS_PROXY`, `NO_PROXY`: set only when the environment needs
  outbound proxy access. The Stack template projects those settings into Grafana,
  including lowercase variants, so its ClickHouse datasource plugin can install.
  HTTP and HTTPS proxy values may differ. Do not store proxy credentials here;
  this map is for non-secret configuration.
- `POCKETHIVE_HAPROXY_NODE`, `POCKETHIVE_NETWORK_PROXY_MANAGER_NODE`: either leave
  both unset for scheduler placement, or set both to distinct eligible Swarm
  hostnames for explicit cross-node verification. A missing/ineligible selected
  node leaves the service pending; no alternative hostname is substituted.

## Complete runtime configuration examples

These are MCP tool arguments, not files to export into the action container.
Replace the example domain, registry/tag and host directories with the selected
installation's verified values. Each `values` map below supplies all seven
common inputs and explicitly sets Grafana's remote URL. Use exactly one matching
profile map; do not merge roots from `swarm-full` into the reduced profile as a
supposed common requirement.

### swarm-reduced with HTTPS

```yaml
set_project_runtime_env:
  projectId: pockethive
  profile: swarm-reduced
  values:
    DOCKER_REGISTRY: ghcr.io/sepa79/pockethive/
    POCKETHIVE_VERSION: "0.15.36"
    POCKETHIVE_WORK_TYPE: ARTEMIS
    POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_IMAGE_REPOSITORY_PREFIX: ghcr.io/sepa79/pockethive
    POCKETHIVE_PUBLIC_INGRESS: https://pockethive.example:8443
    POCKETHIVE_PUBLIC_HOST: pockethive.example:8443
    POCKETHIVE_ALLOW_REMOTE_HTTP: "false"
    POCKETHIVE_GRAFANA_ROOT_URL: https://pockethive.example:8443/grafana/
```

This supplies the action's HTTPS external origin and exact Host. Rabbit remains
CONTROL; Artemis is the explicit WORK selection. No dedicated root inputs are
required by this profile. Redis's node-local volume limitation still applies.

### swarm-full with HTTPS

```yaml
set_project_runtime_env:
  projectId: pockethive
  profile: swarm-full
  values:
    DOCKER_REGISTRY: ghcr.io/sepa79/pockethive/
    POCKETHIVE_VERSION: "0.15.36"
    POCKETHIVE_WORK_TYPE: ARTEMIS
    POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_IMAGE_REPOSITORY_PREFIX: ghcr.io/sepa79/pockethive
    POCKETHIVE_PUBLIC_INGRESS: https://pockethive.example:8443
    POCKETHIVE_PUBLIC_HOST: pockethive.example:8443
    POCKETHIVE_ALLOW_REMOTE_HTTP: "false"
    POCKETHIVE_GRAFANA_ROOT_URL: https://pockethive.example:8443/grafana/
    POCKETHIVE_RABBITMQ_ROOT: /data/rabbitmq
    POCKETHIVE_POSTGRES_ROOT: /data/postgres
    POCKETHIVE_CLICKHOUSE_ROOT: /data/clickhouse
    POCKETHIVE_REDIS_ROOT: /data/redis
```

The seven common values have the same meaning as above. The four additional
roots must already be usable on the correctly labelled nodes before deployment.

### Explicitly allowed HTTP

For an installation intentionally using unencrypted remote HTTP, use the full
reduced-profile map below. An HTTP origin with `"false"`, a missing allowance,
or a non-boolean value is rejected by the render action.

```yaml
set_project_runtime_env:
  projectId: pockethive
  profile: swarm-reduced
  values:
    DOCKER_REGISTRY: ghcr.io/sepa79/pockethive/
    POCKETHIVE_VERSION: "0.15.36"
    POCKETHIVE_WORK_TYPE: RABBITMQ
    POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_IMAGE_REPOSITORY_PREFIX: ghcr.io/sepa79/pockethive
    POCKETHIVE_PUBLIC_INGRESS: http://pockethive.example:8088
    POCKETHIVE_PUBLIC_HOST: pockethive.example:8088
    POCKETHIVE_ALLOW_REMOTE_HTTP: "true"
    POCKETHIVE_GRAFANA_ROOT_URL: http://pockethive.example:8088/grafana/
```

For `swarm-full` HTTP, take the complete full-profile map and replace its public
origin, Host, allowance and Grafana URL with this example's four HTTP values,
retaining all four roots. The companion must independently select
**Remote HTTP (unencrypted)**; this choice does not configure other clients
such as Amazon Q.

## Agent MCP Deploy Checklist

Use the selected environment's HiveForge MCP tools for deployment. Do not use
SSH, Proxmox, direct host Docker commands or REST as an agent deployment workaround.
The setup/policy/configuration examples below describe steps to perform when
installing; editing this guide does not authorize executing them.

1. Open the PocketHive HiveMind work context when available. Read the connected
   HiveForge tool catalogue; `check_health`, `list_environments` and
   `list_projects` establish the target and its supported executor/profile.
   The examples assume an environment identified as `swarm`; use the actual
   selected environment ID. Do not change targets after a validation failure.
2. Register the selected repository/ref if missing. For the official release:

   ```yaml
   register_project:
     repository: https://github.com/sepa79/PocketHive.git
     gitRef: v0.15.36
     registrationKind: official
   ```

   Verify the returned project ID and approved refs. Re-registering the same
   project/repository with another explicitly selected ref adds that approved
   ref; do not delete/recreate a project to approve a release. A development
   registration uses `registrationKind: development` and is tracked as
   `pockethive-development`; consistently use its returned ID and its explicit
   development source/image references in every subsequent call.
3. An authorized operator allows the selected project/profile/action in the
   environment policy. Example for a full installation:

   ```yaml
   set_environment_project_policy:
     environmentId: swarm
     projectId: pockethive
     profiles: [swarm-full]
     actions: [deploy, update]
   ```

   For a reduced installation use `profiles: [swarm-reduced]`. Match the existing
   authorized policy when updating; do not widen it to make validation pass.
4. Set the complete runtime configuration from the matching example above.
   Use `list_project_runtime_env` with the selected `projectId` to verify the
   stored profile scope. Updating this map affects future actions; it does not
   change an already running service or establish a successful deployment.
5. Inspect the exact ref with `inspect_project`, then check prerequisites and
   declared requirements with the same project/ref/profile:

   ```yaml
   explain_deploy_prerequisites:
     projectId: pockethive
     gitRef: v0.15.36
     component: stack
     action: deploy
     profile: swarm-full
   validate_requirements:
     projectId: pockethive
     gitRef: v0.15.36
     profile: swarm-full
   ```

   Substitute `swarm-reduced` consistently for that profile. For an existing
   installation use the intended `update` action in prerequisite inspection.
   Read missing/unknown items and verify the conditional roots, enum/URL values,
   shared storage and registry access separately. HiveForge 0.5.9's presence
   checks are not proof that all playbook assertions or action-runner inputs
   will pass. Do not bypass failures by adding arbitrary process environment
   values or treating Ansible `--check` as a guaranteed read-only preflight.
6. For a first installation, start the selected lifecycle action:

   ```yaml
   start_action:
     projectId: pockethive
     gitRef: v0.15.36
     component: stack
     action: deploy
     profile: swarm-full
     deploymentName: pockethive
   ```

   For `swarm-reduced`, change the profile consistently. For an intended update,
   use `action: update` and the existing deployment's name, profile and storage
   identity. First review the [existing scenario-data limitation](#existing-scenario-data).
   Changing a version is not permission to change adapters, roots, public
   identity, policy or deployment targets implicitly.
7. Poll the returned ID with `get_operation`. Inspect `list_deployments`,
   `get_deployment_compose`, `check_deployment_runtime_status` and `read_journal`
   using their connected tool schemas. Record source ref/resolved commit,
   image references, profile, deployment name, executor and operation result.
   An accepted action or a recorded deployment alone is not runtime health.
8. Follow public-ingress verification below. Stop at the observed error if an
   action or required health check fails; diagnose through the supported MCP
   surface rather than switching profiles, endpoints or executors.

Checkout-based inspection/validation can take time for PocketHive. A timed-out
validation is an unknown result, not approval. Inspect the exact error and the
connected tool's supported asynchronous workflow; do not silently replace
prerequisite checking with a deploy attempt.

## Verify the installation

Use the installation's configured public origin for every PocketHive check.
Direct service/container ports are not substitutes for the supported ingress.
For an already upgraded installation these are read-only checks; do not start
another lifecycle action merely to collect installation evidence.

- Confirm HiveForge's operation completed and runtime diagnostics show the
  expected services converged, including the selected broker and Scenario
  Manager. Inspect effective placement/mounts through HiveForge evidence;
  rendered constraints alone do not prove where a task runs.
- Open `<POCKETHIVE_PUBLIC_INGRESS>/v2/`. Confirm the version reported by the
  product and the deployment's image refs match the selected release. Sign in
  through the configured Auth Service flow.
- Request `<POCKETHIVE_PUBLIC_INGRESS>/healthz`; `ok` verifies the ingress only.
  Fetch `<POCKETHIVE_PUBLIC_INGRESS>/.well-known/oauth-protected-resource` and
  confirm its `resource` is that exact origin plus `/mcp`. The authorization
  metadata must identify the same public origin plus `/auth-service`.
- Check the recorded Stack artifact's Grafana `GF_SERVER_ROOT_URL` matches
  `POCKETHIVE_GRAFANA_ROOT_URL` and the selected public origin plus `/grafana/`.
  Open that public Grafana URL and verify its login/redirect URLs use the
  intended scheme, host and sub-path, rather than `localhost`.
- Connect the PocketHive Java Streamable HTTP MCP at that public `/mcp` URL
  using its supported authentication. A 401 authentication challenge before
  sign-in does not mean the endpoint is broken. Confirm its generated knowledge,
  capabilities and tool catalogue are readable; inspect environment status
  through the connected PocketHive tools. See [the MCP guide](mcp/README.md).
- Read the scenario catalogue through the UI or PocketHive MCP and confirm
  expected bundles and SUTs are visible to the signed-in principal. For an
  existing installation, compare exported user-bundle content with the retained
  pre-deployment copy; bundled release seeds alone do not prove user data survived.
- When functional acceptance is explicitly authorized, run the matching
  public-ingress acceptance target and retain its evidence. Read-only setup
  verification does not authorize creating/removing swarms or cleaning storage.
  The canonical runner and target rules are in [Usage](USAGE.md#independent-acceptance-framework).

Retain the selected configuration identity, operation/runtime result and public
endpoint evidence together. A version bump, image pull or successful renderer
is not proof of a complete installation.

## HiveForge Path Contract

PocketHive Ansible runs inside the isolated action container. It reads/writes
`/hf`, while Docker resolves bind mounts on its target hosts.

| Purpose | Authoritative path/value |
| --- | --- |
| Action root | `/hf` |
| Release-managed artifacts | `/hf/artifacts/runtime/...` |
| Rendered Stack file | `/hf/stacks/compose.yml` |
| Shared application state | `/hf/state/...` |
| Docker-visible equivalent of the managed root | HiveForge-injected `HIVEFORGE_BIND_SOURCE_DIR` |
| Full-profile service directories | The four explicit `POCKETHIVE_*_ROOT` inputs |

For example, an action-created `/hf/state/haproxy/runtime` is rendered as
`${HIVEFORGE_BIND_SOURCE_DIR}/state/haproxy/runtime` in a Docker bind mount.
Ansible must not try to create directories at the Docker host path. Dedicated
full-profile roots are prepared outside that action-root directory creation.
The project manifest owns the complete managed-artifact inventory.

MCP uses a read-only container root and an explicit 128 MiB tmpfs volume at
`/tmp`. Its UID10001 process creates `/tmp/pockethive-mcp-spool` with 0700 and
retains a separate 64 MiB upload quota. Verify successful startup and actual
writable mounts via runtime evidence; the Portainer path did not reliably apply
older service-level `tmpfs` entries or the volume's configured `mode`.

## Maintainer verification and release guidance

For changes to this guide or the renderer, compare all complete example maps
with the component's seven declared inputs and the shared action assertions.
Check both actions and profiles, HTTPS and explicit HTTP, and both WORK
selections. In particular, full-only roots must not become reduced requirements.
Also verify the examples render Grafana's explicit public URL rather than the
template's local default, and the Compose shell example exports its image inputs.
Existing local contract checks require Python with PyYAML/Jinja2:

```bash
python3 -B -m unittest discover -s deploy/hiveforge/tests -v
bash tools/hiveforge-contract-check.sh
```

These checks validate selected rendering contracts; they do not deploy,
prove registry access, establish shared-storage identity or qualify live ingress.
The [release publication checklist](GHCR_SETUP.md#release-publication-checklist)
requires installation examples and versioned migration guidance whenever
mandatory deployment inputs or their semantics change.

This guide addresses the documentation/configuration scope of
[PocketHive #526](https://github.com/sepa79/PocketHive/issues/526) and
[#527](https://github.com/sepa79/PocketHive/issues/527). It does not implement
HiveForge's remaining conditional-prerequisite/runner parity work or the separate
bundle-persistence fix. Runtime manifest and public contracts are unchanged.
