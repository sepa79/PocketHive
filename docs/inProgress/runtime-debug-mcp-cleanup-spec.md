Status: in progress / design

# Runtime Debug MCP Cleanup Spec

## Goal

Allow operators and MCP clients to debug PocketHive worker containers/services
and explicitly clean stale swarm runtime resources without making HiveForge
responsible for PocketHive product runtime.

## Decisions

- PocketHive owns swarm/bee runtime debug and cleanup.
- HiveForge stays deployment-scope only: deploy, update, remove, and health-check
  the PocketHive stack.
- Orchestrator must not call HiveForge to clean bees.
- Orchestrator remains the desired-state and swarm-registry owner.
- Swarm Controller remains the worker/runtime topology owner.
- MCP is an operator facade over PocketHive runtime reconciliation; it is not the
  domain owner for Docker or RabbitMQ state.
- Read-only diagnostics are normal MCP tools.
- The canonical MCP surface is `tools/pockethive-mcp`; runtime debug tools live
  there and inherit its stdio and Streamable HTTP transports.
- `tools/mcp-orchestrator-debug` may remain a CLI/dev helper, but must not be
  the product MCP surface for runtime cleanup.
- Cleanup is always: `plan -> governed execute`.
- Cleanup must only target resources with PocketHive ownership labels.
- Cleanup includes both swarm-controller manager runtimes and worker runtimes.
- RabbitMQ cleanup must only target resources from an exact PocketHive runtime
  ownership manifest or Orchestrator-derived control-plane topology descriptors
  fed by exact PocketHive runtime labels; no queue-prefix guessing.
- No Docker prune, name-prefix guessing, raw broad filters, or implicit fallback cleanup.
- Native ChatGPT/MCP write-tool confirmation is useful UX, but real approval
  belongs in HiveGate or another governed control plane, not PocketHive MCP.

## Runtime Reconciliation Architecture

Use one application service as the authority for cleanup planning and execution:

```text
RuntimeReconciliationService
  -> SwarmRegistryPort
  -> RuntimeOwnershipManifestPort
  -> ComputeRuntimeInventoryPort
  -> RabbitTopologyInventoryPort
  -> RuntimeCleanupEvidencePort
```

Ownership rules:

- Normal registered swarm removal goes through the existing swarm lifecycle path.
  Do not bypass `swarm-remove` / `removeSwarm` with raw Docker/Rabbit deletes.
- Registered swarm-controller container/service deletion is represented as
  `LIFECYCLE_REMOVE_SWARM`, not as a raw Docker delete.
- Orphan cleanup is for resources left behind after lifecycle failure or registry
  loss. It uses the ownership manifest plus live inventory to build an exact plan.
- Orphaned swarm-controller containers/services are valid Docker cleanup
  candidates when ownership labels match and no active registry entry owns them.
- Docker labels prove runtime ownership, but RabbitMQ cleanup needs the manifest
  because queues/exchanges do not carry Docker labels.
- Docker ownership label names are contract constants in `PocketHiveDockerLabels`.
  Runtime cleanup code must reuse that contract rather than redeclare label strings.
- The manifest is written during swarm creation/apply and records exact owned
  resource names: controller runtime id, worker runtime ids, controller control
  queue, worker control queues, work queues, work exchange, swarmId, runId,
  templateId, and image/version metadata.
- If the manifest is missing, Docker cleanup may proceed by labels after
  governed execution is permitted, but RabbitMQ cleanup is blocked and reported
  as `missing ownership manifest`.

Plan actions:

```text
LIFECYCLE_REMOVE_SWARM
DELETE_DOCKER_CONTAINER
DELETE_DOCKER_SERVICE
DELETE_RABBIT_QUEUE
DELETE_RABBIT_EXCHANGE
```

RabbitMQ safety:

- Only delete exact queue/exchange names from the manifest.
- Deleting queues implicitly removes their bindings; do not create separate binding
  delete logic unless the adapter supports exact binding identity.
- Include queue depth and consumer count in the plan.
- Empty queues with zero consumers are standard-risk cleanup candidates.
- Non-empty queues or queues with consumers are high-risk cleanup candidates and
  should be approval-gated by HiveGate policy.
- Active registered swarms must not delete shared work queues/exchanges as part of
  individual stale worker cleanup.
- Worker control queues may be deleted only when the manifest and registry agree
  that the worker instance is no longer active.

MCP role:

- `tools/pockethive-mcp` exposes the tools. HiveGate owns approval UX and
  execution policy when the mutating tool is registered there.
- Cleanup plan and execute tools delegate to Orchestrator's runtime reconciliation
  API. If Orchestrator HTTP is unavailable, those tools fail closed; MCP must not
  execute a local cleanup fallback.
- MCP read-only diagnostics may inspect bounded Docker/RabbitMQ state, but must
  report source availability and must not make cleanup-authority decisions.
- When an Orchestrator HTTP client is configured, MCP runtime tools first check
  `GET /api/runtime/debug/capabilities`. If the contract is stale or missing,
  only runtime tools fail closed; existing scenario/workflow/swarm tools keep
  their current behavior.

## Non-Goals

- Replacing Orchestrator swarm lifecycle APIs.
- Auto-deleting failed swarms.
- Managing non-PocketHive Docker resources.
- Making HiveForge aware of individual bees.

## Runtime Labels

Every Orchestrator controller and Swarm Controller worker runtime object must carry
these labels.

```text
pockethive.managed=true
pockethive.resourceKind=manager|worker
pockethive.owner=orchestrator|swarm-controller
pockethive.swarmId=<swarmId>
pockethive.runId=<runId>
pockethive.role=<role>
pockethive.instance=<instance>
pockethive.logicalName=<logical runtime name>
pockethive.computeAdapter=DOCKER_SINGLE|SWARM_STACK
pockethive.image=<image>
pockethive.createdAt=<RFC3339 timestamp>
```

Optional labels:

```text
pockethive.version=<tag parsed from pockethive.image when present>
pockethive.beeId=<scenario bee id>
pockethive.buildId=<build id>
```

Rules:

- Cleanup candidates require `pockethive.managed=true` and `pockethive.swarmId`.
- Resources without these labels may be reported as unmanaged diagnostics, but must
  not be removed.
- Existing `ph.*` labels are not enough for cleanup authority.
- Worker version metadata must derive from the scenario bee image used to create
  the worker runtime object. Do not source worker version from deployment-wide
  `POCKETHIVE_VERSION`.

## PocketHive MCP Tools

Default exposed tool names use underscores for client compatibility
(`runtime_cleanup_plan`). Dotted names (`runtime.cleanup.plan`) are conceptual
contract names and are available only when the PocketHive MCP is explicitly
started with legacy/both naming enabled.

### Read-Only Tools

Mark these with `readOnlyHint=true`.

Current implemented tools:

```text
runtime.tail-worker-logs
runtime.get-worker-version
runtime.list-workers
runtime.inspect-worker
runtime.diff-swarm-runtime
runtime.control-plane-status
runtime.rabbit-topology-snapshot
runtime.swarm-timeline
runtime.manifest-validate
runtime.cleanup.plan
```

Future expansion:

```text
none
```

All runtime debug tools must prefer explicit PocketHive ownership labels,
runtime manifests, Orchestrator journal/API data, and RabbitMQ management data.
If a source is unavailable, return `available=false` for that source instead of
guessing or silently substituting another authority path.

Performance isolation rules:

- Runtime debug tools must not consume from scenario work/control queues.
- Runtime debug tools must not create consumers, bindings, exchanges, queues, or
  taps on the scenario queues. Debug taps must use separate temporary queues.
- Runtime debug tools must not publish control/data messages, exec into workers,
  pause/resume containers, or mutate worker/container state.
- RabbitMQ diagnostics must use exact manifest-owned queue/exchange reads by
  default. Full RabbitMQ topology scans are allowed only when an operator
  explicitly requests unmanaged diagnostics, and those results are advisory only.
- MCP control-plane status must display manifest/Orchestrator-provided queue names
  instead of deriving queue names locally.
- Docker diagnostics must be bounded metadata/log reads; logs must use finite
  `--tail` and must not follow.
- Agents must not poll these tools in tight loops during performance runs; use
  HiveGate/client policy for rate limits in benchmark sessions.

`runtime.cleanup.plan` input:

```json
{
  "computeAdapter": "DOCKER_SINGLE|SWARM_STACK",
  "swarmId": "required",
  "runId": "optional",
  "includeRunning": false
}
```

`runtime.cleanup.plan` output:

```json
{
  "swarmId": "demo",
  "runId": "run-1",
  "candidateSetHash": "sha256:...",
  "executionRisk": "standard",
  "candidates": [
    {
      "runtimeId": "container-or-service-id",
      "action": "DELETE_DOCKER_CONTAINER",
      "resourceKind": "worker",
      "role": "processor",
      "instance": "demo-processor-1",
      "state": "exited",
      "image": "ghcr.io/pockethive/processor:1.2.3",
      "reason": "no matching active swarm registry entry",
      "labels": {}
    }
  ],
  "blocked": [
    {
      "runtimeId": "container-or-service-id",
      "reason": "missing required PocketHive labels"
    },
    {
      "resourceId": "ph.work.demo.final",
      "action": "DELETE_RABBIT_QUEUE",
      "reason": "missing ownership manifest"
    }
  ]
}
```

`runtime.tail-worker-logs` input:

```json
{
  "computeAdapter": "DOCKER_SINGLE|SWARM_STACK",
  "swarmId": "required",
  "runId": "optional",
  "runtimeId": "optional",
  "instance": "optional",
  "role": "optional",
  "tailLines": 200,
  "since": "optional Docker time filter"
}
```

Rules:

- Resolve exactly one `pockethive.resourceKind=worker` target by labels.
- Require `runtimeId`, `instance`, or a `role` that matches one worker.
- Reject ambiguous matches instead of choosing a worker.
- For `DOCKER_SINGLE`, use `docker logs`; for `SWARM_STACK`, use `docker service logs`.
- Return bounded, redacted, non-streaming log text only.

`runtime.get-worker-version` input:

```json
{
  "computeAdapter": "DOCKER_SINGLE|SWARM_STACK",
  "swarmId": "required",
  "runId": "optional",
  "runtimeId": "optional",
  "instance": "optional",
  "role": "optional"
}
```

Rules:

- Resolve exactly one worker using the same rules as `runtime.tail-worker-logs`.
- Return `declaredVersion` from `pockethive.version` when present.
- Also return exact `image`, parsed `imageTag`, and parsed `imageDigest`.
- Treat `pockethive.image` as the source of truth for the worker runtime image.

`runtime.list-workers` input:

```json
{
  "computeAdapter": "DOCKER_SINGLE|SWARM_STACK",
  "swarmId": "required",
  "runId": "optional",
  "includeManagers": true
}
```

Output: label-gated managers and workers with runtime id, state, role,
instance, image, run id, parsed version, and label health.

`runtime.inspect-worker` input matches `runtime.get-worker-version`. Output is a
bounded inspect summary: state, exit code, restart count, created/started/finished
timestamps, health status, restart policy, network names, and mount destinations.
Do not return raw environment variables or unredacted host paths.

`runtime.diff-swarm-runtime` input:

```json
{
  "computeAdapter": "DOCKER_SINGLE|SWARM_STACK",
  "swarmId": "required",
  "runId": "optional",
  "includeRabbit": true,
  "journalLimit": 100
}
```

Output compares Orchestrator snapshot, ownership manifest, Docker/Swarm runtime,
RabbitMQ topology, and cleanup plan. It must identify missing manifested runtime
objects, unexpected labeled runtime objects, stopped/running state issues,
missing queues/exchanges, and cleanup candidates.

`runtime.control-plane-status` input:

```json
{
  "computeAdapter": "DOCKER_SINGLE|SWARM_STACK",
  "swarmId": "required",
  "runId": "optional",
  "journalLimit": 100
}
```

Output: controller/worker control queues, queue depth, consumers, last known
status/heartbeat-like journal event per worker, and recent control commands when
available. Missing journal or RabbitMQ access must be reported explicitly.

`runtime.rabbit-topology-snapshot` input:

```json
{
  "swarmId": "required",
  "runId": "optional",
  "includeUnmanagedDiagnostics": false
}
```

Output uses exact queues/exchanges from the runtime ownership manifest. If the
manifest is missing, return `manifest.available=false`; do not prefix-guess
cleanup authority. Optional unmanaged diagnostics may report prefix-matched
resources as read-only hints only and may require a broad management API read.

`runtime.swarm-timeline` input:

```json
{
  "computeAdapter": "DOCKER_SINGLE|SWARM_STACK",
  "swarmId": "required",
  "runId": "optional",
  "limit": 100
}
```

Output: chronological view from Orchestrator journal when available, enriched
with runtime/container state summaries. If the journal is unavailable, return a
runtime-only timeline and mark the source as unavailable.

`runtime.manifest-validate` input:

```json
{
  "computeAdapter": "DOCKER_SINGLE|SWARM_STACK",
  "swarmId": "required",
  "runId": "optional",
  "includeRabbit": true
}
```

Output validates the runtime ownership manifest against live Docker/Swarm and
RabbitMQ state. It reports missing manifested resources, label mismatches,
unexpected PocketHive-labeled runtime objects, missing queues/exchanges, and
queue depth/consumer risk. It never removes anything.

### Mutating Tool

Do not mark these as read-only.

```text
runtime.cleanup.execute
```

`runtime.cleanup.execute` removes selected candidates after governance has
allowed the MCP tool invocation.

```text
runtime.cleanup.execute
```

Input:

```json
{
  "computeAdapter": "DOCKER_SINGLE|SWARM_STACK",
  "swarmId": "required",
  "runId": "optional",
  "includeRunning": false,
  "candidateSetHash": "required",
  "candidateIds": ["required"],
  "idempotencyKey": "required",
  "reason": "required",
  "actor": "optional"
}
```

Execution rules:

- Orchestrator recomputes the candidate set before executing.
- Use the same `includeRunning` scope as the planned execution; it is part of
  `candidateSetHash`.
- Reject if the current `candidateSetHash` differs.
- Remove only the listed candidate ids.
- Emit audit/evidence for each attempted removal.
- Reusing the same `idempotencyKey` must not repeat successful deletion work.

## Governance Contract

- PocketHive MCP does not approve its own mutating cleanup tool.
- Register `runtime.cleanup.execute` behind HiveGate for production operation.
- HiveGate policy decides whether the destructive execution requires human
  approval, then invokes the exact tool input it approved.
- `candidateSetHash`, `candidateIds`, `swarmId`, `runId`, `includeRunning`, and
  `idempotencyKey` are the fields HiveGate should bind into policy/evidence.
- Read-only diagnostics and cleanup planning can remain non-destructive.
- Running resources, multi-swarm cleanup, missing `runId`, large candidate sets,
  non-empty queues, and queues with consumers should be high-risk policy inputs.

For ChatGPT Apps:

- App UI may show a cleanup review surface.
- Any approve/reject button must call HiveGate or another authoritative approval
  backend directly; model-callable PocketHive tools must not mint approvals.
- Native confirmation does not replace HiveGate policy/approval.

## Evidence

Record a cleanup evidence entry with:

```text
toolName
actor
idempotencyKey
swarmId
runId
candidateSetHash
candidateIds
resultByCandidate
startedAt
finishedAt
errors
```

Do not store secrets or full unredacted environment variables.

Evidence is written by Orchestrator's cleanup evidence store as part of the
execute API response. MCP clients must not keep a separate cleanup execution
evidence authority.

## Implementation Checklist

- Add Orchestrator `RuntimeReconciliationService` behind ports/adapters.
- Add a persisted runtime ownership manifest written during swarm create/apply.
- Add Orchestrator read-only plan API for Docker + RabbitMQ reconciliation.
- Add Orchestrator execute API with plan-hash/idempotency checks.
- Make PocketHive MCP delegate production cleanup to Orchestrator reconciliation.
- Remove MCP local cleanup plan/execute fallback paths.
- Register `runtime.cleanup.execute` in HiveGate as a governed destructive tool.
- Add canonical labels to controller and worker runtime creation and reuse those
  label contract constants in cleanup reconciliation.
- Add runtime inventory ports for Docker single-node and Docker Swarm service modes.
- Add RabbitMQ topology inventory/removal ports that operate only on manifest names.
- Add bounded log/version inspection.
- Add `runtime.cleanup.plan`.
- Add `runtime.cleanup.execute`.
- Expose tools through the canonical PocketHive MCP/App.
- Add tests for hash mismatch, unlabeled resources, idempotency,
  running-resource risk classification, missing manifest blocking RabbitMQ cleanup,
  non-empty queue risk classification, and active-swarm shared queue protection.
