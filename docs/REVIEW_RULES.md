# Review Rules

## Purpose

This document is the reviewer checklist for non-trivial PocketHive changes. It
is broader than the short project invariants stored in HiveMind.

Review in this order:

1. non-negotiable rules in `AGENTS.md`,
2. `docs/ENGINEERING_RULES.md`,
3. the authoritative architecture, REST, AsyncAPI, schema, and lifecycle docs,
4. the checks below.

A violation of a hard project invariant is blocking. A review-rule violation is
also blocking when it creates ambiguous ownership, hidden behavior, or a new
kitchen sink.

## Required review passes

Run the plan pass before implementation and revisit it when scope changes. Run
all six passes before accepting a non-trivial change; the sections below provide
the detailed criteria. Record findings or a concise pass/not-applicable result
with evidence. A successful pass is not a waiver of another pass or an invariant.

1. **Plan review:** Will the plan produce the requested outcome? Identify owners,
   dependencies, migration/deletion steps, and observable acceptance conditions.
   Check that it removes the cause rather than moving it, and that excluded work
   is not silently needed for success. Distinguish inherited debt from regressions.
2. **Style-guide check:** Does the change follow AGENTS.md, ENGINEERING_RULES.md,
   and the applicable Java/UI guidelines? Check file responsibilities, boundaries,
   naming, contract types, and accurate responsibility headers, not formatting alone.
3. **Conciseness check:** Can the same behavior be expressed with fewer concepts,
   branches, layers, duplicated rules, or dependencies? Prefer a simpler design;
   do not remove validation, verified postconditions, diagnostics, or failure cases
   merely to shorten code. Line count is not the objective.
4. **Security check:** Does the affected flow follow the project's security and
   authorization contracts? Check trust boundaries, least privilege, scope and
   tenant isolation, path/input handling, and secret exposure where applicable.
   Explain any relevant unverified boundary; do not infer approval from old memory.
5. **Library check:** Can existing project or standard-library facilities solve
   this clearly without a new dependency? Justify additions by benefit, maintenance,
   licensing, and security implications. Do not replace a suitable maintained
   library with bespoke cryptography, protocol machinery, or duplicated utilities
   simply to reduce the dependency count.
6. **Readability / maintainability check:** Can another contributor understand
   ownership, data flow, failure behavior, and how to change this safely? Prefer
   explicit types, names, and straightforward control flow; allow more lines when
   they make these clearer. Reconcile this with the conciseness pass explicitly.

## 1. Scope and durable direction

Check:

- the change matches the requested scope,
- durable behavior is documented before implementation,
- public REST, event, schema, routing, and filesystem changes update their
  canonical contract first,
- unrelated cleanup has not obscured the change.

Reject when behavior exists only in code, a test fixture, or conversation.

## 2. File shape and responsibility

Check:

- one Java production type per file, except the narrow private nested-type
  exception,
- one TypeScript/React runtime module or component concern per file,
- one clear responsibility per file,
- required responsibility headers exist and remain accurate,
- listeners and controllers are thin boundaries,
- handlers, state machines, projections, repositories, and adapters are
  separated,
- existing kitchen-sink files are reduced rather than expanded.

Reject when:

- a file becomes or remains the convenient home for unrelated behavior,
- a public nested DTO/enum or `*Contracts` bag is introduced or expanded,
- transport, domain decisions, persistence, and response construction are mixed,
- a responsibility header is widened to avoid extraction.

## 3. Authority and SSOT

Search the whole repository for every affected responsibility.

Check that there is exactly one owner for:

- wire and filesystem contracts,
- parsing, validation, normalization, and mapping,
- routing and topology construction,
- effective configuration and path resolution,
- state transitions and operation terminalization,
- success postconditions and terminal evidence.

Two active authorities are a critical blocker unless architecture explicitly
defines non-overlapping ownership.

## 4. Control plane

Check:

- routing keys come from the shared routing utility,
- listener code decodes and dispatches but does not execute domain workflows,
- every signal/result/outcome/status uses the canonical codec,
- correlation, idempotency, concrete target, and `runId` are matched at every
  acceptance boundary,
- duplicate delivery continues existing execution or replays stored evidence,
- status-full and status-delta retain distinct documented semantics,
- only the canonical operation owner terminalizes and publishes public outcome.

Reject silent duplicate drops, stale-run acceptance, hand-built routing, or
terminal evidence assembled by multiple services.

## 5. Configuration and infrastructure boundaries

Check:

- required adapter and settings are explicit and validated,
- no fallback chain or compatibility heuristic was introduced,
- one resolver owns queue names, paths, exchange names, and effective settings,
- domain/application code depends on ports rather than Docker, RabbitMQ,
  filesystem, database, or Spring implementations.

### Mandatory boundary review — blocking rules

These obligations are checked in the separate review task. Implementation goals
perform work, tests and evidence, then hand off; they do not run a self-review loop.

1. **No custom source-language parser or heuristic architecture validator.** Do not
   build regex/token scanners, preparsers, package-name classifications or generic
   method-name blacklists to decide whether application code respects ownership or
   performs IO. Do not recreate a removed scanner in Java, ArchUnit or another script.
   Off-the-shelf tools such as Maven Enforcer and ArchUnit are allowed.
   Standard build tooling and focused declarative architecture tests may check exact
   dependency rules; they do not establish behavioral compliance.
   The sole human-approved exception is the repository import test
   documented below; it does not authorize any additional scanner.
2. **Name and verify the sole owner.** For every changed responsibility, identify its
   canonical contract, implementation and all consumers. Search the repository for
   alternative implementations and follow their actual call paths. Moving a class or
   changing its package is not proof that duplication has been removed. Competing
   active authorities block approval under the SSOT rule.
   Apply the [responsibility workflow](ai/RESPONSIBILITY_WORKFLOW.md#separate-review-task)
   and provide its per-responsibility evidence in the review report. Check the stable
   architecture ID/section, header and implementation agree, including current versus
   target ownership. A bare "SSOT checked" verdict is insufficient; disagreement or
   missing required evidence blocks acceptance.
3. **Trace effects, including constructors and overloads.** Inspect actual filesystem,
   network, broker, database and process operations, configuration/environment reads,
   and resource-name construction. Establish where each runs and which port/adapter
   owns it. A java.util namespace, an innocent method name or an allowed value type
   does not establish purity. Review the selected constructor/overload and delegated
   calls; distinguish packaged contract resources from runtime infrastructure access.
4. **Review composition at the consuming path.** Check which implementations Spring
   or other bootstrap code selects, whether the consumer receives the configured port,
   and whether direct construction/static lookup bypasses it. Enforce permitted
   imports/dependencies and review actual source against the architecture and headers.
   Follow the boundary-verification policy below; do not require tests of bean/module
   selection or identity as evidence that a responsibility has the correct owner.
5. **Verify state and behavior across transitions.** Identify the single state writer,
   derived projections, update ordering, concurrency, disable/re-enable and completion
   postconditions. Check changes occurring between ticks/callbacks, not just one steady
   snapshot. Require regression evidence for the reported defect and affected behavior.
   For a concurrency finding, identify the actual entrypoints, caller threads and
   applicable configuration that permit the interleaving. A test that invokes public
   methods from extra threads demonstrates conditional behavior; it does not alone
   establish a reachable application defect. Check the documented
   [worker CONTROL execution model](ARCHITECTURE.md#worker-control-command-execution)
   where applicable. Revalidate that model when the change alters callers or concurrency.
6. **State the evidence and limits.** Report inspected owners/call paths, relevant
   before/after behavior, tests run and unverified boundaries. A passing build, scanner,
   negative fixture or lack of findings is not proof of complete IO/SSOT isolation.
   Unverified required acceptance conditions block approval; explicitly deferred work
   must remain named rather than silently treated as completed.

The root Maven Enforcer configuration is the authority for artifact dependency bans.
The architecture document owns responsibility boundaries; this checklist owns their
review requirements. Do not maintain a parallel generated policy claiming to decide
all architectural or behavioral violations.

### Boundary verification and test value

Human decision, 2026-09-08: module responsibility and port/adapter ownership are
enforced through permitted imports/dependencies, architecture/header discipline and
separate source review with evidence. Do not add tests whose purpose is to assert
that a named module, bean, factory or implementation is selected, registered or used.
Do not mirror bootstrap code or inspect private factory fields to certify ownership.
Absence of those tests is not a review finding or a release gate. Do not substitute
a mandatory deployment check whose sole purpose is proving the same internal wiring.

Each extracted class or adapter must have unit tests for its owned behavior. Move or
adapt existing behavior tests with that responsibility instead of duplicating them.
Test a port's behavioral contract through its implementations; a declaration-only
interface does not need an artificial test of its existence. Add component tests
where collaboration between real components has meaningful observable behavior.
Assert contract inputs, outputs, errors and effects, so tests survive internal moves.

Boundary verification itself consists of architecture documentation aligned with
responsibility headers, the one simple repository import test, and review rules with
concrete source evidence. Existing standard dependency checks retain their scope.
Do not build a separate suite around temporary refactor wiring, package placement or
class relationships. This distinction does not exempt extracted behavior from tests.

Keep tests for concrete contracts, observable behavior and reproduced defects. State
the failure they detect: for example startup rejecting missing required configuration,
invalid wire rejection, a disabled capability causing no external effect, or a pending
request surviving updates between ticks. A Spring test is justified by that behavior,
not by its ability to enumerate beans. Wiring changes alone do not require new tests.
The existing single import test and standard dependency/architecture checks remain
within their approved scope. This policy does not waive ownership rules or excuse
missing evidence for a required behavioral outcome.

### Sole source-scanning test exception

Human approval: 2026-09-08, clarified to cover all Java modules. Keep only
`RepositoryImportBoundaryTest`
(`common/control-plane-core/src/test/java/io/pockethive/architecture/RepositoryImportBoundaryTest.java`)
as the custom architecture source-scanning exception. Its one inline rule table owns
the source import restrictions: module-path regex, forbidden-import regex and rule ID.
It scans conventional `src/main/java` sources in repository Maven modules, including
new/untracked sources. Test-source imports are outside this production check; fixture
modules have explicit scope in the rules. Its location in control-plane-core is only
the Maven execution host, not its scan scope. Normal root `mvn test` runs it.

Rules cover infrastructure imports in core/contracts, imports of infrastructure
clients outside their current owning modules, CP-to-Work imports and test-only imports
in production. Existing pre-migration infrastructure owners are explicit in the table;
tighten their scope with the corresponding migration, not by adding per-file waivers.
The root POM continues to own artifact dependency bans, including transitive ones.

This is a JUnit test using one import regex, not ArchUnit or a Java parser. It checks
ordinary, static and wildcard import declarations. It does not resolve wildcard
contents, fully qualified usages, reflection, indirect calls or runtime effects;
import-shaped text inside multiline comments/strings can match. A pass is only
evidence for the declared import rules, never proof of SSOT or working behavior.

Keep the scanner and rule table small and in this one file. Do not add sibling
scanners, a parser, a framework, a policy DSL/generated inventory, method-call
blacklists or semantic ownership inference. New import restrictions belong in this
same table. Broader checking uses standard tools, behavioral tests and separate review.

## 6. Failure behavior and observability

Check:

- failure is explicit and does not claim an unverified effect,
- retries and timeouts are bounded and safe under at-least-once delivery,
- terminal evidence reports actual missing/non-converged resources,
- correlation context is preserved in logs and events,
- exceptions are translated at boundaries rather than swallowed.

## 7. Tests

Check:

- tests use official ingress/API paths where required,
- producer-derived wire payloads are validated by the canonical codec/schema,
- focused tests cover affected handler/state-machine behavior and concrete defects;
  extraction alone does not require a new test,
- wrong target, wrong controller instance, stale `runId`, duplicate delivery,
  timeout, and late result paths are covered where relevant,
- test fixtures and E2E audits do not duplicate production validators or outcome
  calculators,
- test classes and step definitions are not kitchen sinks.

## 8. Verification

Check:

- commands cover the changed surface,
- contract checks and relevant module tests passed,
- integration/E2E evidence uses the supported environment boundary,
- unverified areas and existing failures are stated explicitly,
- `git diff --check` passes.

## Suggested severity

Critical/blocking:

- competing SSOT authorities,
- state or terminalization owned by multiple components,
- contract/routing divergence,
- stale identity accepted as current state.

High/blocking:

- new or expanded kitchen-sink class,
- domain behavior in transport/controller code,
- silent duplicate suppression,
- missing lifecycle postcondition evidence.

Medium unless impact raises severity:

- public contract bags and misplaced contract versions,
- oversized tests that obscure responsibility boundaries,
- missing responsibility header on materially changed runtime code.

## Review output

State at least:

- findings ordered by severity, or `no findings`,
- canonical owners checked and repository-wide searches performed,
- commands and tests run,
- unverified areas,
- merge recommendation and remaining risks.

## Relationship to HiveMind

HiveMind holds a short set of stable, enforceable project invariants. This file
holds the broader diagnostic checklist. Review findings worth preserving should
be recorded through `docs/ai/HIVEMIND_WORKFLOW.md`.
