# PocketHive Managed Test Data — One-Page Approval Brief

*A safer, reusable way to prepare, refresh and verify test data for PocketHive.*

**Decision requested:** Approve the concept and recommended direction for the
next planning stage. Authorise the team to select one real, non-sensitive use
case, resolve the outstanding decisions, appoint named owners and return with
an estimate for a small end-to-end learning exercise. **This does not approve
implementation or release.**

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

For the first delivery, this would be a separate module inside PocketHive's
existing Orchestrator control service. PostgreSQL would hold the trusted state;
messaging would carry work instructions but would not be the trusted record.
Each worker would receive the required data in the background, so measured test
requests would not call a central Dataset service.

All swarm plan, start, stop, live configuration and status messages would keep
using PocketHive's existing RabbitMQ Control Plane—the shared path for swarm
commands and status. Separately, a durable Dataset lifecycle reconciler inside
Orchestrator would check Dataset needs and arrange supply, validation, refresh
and replacement work. The scenario timeline would decide when swarm
steps happen; each worker's rate control would decide how quickly requests are
sent. Dataset target size is independent of request count and the existing
`maxMessages` request limit.

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
- Release also requires named operational ownership for encryption keys,
  recovery and a tamper-resistant safety record protected by qualified
  hardware or an independently approved equivalent.
- The proposal is not a general-purpose data platform or a replacement for all
  existing Redis use.
- The release target is 50,000 eligible reusable records shared by at least two
  swarms, including safe live target changes. That remains a requirement—not a
  support claim—until the exact capacity and 24-hour qualification passes.

## First step and success criteria

After the planning decisions pass the implementation-readiness gate, use the
selected real case to set measurable goals, then run one small end-to-end
learning exercise with controlled, non-sensitive synthetic data, one shared
Dataset, one source workflow and two concurrent swarms. It must demonstrate
safe local use, controlled recovery and end-to-end proof for an identified
test request.

Success means no unsafe start, incorrect ready signal, uncontrolled duplicate
external action, unauthorised data exposure or central Dataset access during
measured traffic. The team must also agree numeric targets for time to usable
data, diagnosis time, invalid starts prevented and cleanup/recovery time.

**Current status:** Independently reviewed and green for an internal concept
decision only. It is not yet approved for architecture, implementation,
security qualification, production scale or release. See the
[team decision brief](managed-test-data-team-review-brief.md) and
[planning wireframes](managed-datasets-wireframes/README.md) for supporting
detail. If this summary conflicts with a normative specification, the
specification wins.
