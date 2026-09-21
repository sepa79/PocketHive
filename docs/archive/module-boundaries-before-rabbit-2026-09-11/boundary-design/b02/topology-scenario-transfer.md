# Topology naming and Scenario neutral validation — 2026-09-10

Status: implemented and tested; awaiting separate review. This is the human-authorized
two-point transfer, with point 3 enforced through removal of old authorities, call-path
inspection, import restrictions and behavior evidence. It does not accept full B02 or
activate the remaining complete Controller Work candidate gate.

Checkpoint: `ee424015` plus the existing uncommitted B02 work and preceding Controller
composition extraction. No commit, push, deployment or automatic review loop performed.

## Ownership and actual paths

| Responsibility | Consumer → port → owner | Removed authority / remaining projection |
|---|---|---|
| Work resource names (RESP-WORK-RESOURCE-NAMES) | WorkerWorkConfigurationAdapter and SwarmWorkTopologyManager → injected WorkResourceNamesPort → PrefixedWorkResourceNames in topology-core | Concatenation removed from ControlPlaneContainerEnvironmentFactory. Its existing collection/settings helpers and SwarmControllerProperties.Traffic getters are read-only delegates to the same owner, not alternative formulas. |
| Rabbit authored and resolved settings (RESP-WORK-RABBIT-SETTINGS) | WorkConfigurationParser → WorkInputSettingsParser/WorkOutputSettingsParser → Rabbit providers; Controller adapter → RabbitWorkSettingsBootstrap | Removed destination acceptance/deferment from AUTHORING and the duplicate Rabbit-specific validation-result wrappers. RabbitInputTuning/RabbitOutputTuning represent authoring success; physical settings remain separate RESOLVED types. |
| Worker environment | Controller adapter → RabbitWorkSettingsBootstrap → RabbitWorkEnvironment | Environment addresses and tuning project the materialized result. Overrides through known Rabbit property names or Spring aliases fail explicitly; connections retain their separate override contract. |
| Scenario validation (RESP-SCENARIO-VALIDATE) | ScenarioBundleValidator → WorkConfigurationFindings → injected WorkConfigurationParser → configured provider ports | Deleted local IO selector validation, per-adapter parser construction and semantic assembly. Generic catalogue validation excludes both Work roots, including selection, required fields, options, types and ranges. |

Startup selection: ScenarioWorkConfigurationComposition already exposed the neutral parser;
its constructor injection now reaches ScenarioBundleValidator. CurrentWorkConfigurationProviders
is the existing shared provider inventory. WorkerWorkConfigurationComposition explicitly
supplies the configuration adapter and name-resolution port; SwarmLifecycleManager forwards
the same port to Rabbit provisioning. No new module or external library was added.

The neutral parser requires both Work roots and explicit selectors. AUTHORING may pass an
omitted settings block as an empty map to its selected provider; that provider still decides
required fields. Explicit invalid blocks fail. Rabbit's empty tuning uses existing canonical
defaults and creates no physical/deferred topology fields. The canonical InputLifecyclePolicy
preflight remains ahead of provider validation, preserving removed-control diagnostics.

48 repository scenario files now explicitly declare previously omitted IO selections.
The migration copied the effective selections from each named service's application.yml;
it adds no runtime inference or fallback and changes no logical bindings or worker behavior.
The repository scenario-validation test exercises all those actual bundles.

## Failure and effect trace

SwarmRuntimeCore.prepare builds worker plans and a local planned state before assigning the
accepted context, declaring topology, registering bootstrap or provisioning workers.
SwarmWorkerSpecFactory delegates Work composition through its port. Invalid Rabbit tuning
therefore fails before those effects. Declaration preflight also rejects conflicting Rabbit
environment settings before the external network-context supplier is read.

The configuration adapter asks the naming port for explicit names, feeds those values to
RabbitWorkSettingsBootstrap and exports that result through RabbitWorkEnvironment. The
connection resolver freezes the completed environment; spec assembly returns that environment
and the corresponding bootstrap. SwarmWorkTopologyManager consumes the same naming port for
queue declarations and bindings. The component test compares actual declared resources,
worker environment and bootstrap rather than checking bean identities.

Scenario's authoring result determines validation findings and bundle availability. This
transfer does not claim that all Scenario file writes are transactional validation gates:
ScenarioService retains its existing descriptor-write/reload lifecycle. Template/file/reference
checks remain with the existing Scenario and request-template owners.

## Verification

Targeted reactor: **141 tests passed**, zero failures/errors/skips. It includes resource
naming (2), neutral configuration contracts (14), Rabbit settings/bootstrap/environment (14),
existing import boundary (2), Control Plane environment projections (5), Scenario validation
and service behavior (46), and Controller planning/lifecycle/topology/property behavior (58).
Two additional Scenario projection cases were then added; all four projection tests passed.
Total distinct verified cases: **143**.

Run:

```sh
./mvnw -B -ntp -pl swarm-controller-service,scenario-manager-service -am \
  -Dtest=PrefixedWorkResourceNamesTest,RabbitWorkEnvironmentTest,WorkTopologyConfigurationTest,RabbitWorkSettingsParserTest,RabbitWorkSettingsBootstrapTest,WorkConfigurationContractTest,SwarmWorkerSpecFactoryTest,WorkerWorkConfigurationAdapterTest,SwarmLifecycleManagerTest,ScenarioRepositoryValidationTest,InputSettingsValidationComponentTest,RedisConfigurationValidationComponentTest,WorkConfigurationFindingsTest,ScenarioServiceTest,RepositoryImportBoundaryTest,ControlPlaneContainerEnvironmentFactoryTest,SwarmControllerPropertiesBindingTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DargLine=-javaagent:/home/sepa/.m2/repository/org/mockito/mockito-core/5.17.0/mockito-core-5.17.0.jar test
```

Full `./mvnw -B -ntp -DskipTests package` passed, including compilation of downstream test
sources. Documentation build and `git diff --check` passed. No direct service-port checks.

Specific regression evidence:

- `rejectedRabbitPlanPreservesStateAndAllowsCorrectedStart`: invalid initial plan causes no
  provisioning/publication; valid correction starts; later invalid replacement preserves the
  accepted workers, SUT, RUNNING state, readiness and absence of pending configuration.
- `provisionedResourcesMatchWorkerEnvironmentAndBootstrap`: canonical names, declared Rabbit
  resources/bindings, worker environment and bootstrap agree, including whitespace normalization.
- `rejectsRabbitEnvironmentBypassesBeforeExternalContextLookup`: legacy exports and Spring
  aliases cannot supply a second Rabbit destination/settings authority.
- Rabbit parser and Scenario projection tests: tuning-only AUTHORING succeeds; physical names
  and topology placeholders fail; only actual authored expressions are deferred.
- Existing Scenario component tests retain one canonical error/deferred path for Redis,
  CSV, scheduler and removed controls; repository bundles remain valid after explicit migration.

## Source and import evidence

Repository-wide searches inspected Java callers of `queueName`, `hiveExchange`,
`swarmTrafficQueueName(s)`, `trafficQueueName(s)`, constructor references to the new port
implementation, and Scenario imports/construction of Rabbit/Redis/local parsers. The sole
queue concatenation owner is now PrefixedWorkResourceNames. Existing metrics, buffer guard,
work bindings and Orchestrator queue-list projections still delegate through their settings
facades to that same owner; this is not a second formula or a claim of full B04 extraction.

Only the existing RepositoryImportBoundaryTest table was extended: Scenario production imports
of rabbit.config, redis.config and work.local are now forbidden. topology-core already falls
under its infrastructure-free module restriction. The scanner was not expanded. These module
rules do not prove intra-service wiring; the consuming call paths above require separate review.

Remaining scope: complete Controller neutral candidate validation, remaining startup integration,
B02 acceptance, and full B04 provisioning/observation/cleanup ownership. Existing delivery
activation and unrelated Scenario persistence semantics were not changed or claimed verified.
