# PocketHive Managed Datasets — One-Page Approval Brief

Status: in progress — decision requested; not yet team-approved

*A safer, reusable way to prepare, refresh and verify test data for PocketHive.*

**Decision requested:** Approve the design for implementation planning and a
small end-to-end learning exercise using non-sensitive synthetic data. Assign
the named product, platform, security, UX and QA owners before implementation
starts. **This does not approve release or make a production support claim.**

## Why this matters

Long-running performance tests need related test entities and other records
that remain valid throughout a test and can be reused safely by several
PocketHive swarms (groups of load-generating workers). PocketHive does not
currently manage this lifecycle end to end. Data can be slow to prepare,
duplicated between scenarios, expire during a run or become inconsistent with
the system being tested. This delays testing and can produce believable but
invalid results.

Checking a central data store during each test request is not acceptable because
it could distort the performance result. Operators also need a clear answer to:
**Is the data safe for this specific test, why, which swarms are affected, and
what evidence supports that decision?**

## The proposed capability

A **Managed Dataset** is a governed, reusable pool of related test records and
refreshable material. PocketHive would:

- obtain or create data through an approved source workflow;
- validate that it is suitable for the exact test and environment;
- securely store, refresh, replace, recover and retire it;
- distribute versioned local copies to workers before measured traffic starts;
  and
- provide a UI for Dataset-package drafts plus read-only runtime health/status
  and bounded retained proof—without exposing record values or secrets.

For the first delivery, this would be an independently bounded module inside
PocketHive's Orchestrator service. Each standalone Dataset package would
explicitly select its storage adapter. PostgreSQL would be the recommended
full managed-records profile. A narrower Redis collection adapter would remain
a separately qualified extension rather than part of the first delivery.
Messaging would carry lifecycle events or bounded work instructions but
would not be the trusted record. Each consumer worker would hydrate its required
data in the background, so measured test requests would not call a central
Dataset service.

All swarm plan, start, stop, live configuration and status messages would keep
using PocketHive's existing RabbitMQ Control Plane. When more records are
needed, the Dataset reconciler first asks the existing lifecycle application to
start the producer swarm. Only after fresh `RUNNING` and input-ready evidence
does it send a bounded `DATASET_SUPPLY` item on the controller-owned WorkItem
route. Results are committed through the Dataset API into the explicitly
selected adapter. Dataset
target size is independent of request count, traffic rate and the existing
`maxMessages` request limit.

The producer also checks the declared business result—not only whether an HTTP
or TCP call returned successfully. A frozen result policy distinguishes
completed, wrong-state, pending, invalid and uncertain outcomes. Completed and
conclusive failed records may be stored in different pre-approved Datasets for
reuse or remediation, but failures never fill the completed-data target and
ambiguous effects remain under reconciliation.

**No new architectural plane is introduced.** The WorkItem route is an
existing swarm data path, and the Dataset API is an application boundary over
an explicit PostgreSQL or Redis adapter—not another RabbitMQ exchange, control
queue or event bus.

## Expected value

- Less time and uncertainty preparing usable test data.
- Safe reuse across concurrent swarms and longer-running tests.
- Fewer invalid starts caused by expired, incomplete or unsuitable data.
- Faster diagnosis through explicit reasons and evidence.
- Better control of refresh, recovery, retirement and cleanup.
- More trustworthy performance results.

Benefits will be measured against an agreed baseline; they are not yet proven.

## Key safeguards

- Readiness is specific to the intended test; a record count alone never means
  “ready.” Missing, stale or contradictory evidence blocks new use.
- Access is limited by environment and Dataset scope. Stored material is
  encrypted.
- Dataset definitions are portable packages under
  `scenarios/managed-datasets/<datasetPackageId>/`. The UI may create/edit
  drafts; UI/MCP publish and retire only through authorised Scenario Manager
  commands. Published versions are immutable.
- A deployment-scoped Dataset Space is the shared SUT/access/classification/
  quota boundary. An explicit registration binds a package to a Space and
  storage adapter; packages do not embed or get rewritten by that context.
- The operator UI cannot mutate Dataset record/business state. Governed AI
  access uses HiveGate for package mutations, status reads and bounded proof.
  AI is never an authority.
- Sensitive or restricted real-world data remains disabled unless separately
  qualified.
- Release also requires named operational ownership for access control,
  encryption, recovery and evidence retention.
- Existing Redis paths become Managed Datasets only when explicitly registered
  by a Dataset package and qualified Redis capability profile; there is no
  automatic conversion or storage fallback.
- The release target is 50,000 eligible reusable records shared by at least two
  swarms, including safe live target changes. That remains a requirement—not a
  support claim—until the exact capacity and 24-hour qualification passes.

## First step and success criteria

Use the selected non-sensitive case to set measurable goals, then run one small
end-to-end learning exercise with one shared Dataset, one producer workflow and
two concurrent consumer swarms. It must demonstrate the mandatory
start/readiness/supply sequence, safe local use, live resizing, controlled
recovery and durable end-to-end receipts.

Success means no unsafe start, incorrect ready signal, uncontrolled duplicate
external action, unauthorised data exposure or central Dataset access during
measured traffic. The team must also agree numeric targets for time to usable
data, diagnosis time, invalid starts prevented and cleanup/recovery time.

**Current status:** **Design-ready for a team approval decision.** Cross-
functional approval and implementation readiness have not been evaluated; the
feature is not implemented or release-qualified. See the
[plain-language team guide](managed-datasets-team-design-overview.md),
[team decision brief](managed-test-data-team-review-brief.md) and
[planning wireframes](managed-datasets-wireframes/README.md) for supporting
detail. If this summary conflicts with a normative specification, the
specification wins.
