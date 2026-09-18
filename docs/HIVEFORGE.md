# HiveForge integration

HiveForge is PocketHive's **recommended direction** for managed,
production-like Docker Swarm deployment. Its feature-level behavior below was
last verified at `195c8480`, which predates the customer candidate source
`0524165e`; it has not been requalified as a deployment path for that candidate.
The older evidence shows stack preparation and validation only, not a complete
or **supported** deployment lifecycle.

Start with the canonical [deployment path chooser](guides/operators/deployment.md)
for the current status of source, Compose-package, and HiveForge paths and for
the shared status terminology. “Production-like” describes the orchestration
model, not a production-support claim.

:::warning Current implementation boundary

The `swarm-reduced` and `swarm-full` deploy/update playbooks render
`/hf/stacks/compose.yml` and validate it with `docker stack config`. They do not
run `docker stack deploy`. The remove playbook always fails, and `single-full`
is rejected by deploy/update.

A successful HiveForge action currently proves repository preparation,
template rendering, and stack validation. It does not prove that the target
runtime changed.

:::

## Implemented profile behavior

| Profile | Deploy/update | Remove |
| --- | --- | --- |
| `single-full` | Rejected. It does not run `build-hive.sh`. | Fails. |
| `swarm-reduced` | Prepares, renders, and validates the reduced Swarm stack. | Fails. |
| `swarm-full` | Prepares, renders, and validates the Swarm stack with dedicated RabbitMQ, Postgres, ClickHouse, and Redis roots. | Fails. |

PocketHive keeps image build and publication outside HiveForge. The Swarm
profiles consume registry-qualified, prebuilt images.

For both Swarm profiles, Scenario Manager, Orchestrator, swarm controllers,
dynamic workers, bundled SUT/mock services, proxy services, and UI use
the HiveForge-managed shared project root where the stack template declares
bind-backed state. Swarm controllers remain constrained to manager nodes
because they need Docker Swarm API access.

Redis is profile-specific: `swarm-reduced` uses the `redis-data` named volume;
`swarm-full` uses a dedicated `POCKETHIVE_REDIS_ROOT` bind and placement label.
`swarm-full` also uses dedicated service-owned roots and placement labels for
RabbitMQ, Postgres, and ClickHouse. Those host paths must already exist on
eligible nodes; the PocketHive action does not create or change them.

## HiveForge path contract

PocketHive Ansible actions run inside the HiveForge action container. They read
and write the project action root, not Docker-daemon host paths.

```yaml
hiveforge_root: /hf
```

| Purpose | Path or value | Owner |
| --- | --- | --- |
| Action root inside the Ansible container | `/hf` | HiveForge/action |
| Prepared release assets | `/hf/artifacts/runtime/...` | HiveForge managed artifacts |
| Rendered Docker Stack file | `/hf/stacks/compose.yml` | PocketHive action |
| Shared runtime state | `/hf/state/...` | PocketHive action under the managed root |
| Docker-daemon-visible equivalent of the project root | `HIVEFORGE_BIND_SOURCE_DIR` | HiveForge environment |
| Dedicated `swarm-full` service roots | `POCKETHIVE_*_ROOT` | Environment operator |

`HIVEFORGE_BIND_SOURCE_DIR` is used only when rendering bind sources for the
target Docker daemon. Ansible must create shared state through `/hf/state/...`.

```text
Action path:             /hf/state/haproxy/runtime
Rendered bind source:    ${HIVEFORGE_BIND_SOURCE_DIR}/state/haproxy/runtime
Example host equivalent: /opt/hiveforge/data/deployed/pockethive/state/haproxy/runtime
```

Managed release assets are copied under `/hf/artifacts/runtime/...`. Mutable
runtime state, such as Grafana and TCP Mock data, is created under `/hf/state`
and is not a managed release artifact.

## Declared runtime requirements

The component manifest currently declares:

```text
DOCKER_REGISTRY
POCKETHIVE_VERSION
POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_IMAGE_REPOSITORY_PREFIX
```

`DOCKER_REGISTRY` includes a trailing slash and equals the repository prefix
plus `/`. `POCKETHIVE_VERSION` must be an explicit image tag; use the exact
`<release-version>` approved for the operation rather than a floating tag.

`POCKETHIVE_STACK_NAME` is not a current component requirement and is not used
by the playbooks.

`swarm-full` additionally validates these profile-specific host paths:

```text
POCKETHIVE_RABBITMQ_ROOT
POCKETHIVE_POSTGRES_ROOT
POCKETHIVE_CLICKHOUSE_ROOT
POCKETHIVE_REDIS_ROOT
```

Each is the exact host directory mounted into its service. Eligible Swarm nodes
must carry the matching placement label:

```text
node.labels.pockethive.rabbitmq == true
node.labels.pockethive.postgres == true
node.labels.pockethive.clickhouse == true
node.labels.pockethive.redis == true
```

## Current preparation/validation workflow

Agents must use HiveForge MCP only. Do not SSH to hosts, inspect the
virtualization layer, run Docker commands directly on the target, or invent a
deployment fallback.

:::warning This workflow does not deploy

The steps below exercise the current preparation/validation action. Record the
result with that wording. Do not describe the action as a runtime deployment or
update.

:::

For a configured `swarm-full` environment:

1. Check HiveForge health and confirm the project/environment policy.
2. Set the exact release registry, version, repository prefix, and required
   dedicated roots.
3. Start `deploy` or `update` for the approved git ref and `swarm-full`.
4. Poll the HiveForge operation and inspect its journal.
5. Record the result as **stack preparation/validation**, not deployment.

Example runtime values:

```text
DOCKER_REGISTRY=ghcr.io/sepa79/pockethive/
POCKETHIVE_VERSION=<release-version-without-leading-v>
POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_IMAGE_REPOSITORY_PREFIX=ghcr.io/sepa79/pockethive
POCKETHIVE_RABBITMQ_ROOT=/data/rabbitmq
POCKETHIVE_POSTGRES_ROOT=/data/postgres
POCKETHIVE_CLICKHOUSE_ROOT=/data/clickhouse
POCKETHIVE_REDIS_ROOT=/data/redis
```

Set proxy variables only when the target needs outbound proxy access. Never
copy example proxy hostnames or credentials into a real configuration.

The current action shape is:

```text
start_action:
  projectId: pockethive
  gitRef: v<release-version>
  component: stack
  action: update
  profile: swarm-full
```

Do not run `remove`: the current playbook fails deliberately for every profile.

**Expected result:** the HiveForge journal records repository preparation,
stack rendering, and successful `docker stack config` validation.

**What this proves:** the action accepted the declared inputs and produced a
syntactically valid rendered stack.

**What this does not prove:** that deploy/update changed the target runtime,
that PocketHive ingress is healthy, or that remove works.

**Next step:** retain the operation ID, exact git ref, profile, image tag, and
journal as preparation/validation evidence. Runtime verification is not a
valid next step until HiveForge reports actual execution evidence.

**If it fails:** correct the explicit requirement or policy named in the
HiveForge journal and rerun through HiveForge. Do not use SSH, direct Docker,
or a different profile as an implicit fallback.

## Intended managed workflow

After runtime execution is implemented, the governed workflow is intended to:

1. publish one immutable PocketHive image set;
2. bind exact registry/version inputs to an approved git ref;
3. execute deploy or update through HiveForge;
4. distinguish render/validation evidence from runtime-execution evidence;
5. verify UI health and the first-swarm lifecycle through official ingress;
6. execute and verify governed remove or documented recovery.

These steps describe the target workflow, not current behavior.

## Managed deployment completion gate

Before HiveForge can be documented as a supported managed deployment, the
integration must demonstrate:

1. deploy creates or changes the target stack;
2. update changes an existing stack;
3. remove deletes the managed stack;
4. operation evidence distinguishes preparation from runtime execution;
5. the PocketHive UI health check passes through official ingress;
6. a scenario can complete create, ready, start, run, stop, and remove through
   the PocketHive customer interfaces.

Until that gate passes, use the
[source-development local flow](guides/operators/deployment.md) only for build
and startup evaluation. That path remains a candidate whose UI lifecycle is
blocked at Connectivity; track HiveForge execution as an explicit dependency.

## Update and recovery boundary

The current action does not update a running stack, remove one, or provide a
runtime rollback. A failed action is a preparation/validation failure: preserve
the operation journal, correct the explicit configuration or policy through
HiveForge, and rerun the approved action.

Do not infer runtime recovery from a successful render, and do not use direct
host access as a workaround. Once execution exists, update/remove/rollback
instructions must identify the observable runtime change, verification through
official ingress, and the evidence retained for each step.
