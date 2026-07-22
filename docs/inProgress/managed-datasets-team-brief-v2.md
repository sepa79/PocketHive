# Managed Datasets — Team Brief (comparison draft)

Status: proposed design; not implemented or qualified

This file is a temporary comparison draft. It does not replace the current
Managed Dataset documents.

## Decision required

Approve Managed Datasets for implementation planning as a bounded module inside
the existing Orchestrator service, using PostgreSQL first and no new
application container.

## Why this matters

Some tests need thousands of records that are slow or expensive to create,
such as cards, accounts or authentication material. Today, individual
scenarios tend to create and manage their own data. This duplicates setup,
increases load on the system under test and makes it difficult to know whether
the available data is still safe to use.

Managed Datasets would let one producer swarm create and maintain test records
for several independent consumer swarms. PocketHive would keep the supply near
a defined target, recover its state after restart and stop unsafe records from
being used.

The expected outcome is faster test preparation, less repeated data creation
and a clear view of which scenarios depend on which data.

## Proposal

- Store Dataset definitions as independent, versioned blueprints.
- Connect scenarios to Datasets through explicit bindings; scenarios do not
  discover or list one another.
- Keep runtime records and lifecycle state in PostgreSQL.
- Let producer swarms call the system under test; Orchestrator coordinates but
  does not execute those calls.
- Download safe Dataset revisions to consumer workers before traffic so each
  request uses local data.

## Where it sits

~~~mermaid
flowchart LR
  UI[Operator UI or MCP] --> SM[Scenario Manager<br/>definitions and bindings]

  subgraph O[Existing Orchestrator service]
    MD[Managed Dataset module<br/>supply, records and readiness]
  end

  PG[(Existing PostgreSQL)]
  SC[Swarm Controller<br/>lifecycle and routes]
  P[Producer swarm<br/>creates or refreshes data]
  C[Consumer swarm<br/>uses local data]
  SUT[System under test]

  SM --> MD
  MD <--> PG
  MD -->|request bounded work| SC
  SC --> P
  P -->|business calls| SUT
  P -->|commit results| MD
  C -->|background download| MD
  C -->|measured traffic| SUT
~~~

| Concern | Owner |
|---|---|
| Definitions, Dataset Spaces, registrations and scenario bindings | Scenario Manager |
| Runtime records, supply, readiness and recovery | Managed Dataset module in Orchestrator |
| Swarm lifecycle and work routes | Swarm Controller |
| Calls that create, refresh or validate data | Producer swarm |
| Durable records for the first release | PostgreSQL |
| Request-time selection | Consumer worker using local memory |

## Essential definitions

| Term | Plain meaning |
|---|---|
| Dataset package | A versioned blueprint for one kind of Dataset; it contains definitions, not live records |
| Dataset Space | The controlled area for one test environment, defining access, classification and limits |
| Registration | The deployment record that connects one package version to a Space and storage configuration |
| Managed Dataset | The live records and lifecycle state created from a registered package |
| Requirement | A statement of the data a scenario needs |
| Scenario Binding | The explicit mapping from a requirement to a registered Dataset |

## Example

A payment test needs 50,000 cards:

1. An operator publishes the card Dataset blueprint and registers it in the
   integration-test Space.
2. The payment scenario declares that it needs cards; its Scenario Binding
   selects the registered Dataset.
3. PocketHive starts the producer swarm and asks it to create small batches
   until the target is reached.
4. Successful cards enter the main Dataset. Confirmed failures may enter a
   separately approved failure Dataset. Unknown results are checked again
   rather than counted as success.
5. Traffic workers download an approved revision and use cards locally.

## Included and not included

| First release | Later or excluded |
|---|---|
| PostgreSQL-backed reusable records | Managed Redis profile |
| Explicit scenario requirements and bindings | Automatic consumer discovery |
| HTTP and TCP producer outcomes | Exclusive or single-use records |
| Automatic supply, refresh and validation | High-availability or backup-restore claims |
| Real UI and MCP authoring/status | Agent access to raw records or secrets |
| Restart-safe Dataset state | Another Dataset application container |

Scenarios that do not contain a Dataset requirements file continue to run
without Dataset binding or readiness checks.

## Main trade-off

Orchestrator co-location avoids another container. Managed
Dataset state and its outbox record—the message instruction—commit through one
connection in one local PostgreSQL transaction. A relay sends it after commit.
The module shares Orchestrator availability. Its dedicated schema, role,
connection pool and bounded executors isolate load.

## Next step

If the team accepts the ownership model and trade-off, freeze the new
executable contracts and build one small, non-sensitive end-to-end example.
The feature cannot be described as supported until its correctness, restart
and 50,000-record performance criteria pass.

## Technical detail

- [Current normative specification](managed-test-data-lifecycle-generic-spec.md)
- [Current design decisions](managed-datasets-team-design-overview.md)
- [Architecture model](../architecture/sut-dataset-simulation-model.md)
