# Control Plane Contract & Integration Tests

The control-plane modules expose reusable routing, topology, and messaging DSLs that now ship with
explicit contract tests and integration coverage. Use the commands below when running CI locally or
updating automation scripts.

## Contract (golden) tests
Golden fixtures live under `common/control-plane-core/src/test/resources/io/pockethive/controlplane/`.
They cover routing keys, queue declarations, and emitter payloads emitted by the control-plane DSL.
Execute them with:

```bash
./mvnw -pl common/control-plane-core test
```

The build compares actual DSL output against the JSON fixtures and will fail if routing keys,
bindings, or payload structures drift.

## Spring integration tests
`common/control-plane-spring` uses an `ApplicationContextRunner` with a mocked `AmqpTemplate` to
verify that the auto-configured `ControlPlanePublisher` targets the configured exchange and routing
keys. Run the suite with:

```bash
./mvnw -pl common/control-plane-spring test
```

## CI integration
Both suites execute automatically as part of the existing Maven workflows. Running either
`./mvnw verify` or the module-specific Maven `test` goals above will execute the new checks, making
it easy to plug them into GitHub Actions or other CI runners without extra wiring.

## Repository import boundaries

`RepositoryImportBoundaryTest`, hosted in `common/control-plane-core`, scans Java
production imports across all repository Maven modules. Its inline table owns these
source rules; [review rules](../REVIEW_RULES.md#sole-source-scanning-test-exception)
define the scope and limits. It replaces the former ControlPlane source scanner.
Maven Surefire supplies the repository root explicitly. Module declarations come from
the root and nested POMs; traversal is limited to their `src/main/java` trees, so transient
JVM/build files elsewhere cannot interrupt the scan. No directory guessing is used.

Run through normal root `./mvnw -B -ntp test`, or the focused reactor:

```bash
./mvnw -B -ntp -pl common/control-plane-core -am test
```

Forbidden imports report the file, line and rule. This check does not prove runtime
behavior or replace relevant codec, startup, lifecycle or ingress behavior checks.
Module ownership/selection is verified by imports/dependencies and source review,
following [the boundary-verification policy](../REVIEW_RULES.md#boundary-verification-and-test-value).

## New acceptance system

`acceptance-tests` is built independently from the frozen legacy suite. It uses
Java 21, JUnit 5 and JDK HTTP against official ingress; canonical product contracts
remain the wire authority. Plain Maven tests verify framework behavior without a
PocketHive deployment. Live acceptance requires an explicit target file and group.
No missing-target assumption skips and no automatic legacy execution.

The [framework responsibility records](../architecture/acceptance-tests.md) own the
implementation boundaries. The [coverage ledger](acceptance-coverage.md) maps current
requirements to new evidence. The [replacement plan](../inProgress/e2e-test-system.md)
owns N0–N4, including deletion only after confirmed replacement. This does not alter
existing control-plane contract tests or authorize direct service-port stack checks.
