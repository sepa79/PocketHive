# Managed Datasets, explained

Status: in progress

This is a short introduction for the team. It explains the design without
trying to define every contract or implementation rule.

For exact requirements, use the
[Managed Test Data specification](managed-test-data-lifecycle-generic-spec.md).

## What problem are we solving?

Some tests need data that is expensive or slow to create.

For example, a PocketHive swarm may call a system to create 50,000 test cards.
Other swarms then need those cards to run payment traffic. The cards may need
refreshing, may expire, or may fail during creation.

Today, each scenario tends to manage this data for itself. That makes it hard
to:

- reuse data safely across scenarios;
- know whether enough valid data exists;
- recover after a restart;
- separate successful and failed records;
- understand which tests depend on which data.

A Managed Dataset solves this by giving PocketHive one durable, controlled way
to create, store and share test records.

## The idea

A Managed Dataset is a named collection of test records.

One swarm produces the records. Other swarms consume them.

PocketHive keeps the Dataset near a configured target size. If the number of
usable records falls below the target, PocketHive asks the producer swarm to
create or refresh more.

Consumers download a safe snapshot before running traffic. They use that local
copy while sending requests, so normal traffic does not wait for a database or
Orchestrator call.

The design uses several similar names for different things:

| Name | Plain meaning | Contains live records? |
|---|---|---|
| Dataset package | The versioned blueprint for one kind of Dataset | No |
| Dataset Space | The security and ownership boundary for one SUT environment | No |
| Dataset registration | The installation record that makes one package version available in one Space | No |
| Managed Dataset | The live records and lifecycle state created from that registered blueprint | Yes |
| Scenario requirement | A statement of what data a scenario needs | No |
| Scenario Binding | The explicit connection from that requirement to a registered Dataset | No |

## A simple example

Assume we need 50,000 cards for a payment test.

1. A Dataset package describes a card record and the fields it may expose.
2. An operator registers that package in the test environment.
3. A payment scenario declares that it needs card records.
4. A Scenario Binding connects that requirement to the registered cards
   Dataset.
5. PocketHive sees that the Dataset is below its target.
6. PocketHive starts the card-producer swarm if it is not already ready.
7. PocketHive sends the producer a small, bounded batch of work.
8. The producer calls the system under test to create cards.
9. Successful results go into the cards Dataset.
10. Confirmed failures may go into a separate failed-cards Dataset.
11. Results that cannot yet be proved successful or failed are held for
    reconciliation. They are not treated as success.
12. Traffic workers download an approved Dataset revision and select cards
    locally.

The same flow works for HTTP and TCP producers. The evidence used to decide
success differs by protocol, but the final outcomes are the same: completed,
failed, or not yet known.

### The journey at a glance

~~~mermaid
flowchart LR
  A[Dataset package<br/>versioned blueprint] --> B[Registration<br/>installs it in a Dataset Space]
  C[Scenario requirement<br/>says what data is needed] --> D[Scenario Binding<br/>selects the Dataset]
  B --> D
  D --> E[Orchestrator checks<br/>supply and readiness]
  E --> F[Producer swarm<br/>creates or refreshes records]
  F --> G[(Managed Dataset<br/>durable records)]
  G --> H[Traffic workers<br/>download a safe revision]
  H --> I[Workers use records locally<br/>while sending traffic]
~~~

The package describes the data. The registration makes it available in one
environment. The binding connects it to a scenario. Runtime records are created
only after those design-time choices have been validated.

## The definitions in more detail

### Dataset package

A Dataset package is a **versioned blueprint for one kind of Dataset**.

In practical terms, it is a folder of related definition files that Scenario
Manager validates and publishes as one unit. It is similar to a scenario
bundle, but it describes reusable data instead of a swarm.

The word “package” does not mean a running application, Docker container,
database, archive format or collection of live records.

It contains the record schema, field subsets, mappings, source details and
policies. It lives independently from scenario bundles:

~~~text
scenarios/managed-datasets/cards/
├── dataset.yaml
├── schema/
├── contracts/
├── mappings/
├── policies/
└── sources/
~~~

Each Dataset owns its own contracts. Changing the cards contract cannot change
another Dataset.

The package contains no live records, passwords or deployment settings.

For the card example, the package answers:

- What fields make up a card record?
- Which fields may a payment scenario use?
- How is a card-creation response mapped into those fields?
- Which producer flow can create or refresh cards?
- What rules decide whether the records are safe to use?

Publishing freezes an immutable package version. It does not create 50,000 card
records; those are created later by producer swarms.

### Dataset Space

A Dataset Space is the security and ownership boundary for a particular system
under test.

It answers questions such as:

- Which SUT environment does this data belong to?
- Who may use it?
- What data classification is allowed?
- Which storage types are allowed?
- What limits apply?

Several Datasets can share a Space, but data cannot silently move between
Spaces or SUT environments.

### Dataset registration

A registration installs one exact package version into one Dataset Space.

It gives the Dataset its name in that environment and explicitly selects its
storage adapter and settings.

PostgreSQL is the first full managed-storage implementation. Redis can be added
through its own explicit capability profile. PocketHive will never switch
between them automatically.

Using the card example:

- the package says what a card Dataset is;
- the registration says to make version 3 available as payment-cards in the
  integration-test Space using a named PostgreSQL configuration;
- the Managed Dataset is the actual set of card records maintained at runtime.

### Scenario requirement and binding

A scenario that needs Managed Dataset data declares only what it needs:

~~~text
scenarios/bundles/payment-traffic/
└── datasets/
    └── requirements.yaml
~~~

The file is optional. If it is absent, the scenario has no Managed Dataset
dependency, needs no Dataset binding or readiness check, and runs through the
existing scenario path.

If the file exists, it contains at least one requirement. Every requirement is
mandatory in the MVP. It does not name other scenarios or keep a list of
consumers.

A separate Scenario Binding connects that requirement to a real Dataset. This
is configured deliberately; it is not discovered automatically from matching
names. Every declared requirement must have exactly one valid mapping before
the scenario can run.

This keeps scenario bundles independent and makes the runtime choice visible.

## Where the work happens

The boxes below are deployment components. The Managed Dataset module is
inside the existing Orchestrator service; it is not another container.

~~~mermaid
flowchart TB
  USER[Operator or approved agent]

  subgraph AUTHORING[Definition and binding]
    UI[Operator UI]
    MCP[PocketHive MCP]
    SM[Scenario Manager<br/>packages, Spaces, registrations<br/>and Scenario Bindings]
  end

  subgraph ORCHESTRATOR[Existing Orchestrator service]
    OA[Orchestrator application<br/>admission and swarm coordination]
    MD[Managed Dataset module<br/>records, supply, readiness<br/>and recovery]
  end

  subgraph CONTROL[Existing swarm runtime]
    RMQ[RabbitMQ<br/>ph.control and WorkItem routes]
    SC[Swarm Controller<br/>lifecycle, topology and readiness]
    PS[Producer swarm<br/>calls the SUT]
    CS[Consumer swarm<br/>runs test traffic]
  end

  PG[(Existing PostgreSQL<br/>Managed Dataset authority)]
  SUT[System under test]

  USER --> UI
  USER --> MCP
  UI --> SM
  MCP --> SM
  SM -->|published definitions<br/>and bindings| OA
  OA <--> MD
  MD <--> PG

  OA -->|start or stop through<br/>the existing control path| RMQ
  RMQ <--> SC
  SC <--> PS
  SC <--> CS

  MD -->|bounded DATASET_SUPPLY<br/>through the WorkItem route| RMQ
  PS -->|create, refresh or validate| SUT
  PS -->|commit results through<br/>the Dataset API| MD
  CS -->|background snapshot download| MD
  CS -->|measured traffic using<br/>local records| SUT
~~~

There are two important boundaries in this picture:

1. Orchestrator decides **whether Dataset work is needed**. The producer swarm
   performs the SUT-specific work.
2. Consumer swarms contact the Dataset module only in the background. Their
   measured traffic uses records already held locally.

| Part | Responsibility |
|---|---|
| Scenario Manager | Dataset packages, Spaces, registrations and scenario bindings |
| Orchestrator | Dataset rules, records, supply decisions, readiness and recovery |
| Swarm Controller | Starting swarms, declaring their work routes and reporting readiness |
| Producer swarm | Calling the SUT to create, refresh, validate or remove data |
| Traffic worker | Downloading a safe revision and using it locally |
| PostgreSQL | Durable authority for the first managed-storage profile |
| UI and MCP | Authorised ways to manage definitions and inspect status |

The Dataset logic lives as a separate module inside Orchestrator. It is not a
new container. The module has its own boundaries, database schema and resource
limits so Dataset work does not become ordinary Orchestrator code.

Orchestrator coordinates the work. It does not call the SUT to create records;
producer swarms still do that.

## Why start and supply are separate

Starting a swarm and asking it to create data are different actions.

- The existing PocketHive control plane starts and stops the producer.
- After the producer reports that it is ready, the normal WorkItem route gives
  it a bounded Dataset supply request.

This prevents work being sent to a swarm that cannot receive it. It also keeps
Dataset behavior from creating a second control plane.

The producer's database receipt proves that records were committed. A RabbitMQ
acknowledgement only proves that a message was handled.

## How producer results are handled

A successful network call does not always mean that the business operation
succeeded.

For example, an HTTP call may return a response saying that the card is in the
wrong state. A TCP write may succeed even though the remote protocol later
rejects the request.

Each producer therefore uses an explicit result policy. It looks at the
protocol response and the business result, then chooses one outcome:

- **Completed:** commit the usable record to the main Dataset.
- **Failed:** optionally commit a safe, approved failure record to a separate
  remediation Dataset.
- **Pending or uncertain:** keep the operation for reconciliation.

Dataset destinations are configured and authorised in advance. A response
cannot invent a Dataset name.

The preferred worker output is DATASET_UPSERT. A worker that already needs a
different primary output can enable the shared Managed Dataset interceptor.
Both paths use the same validation and durable commit rules.

## What makes a Dataset ready?

Having enough rows is not sufficient.

PocketHive also checks that:

- the records have the required fields;
- they belong to the correct SUT environment, partition and pool;
- they came from an approved source;
- they are still valid for long enough;
- the consumer is allowed to see the required fields;
- every required worker has loaded the same approved revision.

These checks form the Dataset Fitness Contract.

The result is pass, fail, or unknown. Only a current pass can make a required
Dataset ready for a new run.

## What happens during traffic?

Before traffic starts, each worker downloads a bounded Dataset snapshot.

During a request, the worker:

1. selects a record from local memory;
2. checks that it is still safe to use;
3. inserts the allowed fields into the outgoing request.

It does not query PostgreSQL, Redis, RabbitMQ or Orchestrator for every request.
This is how the design protects traffic performance.

## What operators will be able to do

The UI will use real Scenario Manager and Orchestrator APIs. It will not contain
sample rows pretending to be live data.

Authorised users will be able to:

- list and inspect Dataset packages;
- create and edit drafts;
- validate and publish a version;
- retire a published version;
- remove an unreferenced draft;
- create and manage Spaces and registrations;
- connect scenario requirements to Datasets;
- see readiness, supply progress, failures and consumers.

Published versions and active history are retired, not edited or deleted.

PocketHive MCP exposes the same product services. An AI agent does not gain
extra Dataset authority and cannot read raw records or secrets.

## Failure and restart behavior

PostgreSQL is the durable authority for the first profile.

After a restart, PocketHive rebuilds Dataset state from PostgreSQL before
accepting new mutations. Duplicate messages reuse the same operation identity.
Stale workers and controllers cannot update newer state.

If PocketHive cannot prove whether an external call changed the SUT, it marks
the operation uncertain and reconciles it. It does not blindly repeat the call.

If PostgreSQL is unavailable, the Dataset becomes unavailable. PocketHive does
not fall back to files, memory or Redis.

## What is in the first release?

The first release focuses on:

- PostgreSQL-backed reusable records;
- explicit scenario bindings;
- package-local contracts;
- HTTP and TCP producer results;
- completed and failed Dataset routing;
- automatic supply and refresh;
- worker-local consumption;
- real UI and MCP authoring;
- restart-safe operation handling.

Later work may add managed Redis collections, exclusive leases, consumable
records, stronger recovery profiles and restricted-data support. These are not
silently included in the first release.

## What the team is approving

The important decisions are:

1. Dataset definitions are independent packages, not part of scenario.yaml.
2. Scenarios declare only their own requirements.
3. Scenario bindings are explicit, not automatic.
4. Each Dataset owns its contracts.
5. Dataset Spaces provide the shared SUT and authority boundary.
6. PostgreSQL is the first full managed-storage profile.
7. Redis remains available through an explicit, separately supported profile.
8. Dataset runtime logic is a bounded module inside Orchestrator.
9. Producer swarms make SUT calls; Orchestrator does not.
10. Workers use local snapshots on the traffic path.
11. Network success alone never proves record creation.
12. UI and MCP use real product APIs and data.

If the team agrees with those decisions, implementation can start by freezing
the executable contracts and building the smallest end-to-end card-style
example.

## Further detail

- [Normative design specification](managed-test-data-lifecycle-generic-spec.md)
- [Detailed team design guide](managed-datasets-team-design-overview.md)
- [SUT, Dataset and Simulation architecture](../architecture/sut-dataset-simulation-model.md)
