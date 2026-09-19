# B02 Rabbit connection export transfer — 2026-09-08

Status: implemented and tested, pending separate review. B02 remains open.
This task continues the approved B02 connection/configuration work with an independent
responsibility before the coupled WorkConfigurationParser migration.

## Owner and scope

Contract: [RESP-RABBIT-CONNECTION](../../../../architecture/runtime-responsibilities.md#resp-rabbit-connection).
`rabbit-config` has no production dependencies. RabbitConnectionSettings validates and
retains the existing host/port/username/password/virtualHost values; RabbitConnectionEnvironment
is their sole Java container encoder. The existing Maven Enforcer rule and import test
cover the module. The same import table now prohibits Spring AMQP configuration imports
outside control-plane-spring. No scanner or architecture test class was added.

RabbitConnectionConfiguration uses Spring Binder directly against that shared record.
Both service entrypoints explicitly import this bootstrap configuration. It adds no
defaults and contains no competing field validator. ContainerLifecycleManager and
SwarmLifecycleManager/SwarmWorkerSpecFactory now receive immutable settings.
ControlPlaneContainerEnvironmentFactory delegates the shared encoder; its old
populateRabbitEnv and requireRabbitPort methods were deleted.

The library does not open connections. Spring still owns client creation. The five-field
export is unchanged for valid values; credentials are not trimmed. Missing/blank values
fail when settings are constructed, instead of at container planning. Ports above 65535
now fail as well. No default RabbitProperties object can supply missing bootstrap fields.
The settings' toString hides values. Existing application YAML defaults are still upstream
input and remain debt for the wider explicit-configuration migration.

This extraction does not implement TLS/address-list propagation, change Work/CP delivery,
remove Rabbit clients from services, or close the other responsibilities still mixed in
the CP environment factory and lifecycle classes. It does not claim full Rabbit SSOT.

## Implementation searches

Repository searches for `RabbitProperties`, `populateRabbitEnv`, `requireRabbitPort` and
`SPRING_RABBITMQ_` identified the CP factory as the previous Java encoder and the two
launch paths as consumers. Production Java now has no RabbitProperties references or old
encoder/validator methods. The five environment assignments live only in the new encoder.
Application YAML/compose variables are producers/consumers, not another Java encoder;
the inspected Node tools do not implement this container connection export.

Commands included:

```bash
rg -n 'RabbitProperties|populateRabbitEnv|requireRabbitPort' --glob '*.java' --glob '!**/target/**'
rg -n 'SPRING_RABBITMQ_' --glob '*.java' --glob '*.js' --glob '*.mjs' --glob '*.ts' --glob '!**/target/**' --glob '!**/build/**'
```

These are implementation evidence for a later reviewer, not a self-review verdict.

## Verification

Before: `eb681ee7` plus the existing uncommitted B02/R1 correction, before this transfer.
The four existing environment/lifecycle/spec suites passed **43 tests**.

After: **58 tests passed, no failures/errors/skips**:

| Responsibility/behavior | Cases |
|---|---:|
| Required connection fields, port bounds and redacted text | 7 |
| Exact connection environment export with non-default values | 1 |
| Environment decode/export round trip and all missing-field failures | 7 |
| Existing CP participant environment composition | 5 |
| Existing Orchestrator container lifecycle | 8 |
| Existing Controller worker plans and lifecycle | 28 |
| Single repository import test | 2 |

Two old blank-host tests were removed from the CP factory and Controller lifecycle suite;
their rejection behavior moved to the canonical settings tests and bootstrap coverage.
No test asserts bean selection, implementation identity or private wiring. Lifecycle tests
use existing in-process doubles; no deployed services or direct service ports were used.

```bash
./mvnw -B -ntp -pl orchestrator-service,swarm-controller-service -am \
  -Dtest=RabbitConnectionSettingsTest,RabbitConnectionEnvironmentTest,RabbitConnectionConfigurationTest,ControlPlaneContainerEnvironmentFactoryTest,ContainerLifecycleManagerTest,SwarmLifecycleManagerTest,SwarmWorkerSpecFactoryTest,RepositoryImportBoundaryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Logs: `/tmp/b02-rabbit-before.log`, `/tmp/b02-rabbit-after.log`.
The initial after run exposed one unmigrated test fixture constructor; it was corrected
before the successful run. [Durable verification](rabbit-verification.txt) records results;
[current cumulative B02 source fingerprint](rabbit-source-changes.sha256) identifies the
Java/POM files including earlier transfers. Historical fingerprints remain unchanged.

The whole root reactor passed `./mvnw -B -ntp -DskipTests package` (production/test
compilation and packaging; tests skipped in this command). The documentation build
`npm --prefix docs-site run build` and `git diff --check` also passed. Logs:
`/tmp/b02-rabbit-package.log`, `/tmp/b02-rabbit-docs.log`.

The next responsibility remains WorkConfigurationParser: typed settings and connections,
AUTHORING/RESOLVED parity, full candidate validation before accepted state, Redis validator
removal, input enablement/default removal and producer migration. Template context/default
debt also remains. No B02 acceptance, automatic review loop, commit or deployment is implied.
