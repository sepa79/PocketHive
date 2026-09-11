# B02 Redis dataset selection — separate review

Review finding: **SEL-R1 HIGH**. Subsequent human decision defers it as an open
[known issue](known-issues.md#sel-r1--stale-redis-source-during-stopupdatestart) and permits
continuing the plan. The finding and reproduction below remain valid. Reviewed HEAD `eb681ee7` plus the
uncommitted source-mode transfer and RS-R1/RS-R2 corrections. Other B02 transfers
and complete B02 acceptance are outside this review. No production fixes or permanent
tests were added.

## SEL-R1 — HIGH: a resumed tick keeps consuming the previous list

RedisDataSetWorkInput captures sourceSelection only in ensureReadyForTick (line 265),
then popNextValue reads that snapshot for every pop (lines 552–555). The state listener
can disable the worker, apply an allowed single-list update and re-enable it while a
tick is in progress. applyRawConfigOverrides changes properties (lines 365–368), but
the remaining tick still destructively pops the old Redis list after the new selection
has been applied. The next tick eventually refreshes the snapshot; that does not restore
entries already removed from the wrong list.

Reproduction against the actual input and registered state listener, with an in-memory
Redis client: quota 2, initial list `old`; during the first pop deliver disabled/old,
disabled/new, enabled/new snapshots. WorkPatchPolicy accepts the name-only update with
previous single-source settings and workerEnabled=false. Properties report `new`, but
the two pop calls are **[old, old]**. This models a callback interleaving while Redis IO
is in flight; it uses no backend port or live Redis.

The pre-transfer popNextValue read properties for each pop (`git show
eb681ee7:common/worker-sdk/src/main/java/io/pockethive/worker/sdk/input/redis/RedisDataSetWorkInput.java`).
A temporary copy of the current adapter with only those three source reads restored
produces **[old, new]** under the same probe. This is a controlled comparison of the
changed reads, not a claim to have run the complete previous revision. The existing
lack of a per-pop disable check predates this transfer; retaining the stale source
through subsequent pops is introduced here.

The adapter must invalidate/finish the old tick before more reads, or make subsequent
reads use the current atomically published validated selection. Keep selection rules
in work-config. Do not reinstate an independent mode predicate as the production fix.

## Ownership and effects

| Responsibility | Owner/header, consumers and inspected effects | Verdict |
|---|---|---|
| RESP-WORK-REDIS-SELECTION | WorkConfigurationParser owns mode and symbolic co-constraints; RedisDatasetSelectionValidation is an immutable report, RedisDatasetSourceMode names states. Startup, raw input, Scenario Manager and WorkPatchPolicy delegate. No infrastructure calls in these types. | Canonical decision supported; runtime application violates current-selection behavior under SEL-R1. |
| RESP-WORK-REDIS-SOURCES | RedisDatasetSource owns scalar decoding, parser owns list/duplicate rules, RedisSourcesValidation exposes results. Source constructor and parser do not access Redis. | RS-R1 explicit-null rejection and RS-R2 normalized-name diagnostics accepted; prior findings preserved. |
| RESP-WORK-IO-CONFIG / RESP-WORK-PATCH-POLICY | WorkerDefinitionDiscovery → WorkInputConfigBinder → properties/parser; WorkerControlPlaneRuntime → policy before merge. Policy validates requested name and prior mode without mutation. | Inspected transfer supported; complete candidate/acknowledgement and remaining settings remain deferred. |
| RESP-WORK-REDIS-DATASET / RESP-SCENARIO-VALIDATE | Redis factory → input/state listener → shared parser → properties; tick → LettuceRedisListClient → lpop. ScenarioBundleValidator → WorkConfigurationFindings projects errors/deferred paths. | No second source-mode validator found; SEL-R1 blocks adapter acceptance. |

Repository-wide search covered Java, TS/TSX, JS/MJS and Python for listName/list-name,
source-mode/single-source, old predicates and selection/parser symbols. Results:
`/tmp/b02-selection-review-owners.txt`, `/tmp/b02-selection-review/consumer-search.txt`.
Inspected SwarmWorkerSpecFactory environment export (producer, still deferred), capability
required/allowBlank metadata and UI runtimeConfigGuard (patch presence/stopped-state UX),
plus orderedSources/weightedIndex (sampling). They do not independently validate the
source-mode contract. Remaining connection/rate validators are explicitly outside this slice.

## Verification and review passes

- Re-ran the focused command in redis-selection-transfer.md: **258 tests passed**,
  no failures/errors/skips, including the existing import test. Log:
  `/tmp/b02-selection-review-tests.log`. Existing tests cover RS-R1/RS-R2 but omit SEL-R1.
- Temporary probes and outputs: `/tmp/b02-selection-review/SelectionTickProbe.java`,
  `tick.txt`, `tick-before.txt`, `before-pop/RedisDataSetWorkInput.java`.
- Checked Spring precedence with a high-priority scalar above a lower nested field.
  Both current Object binding and a temporary pre-transfer String binding shape reject
  it (`binding.txt`, `binding-before.txt`); this is inherited binder behavior, not a new finding.
- Plan: selection ownership is transferred, but preservation across STOP/update/START
  is blocked by SEL-R1. Style: new types/headers match their records; no new mixed type.
  Conciseness: duplicate mode predicates removed, no new scanner/framework.
  Security: SEL-R1 can remove data from the no-longer-selected Redis list; no new auth,
  credential or routing surface. Libraries: no new dependency. Readability/maintainability:
  ownership is explicit, but the tick projection's lifetime is incorrect under updates.
- `git diff --check` passed. No deployed-stack check, commit or full-repository SSOT verdict.
  Full candidate validation before accepted Control Plane state/ack, remaining typed
  settings/producers and original empty YAML shape preservation remain open.
