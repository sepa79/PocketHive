# B02 Rabbit connection transfer review — 2026-09-08

Verdict: no new runtime regression found in the reviewed export transfer; two MEDIUM
architecture/header corrections are required before accepting this bounded transfer.
The inherited final-environment validation gap remains open for B02. No production
changes or fixes were made during review.

## Scope and verification

Reviewed `eb681ee7` plus the latest uncommitted Rabbit transfer, separated from the
earlier B02/R1 work using r1-source-changes.sha256 and rabbit-source-changes.sha256.
All 103 current Java/POM entries in the latter match the actual files/deletions.
Earlier request-template/patch-policy transfers were not reviewed again.

Reran the eight suites from the transfer's documented command: **58 tests passed**,
zero failures/errors/skips. Log: `/tmp/b02-rabbit-review-tests.log`. The existing import
test passed; the new pure module's inherited Maven dependency enforcement passed.
The implementation's earlier root package/docs results were read, not rerun in review.

A temporary Java probe compiled the two original classes from `git show HEAD:<path>`
under distinct names, against the current classpath:
ControlPlaneContainerEnvironmentFactory and SwarmWorkerSpecFactory. Their remaining
dependencies did not change in this Rabbit transfer. It compared complete worker
participant exports and exercised both real worker planning paths without external IO.

```text
Complete participant export identical for port 1: true
Complete participant export identical for port 5672: true
Complete participant export identical for port 65535: true
Before: final worker plan accepts blank host=true, port=0
After: final worker plan accepts blank host=true, port=0
```

Probe: `/tmp/pockethive-b02-rabbit-review/RabbitExportProbe.java`; output:
`/tmp/pockethive-b02-rabbit-review/probe-result.txt`. It used synthetic credentials and
the public planning/export APIs; no broker, container launch or direct service port.

## RB-R1 — MEDIUM: current architecture overstates validation of the final environment

The new RESP-RABBIT-CONNECTION required effect (`runtime-responsibilities.md:389–391`)
says the same explicit values reach container plans and invalid fields fail before
launch. SwarmWorkerSpecFactory.plan still calls `environment.putAll(bee.env())` at line
80 after the canonical export. A bee can therefore replace the validated host with an
empty string and the port with `0`; both values reach WorkerSpec without rejection.

The probe reproduced this before and after the transfer. The runtime bypass is inherited;
the new defect is presenting the broader postcondition as established by this transfer.
The remaining complete candidate/producer migration is already explicitly open in B02.

Correction: distinguish the implemented validation/export of the shared base settings
from the still-required validation of the final worker environment. Name the bee.env
merge in the remaining gate and its canonical owner/policy; do not weaken the final B02
requirement or claim it has passed. This review does not require an unrelated full B02
implementation merely to accept the bounded encoder extraction.

## RB-R2 — MEDIUM: two changed headers cite a dependency's contract as their primary role

ContainerLifecycleManager's new header (lines 42–45) declares container lifecycle, while
its only Contract points to RESP-RABBIT-CONNECTION. SwarmWorkerSpecFactory's changed
header (lines 26–29) declares scenario-to-worker planning, but points to the same connection
record. That record defines connection values/export and lists these classes only as
consumers. It does not define the owner/restrictions for lifecycle, worker spec/config
construction, SUT enrichment or volume planning.

The existing mixed implementations are inherited debt. The new or replaced header links
do not satisfy the agreed primary-responsibility documentation requirement. A debt note
does not establish the missing owner record. SwarmLifecycleManager correctly retains
RESP-CONTROLLER-CONTROL and names the Rabbit dependency separately; use that distinction.

Correction: add or reference accurate current-owner records for container lifecycle and
worker planning, including their explicit remaining debt. Link the two primary Contract
headers there and retain RESP-RABBIT-CONNECTION as a consumed contract. Do not expand the
Rabbit record to own these unrelated responsibilities.

## Responsibility and source evidence

| Owner/call path | Inspected evidence | Result |
|---|---|---|
| RabbitConnectionSettings → RabbitConnectionEnvironment | Record constructor validates required text and port bounds; encoder only reads immutable fields into Map.of. Password is preserved; text representation redacts values. No production POM dependencies or IO. | Supported for the five-field base export. |
| Both service entrypoints → RabbitConnectionConfiguration | Explicit @Import in each entrypoint; Spring Binder binds the same record, without RabbitProperties defaults or a second validator. Only launch services require the new settings bean. | Supported; missing-field and round-trip behavior tests pass. |
| ContainerLifecycleManager → CP controllerEnvironment → shared encoder | Both constructor paths retain the immutable settings; factory delegates export before container spec creation. | Export behavior preserved; RB-R2 primary header record missing. |
| SwarmLifecycleManager → SwarmWorkerSpecFactory → CP workerEnvironment → shared encoder | Actual constructor forwarding inspected; worker planning later overlays bee.env, adds volumes and emits WorkerSpec. | Base export preserved; RB-R1 final-value guarantee overstated and RB-R2 primary header record missing. |
| Old validation/export owners | Repository searches for RabbitProperties, populateRabbitEnv, requireRabbitPort, SPRING_RABBITMQ_, RabbitConnectionDetails and connection factory/custom binding hooks. Inspected production callers and JavaScript/tool hits. | Old Java encoder/validator removed. No new competing encoder found. Spring client construction and YAML input/defaults remain distinct upstream concerns. |

Searches covered the repository, excluding generated target/build output; test fixtures,
application YAML and compose variables were distinguished from production encoders.
The five Java environment assignments exist only in RabbitConnectionEnvironment. The
generic bee.env overlay remains relevant even though it contains no literal Rabbit key.

## Six review passes

| Pass | Result |
|---|---|
| Plan outcome | Extraction and deletion follow the approved B02 direction. RB-R1 requires accurate current-versus-final acceptance boundaries; full Work parsing is still open. |
| Style guide | New pure types are separate and have accurate headers. RB-R2 blocks primary header/architecture agreement in two consumers. No new mixed runtime behavior was added. |
| Conciseness | Two pure types plus a standard Spring decoder suffice; no fallback chains, new parsing framework or duplicate validator. |
| Security | Settings text is redacted and credentials are preserved. No new external effects. Final environment override behavior is inherited and must not be represented as validated. |
| Library necessity | No new production third-party dependency; uses Java records/maps and the existing Spring Binder. |
| Readability/maintainability | Base settings → encoder → launch composition is explicit. Correct the primary records and acceptance wording under RB-R1/RB-R2. |

Limits: no deployed acceptance or TLS/address-list propagation verification. The latter,
upstream application defaults, remaining service client imports and full Work candidate
validation remain declared open work. No root-repository SSOT or whole-B02 acceptance.
`git diff --check` passed. No implementation fix, commit, push or deployment was performed.
