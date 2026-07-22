# Managed Datasets — Team Brief v3 (comparison draft)

Status: proposed design; not implemented or qualified

## Decision required

Approve implementation planning for Managed Datasets as a bounded module
inside the existing Orchestrator service. The first release uses PostgreSQL and
adds no application container.

## Essential definitions

| Canonical term | Status | Plain meaning | Not the same as |
|---|---|---|---|
| Managed Dataset | `PROPOSED` | Live reusable test records plus the lifecycle state PocketHive uses to keep them safe and available | A file, database table or scenario bundle |
| Dataset package | `PROPOSED` | Versioned blueprint for exactly one kind of Managed Dataset; it owns definitions but no live records | An application, Docker container, archive or database |
| Dataset Space | `PROPOSED` | Authority boundary for one system-under-test environment; it controls access, classification, quotas and allowed storage profiles | A folder containing Dataset packages or records |
| Dataset registration | `PROPOSED` | Deployment link from one Dataset package version to one Dataset Space and one explicit storage configuration | Publishing a Dataset package or creating records |
| Scenario requirement | `PROPOSED` | Scenario-local statement of the fields and behaviour the scenario needs | A reference to a concrete Managed Dataset, swarm or other scenario |
| Scenario Binding | `PROPOSED` | Explicit mapping from each Scenario requirement to one compatible Dataset registration | Automatic discovery by name or alias |

## Why this matters

Some tests need thousands of records that are slow or expensive to create.
Individual scenarios often repeat this setup and cannot prove that existing
records remain safe to use.

Managed Datasets let producer swarms maintain records for independent consumer
swarms. PocketHive keeps supply near a target, survives application restart and
blocks unfit records. This reduces repeated setup and exposes dependencies.

## Proposal

- Store each definition as an independent, versioned Dataset package.
- Connect scenarios through Scenario requirements and explicit Scenario
  Bindings. Scenarios never discover or list one another.
- Keep first-release records and lifecycle state in PostgreSQL.
- Let producer swarms call the system under test. Orchestrator coordinates the
  work but does not make those business calls.
- Hydrate approved local snapshots before traffic; measured requests use
  worker-local data.

## Where it sits

~~~mermaid
flowchart LR
  UI[Operator interface or agent tools] --> SM[Scenario Manager<br/>Dataset packages, Dataset Spaces,<br/>Dataset registrations and Scenario Bindings]

  subgraph O[Existing Orchestrator service]
    MD[Managed Dataset module<br/>records, supply, readiness and recovery]
  end

  PG[(Existing PostgreSQL)]
  SC[Swarm Controller<br/>lifecycle and work routes]
  P[Producer swarm]
  C[Consumer swarm]
  SUT[System under test]

  SM --> MD
  MD <--> PG
  MD -->|bounded supply work| SC
  SC --> P
  P -->|create or refresh| SUT
  P -->|commit results| MD
  C -->|background snapshot| MD
  C -->|local-data traffic| SUT
~~~

| Concern | Owner |
|---|---|
| Definitions, Dataset Spaces, Dataset registrations and Scenario Bindings | Scenario Manager |
| Records, supply, readiness and recovery | Managed Dataset module in Orchestrator |
| Swarm lifecycle and work routes | Swarm Controller |
| Create, refresh and validation calls | Producer swarm |
| Request-time selection | Consumer worker using local memory |

## Example

A payment test needs 50,000 cards:

1. An operator publishes a card Dataset package and creates a Dataset
   registration in the integration-test Dataset Space.
2. The payment scenario declares a Scenario requirement. Its Scenario Binding
   selects that Dataset registration.
3. PocketHive asks a producer swarm to create bounded batches until the target
   is reached.
4. Confirmed successes enter the primary Managed Dataset. Confirmed failures
   may enter a separately authorised remediation Managed Dataset. Unknown
   outcomes are reconciled rather than counted as success.
5. Consumer workers activate an approved local snapshot before traffic.

## Included and not included

| First release | Later or excluded |
|---|---|
| PostgreSQL-backed reusable records | Managed Redis capability profile |
| Optional scenario-level requirements file; every listed requirement is mandatory | Per-requirement optionality or automatic discovery |
| Web and socket producer outcome classification | Exclusive or single-use records |
| Supply, refresh, validation and restart recovery | High-availability or backup-restore claims |
| Real operator and agent-tool authoring and status | Raw record or secret access |
| Existing PocketHive application containers | Separate Dataset application container |

A scenario without `datasets/requirements.yaml` has no Managed Dataset
dependency, Scenario Binding or readiness gate.

## Main trade-off

Co-location avoids another container. Managed Dataset state and its outbox
record—the message instruction—commit through one connection in one local
PostgreSQL transaction. A relay sends it after commit. The module shares
Orchestrator availability. Its dedicated schema, role, connection pool and
bounded executors isolate load.

## Next step

Approve the ownership model, freeze the executable contracts and build one
non-sensitive example. Claim support only after correctness, restart and
50,000-record performance requirements pass.

## Technical detail

- [Normative specification](managed-test-data-lifecycle-generic-spec.md)
- [Design decisions](managed-datasets-team-design-overview.md)
- [Architecture proposal](../architecture/sut-dataset-simulation-model.md)
