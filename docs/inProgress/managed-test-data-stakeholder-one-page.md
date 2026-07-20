# PocketHive Managed Datasets — One-Page Approval Brief

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
- provide a read-only view of health, suitability, supply work, affected swarms
  and supporting evidence—without exposing record values or secrets.

For the first delivery, this would be an independently bounded module inside
PocketHive's Orchestrator service. PostgreSQL would hold the trusted runtime
state; messaging would carry lifecycle events or bounded work instructions but
would not be the trusted record. Each consumer worker would hydrate its required
data in the background, so measured test requests would not call a central
Dataset service.

All swarm plan, start, stop, live configuration and status messages would keep
using PocketHive's existing RabbitMQ Control Plane. When more records are
needed, the Dataset reconciler first asks the existing lifecycle application to
start the producer swarm. Only after fresh `RUNNING` and input-ready evidence
does it send a bounded `DATASET_SUPPLY` item on the controller-owned WorkItem
route. Results are committed through the Dataset API into PostgreSQL. Dataset
target size is independent of request count, traffic rate and the existing
`maxMessages` request limit.

**No new architectural plane is introduced.** The WorkItem route is an
existing swarm data path, and the Dataset API is an application boundary over
PostgreSQL—not another RabbitMQ exchange, control queue or event bus.

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
- The operator UI and AI integrations are read-only. They cannot create, refresh,
  approve or delete data, and AI is never an authority.
- Sensitive or restricted real-world data remains disabled unless separately
  qualified.
- Release also requires named operational ownership for access control,
  encryption, recovery and evidence retention.
- The proposal is not a general-purpose data platform or a replacement for all
  existing Redis use.
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

**Current status:** **GREEN for team design approval and implementation
planning.** The feature is not yet implemented or release-qualified. See the
[plain-language team guide](managed-datasets-team-design-overview.md),
[team decision brief](managed-test-data-team-review-brief.md) and
[planning wireframes](managed-datasets-wireframes/README.md) for supporting
detail. If this summary conflicts with a normative specification, the
specification wins.
