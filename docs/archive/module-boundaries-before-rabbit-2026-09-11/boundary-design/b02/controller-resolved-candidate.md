# Controller RESOLVED candidate gate — 2026-09-11

Status: implemented and tested, pending separate review. Base: 2e5b691e.
This closes the implementation of the Controller gate, not full B02 acceptance.

## Owner and consuming flow

WorkerWorkConfigurationPort remains the worker-planning boundary. Its existing adapter
receives WorkConfigurationParser through its constructor; startup obtains the parser
from CurrentWorkConfigurationProviders in the existing work-config-composition module.
Controller adds that module dependency. No service-local provider inventory, parser,
settings DTO or field validator was introduced. Neutral parsing delegates selected
settings to WorkInputSettingsParser/WorkOutputSettingsParser ports.

Composition still resolves topology, materializes Rabbit settings, applies supported
local/dataset overrides and freezes connections through their existing owners. After
final bootstrap projection the adapter calls the neutral parser in RESOLVED mode on
exactly the map it returns. Problems or deferred paths prevent returning the result.
SwarmWorkerSpecFactory propagates that failure; SwarmRuntimeCore prepares worker plans
before accepted-context writes, topology effects, container starts and bootstrap fanout.
No selector is inferred for missing input/output blocks and no invalid candidate is
silently repaired. Earlier declaration preflight remains distinct from this final gate.

The parser's typed result supplies validation evidence; it does not replace the
adapter-owned environment/bootstrap projection with a second mapper. No adapter field
normalization or domain state ownership moved to the Controller gate.

## Behavioral evidence

- WorkerWorkConfigurationAdapterTest rejects missing IO, unknown selectors, settings
  attached to NONE, and a Redis output with only a connection and no complete settings.
  A successful final scheduler bootstrap passes the canonical RESOLVED parser, with
  supported environment override behavior and unchanged sources.
- SwarmLifecycleManagerTest starts a valid accepted plan, then rejects missing input,
  unknown output, unselected settings and incomplete Redis output, in addition to prior
  selector/null regressions. Worker identities, SUT, RUNNING/readiness and pending state
  remain unchanged; no Rabbit or container-creation effects occur.
- The bootstrap fanout test decodes the actual config-update payload, validates it with
  the canonical parser and compares its selectors/rate with the environment passed to
  container creation. Existing Rabbit topology/settings/environment and CSV/scheduler/
  Redis connection/dataset tests retain their agreement checks.
- Legacy success fixtures now explicitly declare both IO directions. Connection-only
  Redis output fixtures supply complete write settings. These are literal test inputs,
  not production defaults or a fixture helper that infers missing configuration.

## Validation and limits

81 selected contract, adapter, factory, lifecycle, connection, runtime-core and existing
import-boundary tests passed, zero failures/errors/skips. Log:
`/tmp/ph-candidate-tests4.log`. No new source scanner or bean-identity test.

Remaining startup-producer and runtime/request-template reconciliation stays in the
B02 plan. This gate validates the final bootstrap inputs/outputs; it does not claim
full arbitrary bee.env authoring parity, live worker delivery or B03 state extraction.
No commit, push or deployment. Separate review must check actual calls, remaining
owners, ordering and tests before accepting the gate.

Full root package passed with tests skipped in offline mode using cached dependencies
(`/tmp/ph-candidate-package-offline.log`). The first online package attempt stopped on
read-only Maven cache metadata, not a compilation failure. Documentation site build
passed (`/tmp/ph-candidate-docs.log`); git diff --check passed.

## CG-R1 correction — one Redis output projection

The separate review found that write/target environment overrides were absent from the
validated bootstrap. RedisOutputEnvironment now owns the output candidate, environment
export and resolved bootstrap mapping in redis-config. Controller injects it and deletes
its prior scalar/indexed Redis output export helpers. RedisConfigurationParser retains
all field rules; connection composition retains its original owner.

Scalar sourceStep/pushDirection/maxLen/defaultList/targetListTemplate overrides are read
with raw Spring property lookup into the candidate before export and connection freezing.
After final Spring expansion the same candidate is parsed into resolved settings and
projected into bootstrap. Non-string declared types remain available to canonical
validation; environment encoding cannot make invalid raw types valid.

Routes remain in config. Environment route-list overrides, including indexed and dotted
forms, explicitly fail in declaration preflight. SpringConnectionEnvironment supplies
canonical property-tree presence using Spring's own name representation; it does not
define Redis policy. Declared route placeholders resolve through final properties.
No output rule was embedded in Controller and no extra connection resolver was added.

Regression tests reproduce invalid and valid differing output overrides, aliases and
placeholders; verify source preservation, route projection and invalid raw route values;
and preserve accepted lifecycle state without effects on rejection. The lifecycle test
compares emitted Redis bootstrap data with actual container environment for LPUSH.
Correction implemented pending separate review; historical CG-R1 report remains intact.

CG-R1 correction verification: 85 selected tests passed, zero failures/errors/skips
(`/tmp/ph-redis-output-final2-tests.log`). Full root offline package with tests skipped
and documentation build passed (`/tmp/ph-redis-output-package.log`,
`/tmp/ph-redis-output-docs.log`). git diff --check passed. No commit or deployment.
