# Engineering Rules

## Purpose

This document defines mandatory implementation rules for active PocketHive code.
It is not optional style guidance.

These rules exist to prevent service and control-plane code from collapsing into
large classes that own transport, orchestration, state, persistence, projection,
and response construction at the same time.

The non-negotiable rules in `AGENTS.md` take precedence. Architecture and
protocol details remain owned by the authoritative documents listed there.

## Core rule

Implementation units must be strictly separated:

- one Java production type per file by default,
- one TypeScript/React runtime module or component per file,
- one clear responsibility per file,
- one authoritative owner per behavior or state transition,
- no kitchen-sink classes or contract bags.

Existing violations are debt, not precedent. A change must not add another
responsibility to an already mixed file. Extract the affected responsibility
before extending its behavior.

## File shape

### Responsibility header

Every new or materially changed runtime class, REST controller, message
listener, command/event handler, state machine, coordinator, boundary parser,
projection, repository, and infrastructure adapter begins with a concise
ownership header:

```java
/**
 * Responsibility: <one concern owned by this type>.
 * Must not: <adjacent concerns owned elsewhere>.
 * Contract: <closest durable contract document or schema>.
 */
```

The header is a local ownership contract. It must describe the implementation,
not excuse it. If new behavior does not fit without broadening the header,
extract another type and update the durable contract first when necessary.

### Java: one type per file

Every Java production class, interface, enum, record, and annotation gets its
own file by default. In particular:

- public, protected, and package-visible nested production types are forbidden,
- public DTOs and enums must not be grouped in `*Contracts`, `*Types`, or similar
  namespace-holder classes,
- request, response, evidence, state, action, and result types each get a named
  file in the package that owns their contract,
- a file name must match the type and responsibility it contains.

A private nested type is allowed only when it is small, has no independent
behavior or external consumers, and is meaningless outside its owner. Builders
that are part of a public API and reusable handlers are not covered by this
exception.

Generated sources are exempt only when the file is reproducibly generated from
the canonical schema or generator and is never edited manually. The generator
or schema remains the owner.

### TypeScript and React: one module concern per file

Each TypeScript/React file exports one primary runtime concern, such as one
component, hook, store, adapter, parser, client, or contract type. Do not use a
file as a namespace bag for unrelated components, hooks, types, and helpers.

Tiny props or private helper types may stay with their sole component when they
have no other consumer. Shared and durable boundary types get their own named
files. Barrel files may re-export types but must not implement behavior.

### One responsibility per file

A file owns one coherent concern, for example:

- one inbound transport adapter,
- one command or event handler,
- one lifecycle state machine,
- one application service,
- one projection or response mapper,
- one repository adapter,
- one configuration resolver,
- one contract type.

A file must not simultaneously own combinations such as:

- AMQP decoding, lifecycle execution, status projection, and journaling,
- HTTP mapping, authorization, scenario rendering, and lifecycle orchestration,
- runtime provisioning, RabbitMQ topology, worker readiness, and scenario
  execution,
- configuration parsing, domain validation, application, and acknowledgement,
- persistence, state transitions, and public response construction.

Moving methods without assigning clear ownership does not count as separation.

### Growth rule

Before adding behavior, answer:

1. Which type is the authoritative owner?
2. Does the behavior fit its responsibility header unchanged?
3. Does the type already mix transport, domain, state, projection, or storage?
4. Does the change require a new handler, application service, mapper, or port?

If the existing file is mixed or the header must broaden, create or extract a
type before adding the behavior. Do not add "just one more method" to a known
kitchen-sink class.

File size is evidence, not the architecture rule itself. A production file over
400 lines, a constructor with more than eight collaborators, or a type with more
than twenty methods triggers mandatory responsibility review. It may remain
only when the file still proves one coherent concern; size alone is never an
excuse to split one authority into competing owners.

## Boundary shapes

### Message listeners

An AMQP listener owns transport concerns only:

- receive bytes and routing metadata,
- invoke the canonical codec and routing parser,
- establish correlation/trace context,
- dispatch to one explicitly selected handler,
- apply the transport acknowledgement/error policy.

It must not execute lifecycle transitions, calculate convergence, mutate domain
state, assemble status projections, perform filesystem cleanup, or construct
terminal results. Those responsibilities belong to dedicated handlers and
owners behind explicit interfaces.

One handler owns one command/event family or one explicitly named workflow.

### REST controllers

A REST controller owns HTTP concerns only:

- request mapping and boundary validation,
- authentication/authorization invocation,
- delegation to an application service,
- mapping the application result to the documented response.

It must not render scenario templates, provision runtime resources, implement
lifecycle state machines, query infrastructure directly, or build independent
domain outcomes.

### State machines and coordinators

A state machine or coordinator owns one domain process. It may depend on ports,
but it must not also become a transport adapter, persistence implementation, or
read-model builder.

Each domain fact and operation success postcondition has exactly one writer.
Observers and projections are read-only and do not independently validate or
settle domain behavior.

### Contracts and codecs

- Durable wire contracts are defined in the canonical schema/spec first.
- Each Java wire type has one definition and its own file.
- Parsing, validation, normalization, and routing/envelope identity checks occur
  once at the canonical boundary.
- Tests consume the canonical boundary; they do not create test-only parsers or
  validators for the same contract.
- Contract version identifiers live with the contract they version, not in an
  unrelated catch-all class.

### Configuration and filesystem paths

- One validated properties type and resolver owns each effective setting.
- Services consume the resolved value; they do not rebuild names or defaults.
- Filesystem layout and path construction have one shared owner.
- No compatibility heuristics infer whether a caller supplied a prefix, suffix,
  swarm identifier, adapter, or protocol.

## Package and dependency direction

Prefer the following dependency direction:

1. wire/domain contracts,
2. domain state and ports,
3. application handlers and coordinators,
4. infrastructure and transport adapters,
5. composition/configuration.

Domain and application code must not depend on Spring AMQP, REST controllers,
Docker/RabbitMQ clients, or persistence implementations. Adapters may translate
boundary values once and delegate inward.

Avoid bidirectional dependencies and service-local copies of shared contracts.
If two modules both decide the same behavior, ownership is wrong.

## Tests

Tests follow the same separation rules as production code:

- one test class per production responsibility,
- handler tests target handlers, not a giant listener fixture,
- contract tests use producer-generated payloads and the canonical codec,
- E2E step definitions are split by bounded workflow,
- test helpers do not become alternate contract or state authorities.

A large production split must be accompanied by tests at the new boundaries.
Do not preserve a monolithic test solely because the previous production class
was monolithic.

## Review and enforcement

Reject a change when it:

- introduces or expands mixed responsibilities,
- adds another Java production type to an existing file outside the narrow
  private nested-type exception,
- creates or extends a public nested contract bag,
- puts domain behavior in a listener or controller,
- introduces another parser, mapper, resolver, state writer, or outcome builder,
- widens a responsibility header to make unrelated behavior appear acceptable,
- grows a known kitchen-sink file without first extracting the changed concern.

Architecture tests should enforce type-per-file, dependency direction, and
boundary restrictions where practical. Do not use broad allowlists to make
existing debt look compliant. Add mechanical enforcement as violations are
removed, and keep the review rule blocking until then.

## Default bias

When in doubt:

- split earlier,
- name the owner explicitly,
- prefer a few focused files over one large coordinator,
- preserve one state/contract authority while separating adapters and
  projections,
- make dependencies visible through small ports.
