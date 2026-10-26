# Scenario validation simplification (#533)

Status: agreed nonbreaking implementation complete locally and independently reviewed, 2026-10-24.
Baseline: `3c2b0f9fd9ad7b71ca27084c8adfdb7ad4372836`.
Checkout: `/home/sepa/PocketHive-ph-ui`, branch `codex/sut-variable-binding`.
Issue: https://github.com/sepa79/PocketHive/issues/533.

## Scope and decisions

The maintainer rejected the previous configuration-format redesign and requested
its complete deletion. Preserve Scenario Protocol 2, existing YAML, `{{ ... }}`
expressions and the behavior of existing PocketHive scenarios. Do not introduce
`configBindings`, inline reference objects, public template metadata, compatibility
modes, a new parser or a validation framework. No scenario migration is required
for the repository corpus. The explicit maintainer decision of 2026-10-26 reserves
the identifier `vars` for Scenario Variables and prohibits named template
declarations using it; that restriction is the approved exception to prior
behavior preservation (see the decision checkpoint below).

Simplify validation along the existing section and owner boundaries. The Scenario
model already separates metadata, template, topology, traffic policy and plan.
Scenario Manager must not interpret the opaque Orchestrator-owned plan. Parsed
configuration and canonical request-template definitions supply validation input;
comments and unrelated bundle files must not create executable dependencies.

Keep configuration preparation and per-message rendering at their current times.
Controller preparation resolves variables with the existing full-value versus
embedded-string rules, nested paths, profile overwrite of config.vars, empty-map
gate and top-level SUT baseUrl handling. Preserve variable substitution inside
message fields. Do not enable strict Pebble variables or silently change optional
variables. Required variable resolution remains owned by ScenarioVariablesService.

Correct false authoring errors for ordinary data, comments and dynamic selectors.
These admission corrections intentionally change diagnostics for affected inputs,
while preserving the runtime behavior of existing scenarios. Trailing JSON and
numeric callId are independent admission mismatches included in this work.

## Owners and implementation order

1. Establish regressions for the reported false diagnostics, real unknown and
   optional variables, static/dynamic selectors, portable step admission and trailing
   JSON. Keep all 86 repository scenarios unchanged as a regression corpus.
2. Extract configuration preparation from SwarmController without semantic changes;
   delete the old methods when the caller moves. Keep ownership recorded in runtime
   responsibilities and responsibility headers.
3. Scope authoring validation to parsed sections. Delegate source-language work to
   the existing Pebble/SpEL compiler, request-template semantics to
   RequestTemplateParser and Work settings to WorkConfigurationParser. Do not replace
   raw scanning with another regex/token parser or duplicate field catalogue.
4. Replace arbitrary recursive selector discovery with the actual consumer contract:
   HTTP Sequence configured steps, Request Builder actual Work selectors. Dynamic
   expressions are deferred to execution, not enumerated from quoted arguments.
   Generator SIMPLE and HTTP headers have different destinations.
5. Share the runtime template-file decoder with authoring, retaining duplicate-key
   checks and the existing YAML policy while rejecting trailing JSON. Normalize
   numeric callId through the same binding behavior as runtime before applying
   HttpSequencePortablePolicy; do not skip other concrete checks because a field
   is unresolved.
6. Run relevant Java tests, repository scenario validation and the existing import
   boundary test for dependency changes. Request separate independent review under
   docs/REVIEW_RULES.md and docs/ai/RESPONSIBILITY_WORKFLOW.md. Record actual results
   and gaps; previous discarded implementation results are not evidence.

## Observable acceptance

- Existing YAML files and Scenario Protocol remain unchanged.
- Profile/SUT preparation produces the same values, types and failure behavior.
- Literal config.vars values, comments and metadata create no template dependencies.
- Real unknown references are still reported; optional/empty-map behavior is covered.
- Template syntax uses its canonical compiler and field/section ownership.
- Nested business callId/header-like keys do not select request templates.
- Literal selectors remain checked; dynamic selectors are handled at runtime.
- Runtime and authoring reject trailing JSON identically and apply portable policy
  to numeric identifiers consistently.
- No duplicate active resolver/parser remains after moving its callers.
- All existing repository scenarios pass validation; targeted regressions and
  independent review report their evidence and limitations.

## Operational constraints

PH stays stopped. Do not restart PP/PD, modify operational data, materialize or
migrate live scenarios. Preserve the pre-existing `.playwright-mcp/` directory.
No commit, push or PR without a separate explicit maintainer instruction.

## Evidence before implementation

Read-only audit: all 86 scenario descriptors parsed; 332 bee configurations inspected.
Seven existing scoped tests passed, including validation of all 86 bundles.
Independent feasibility review agreed that section splitting must preserve current
preparation scope and delegate to existing owners. This is baseline evidence only;
it does not approve an implementation or replace post-change verification.

## Current implementation checkpoint

The maintainer explicitly approved both remaining internal changes on 2026-10-23:
shared pure auth traversal (A), and owner-provided source projections with the
existing Work output parser observer (B). The earlier rejected operations did not
execute; this approval supersedes the pending-permission checkpoint.

Auth runtime and authoring now share `AuthProfileTemplateValues`; authoring does
not read env/file secrets. Native sources are supplied by explicit projections in
the existing templating module (generator, clearing template/selected schema,
invocation interceptors), the selected Redis output parser and the canonical HTTP
Sequence set model. DB parameter paths retain their real vars dependency through
DbParameterSource; SQL is not Pebble. Existing clearing schema file selection and
decoding have been moved intact to shared owners, with old active code removed.

ScenarioTemplateAnalysis owns canonical syntax validation and read-only source inspection; configuration source
selection, additional worker documents and request target projection have been
extracted from the mixed validator. Generic configuration syntax/AST walking is
removed. Bootstrap variable reads still use ScenarioConfigurationPreparation's
exact existing matcher over its original scope. Whole-value object headers/set
bindings are deferred without suppressing concrete neighbors. Optional variables
remain optional. HTTP Sequence selectors are literal after bootstrap; only actual
generator Work headers are per-message templates.

A proposed dependency on the worker SDK starter was removed after composition
review. Pure projections live in existing templating, with a dependency only on
existing redis-config; MessageBodyType moves to templating-api retaining its Java
FQN and enum values. Scenario Manager's application composition is unchanged.

## Final verification — 2026-10-24

- Broad selected Java run: **1824 tests in 248 classes, zero failures/errors/skips**,
  BUILD SUCCESS. Covered changed service/runtime modules, configuration preparation,
  shared request decoding, worker auth/interceptors, Redis/Work configuration and
  the existing repository import-boundary checks. Actual command is recorded in
  `/tmp/ph533-final-green-command.txt`; output: `/tmp/ph533-final-green-java.log`.
  The wrapper removed its disposable Redis/Rabbit fixture containers and network.
- After the final AST projection change: all selected Scenario Manager and templating
  tests plus the existing import-boundary checks passed: **461 tests in 45 classes,
  zero failures/errors/skips**, `/tmp/ph533-final-authoring.log`.
- After restoring the original missing-clearing-schema failure message/cause:
  the entire clearing test set passed: **86 tests in 14 classes, zero
  failures/errors/skips**, `/tmp/ph533-final-clearing.log`. The newly exposed
  wrapping regression was reproduced first in `/tmp/ph533-clearing-missing-before.log`.
- These runs overlap; their counts must not be added into a unique test total.
  An earlier copied `ConfigMergerTest` selector matches no current test class and
  is not evidence of execution. Configuration preparation is covered by its actual
  ScenarioConfigurationPreparationTest and existing runtime/configuration tests.
- All **86 repository scenario bundles** pass Scenario Manager validation using
  the existing repository-corpus test. `git diff --exit-code HEAD -- scenarios`
  confirms their files are unchanged. This is validator admission evidence, not
  execution on a deployed PH stack. SwarmCreationMock1E2ETest was intentionally
  excluded; PH remains stopped.
- `git diff --check` passes. HEAD and the branch remain the stated baseline.
  No commit, push, PR, PH/PP/PD lifecycle action or operational-data change occurred;
  the pre-existing `.playwright-mcp/` remains outside the task diff.

Independent final review completed all six required passes, repository-wide SSOT,
actual composition/call-path and data-contract checks. It independently reproduced
then rechecked the filter/block AST cases, canonical registry/argument checks,
opaque outside-root auth/business documents, dynamic method lookup deferral and
both clearing error paths. No remaining blocking finding in the agreed scope.
Review report: `/tmp/ph533-independent-review.md`. The reviewer ran its own isolated
Java/component probes; it did not run a second concurrent Maven build or PH.

## Verification limits and remaining inherited debt

- Existing native Pebble optional/missing-value behavior is preserved. This change
  does not enable strict variables or claim that every runtime missing value fails.
  Statically identified real references still require declarations; required value
  resolution remains with ScenarioVariablesService and configuration preparation.
- Dynamic Pebble variable methods/indexes and Pebble AST children without public
  accessors (e.g. include with-maps/cache/embed/from internals) are explicitly deferred
  with the existing warning. Dynamic SpEL indexing is not statically resolved and
  currently emits no deferred warning; this remains a diagnostic limitation inherited
  relative to the baseline. Public block/include name expressions are traversed. No
  reflection, custom source preparser, guessed identifiers or compatibility path
  is used to fill the compiler API gaps. Full lexical/flow analysis and externally
  owned template dialects are outside this slice.
- Existing larger mixed validator/controller/config files retain unrelated debt;
  affected preparation/source/selector responsibilities were extracted. This is
  not a redesign of their remaining domains or of the original issue's future SUT
  reference format. The maintainer explicitly retained current YAML/Protocol 2.


## Fresh review corrections — 2026-10-24

A subsequent read-only review found three regressions despite the earlier green
runs: blank eval was rejected, static #root/#this variable reads were lost, and
custom image names bypassed worker-specific source inspection. These findings
superseded the earlier no-blocker verdict and are now corrected locally.

SpEL inspection preserves blank handling and uses the existing parser's root
semantics. Selection/projection element context and root-scoped index expressions
are covered by runtime-versus-inspection and real bundle regressions. Missing,
unknown and declared optional variables retain their existing admission rules.
Worker source and request-contract selection now consistently uses the resolved
capability manifest role; the old basename parser is removed. Canonical/custom
image tests cover configuration sources, DB documents and selected clearing schema.

New regressions failed before each correction. Fresh broad reactor verification
passed 1348 tests in 171 classes. After the last index-context correction, the full
selected templating/Scenario Manager/import-check set passed 453 tests in 42 classes.
Both runs have zero failures, errors or skips and overlap; do not add their counts.
All 86 repository scenarios pass admission and their files remain unchanged.
Numeric callId and trailing JSON regressions remain green. git diff --check passes.

Two independent reviewers rechecked their fixes and the additional SpEL contexts;
no finding remains in this correction scope. Evidence and exact commands:
`/tmp/ph533-review-fixes-20261024.md`, `/tmp/ph533-review-fixes-authoring.log`,
`/tmp/ph533-review-fixes-final.log`. No deployed PH execution or full monorepo build
was performed in this correction turn. PH/PP/PD and operational data remain untouched;
no commit, push or PR was created. Pre-existing .playwright-mcp remains outside changes.


## Symbolic clearing mode correction — 2026-10-25

Fresh review found that an unresolved clearing mode silently skipped both
conditional source branches. ScenarioConfigurationTemplateSources now reports
one WORK_CONFIGURATION_DEFERRED warning at config.mode via the existing symbolic
marker. No default mode, conditional template compilation or schema read is
introduced. Concrete neighboring sources keep their checks; literal modes restore
their existing selected-source checks.

Two new template/structured profile regressions failed before the fix and passed
after it. The full selected Scenario Manager scenario/validation suite passed
347 tests in 31 classes with zero failures, errors or skips, including all 86
unchanged repository scenario bundles and the portable/numeric callId/JSON cases.
The 59-case scope run overlaps that total. git diff --check passes.

Independent source review completed all six passes and reproduced the corrected
warning on the original example, with no findings in this correction scope.
Exact commands, logs and limits: /tmp/ph533-clearing-mode-fix-20261025.md.

The prior overbroad dynamic-lookup wording is corrected: dynamic Pebble lookups
have deferred diagnostics; dynamic SpEL indexing remains an inherited diagnostic
limitation. Runtime evaluation and YAML/Protocol 2 are unchanged. No deployed
PH execution, full monorepo build, operational-data changes, commit, push or PR.
PH/PP/PD remain untouched; pre-existing .playwright-mcp remains outside changes.


## Bootstrap resource root correction — 2026-10-25

The grouped review finding for whole-value templateRoot/schemaRegistryRoot
bindings is fixed in ScenarioBundleResourcePaths. It reuses the exact existing
configuration-preparation matcher before mapping concrete paths and emits one
deferred warning at the declaring field. Request, DB and clearing readers consume
that owner. The old schema-local root check is removed; selected schema id/version
deferral remains separate. No profiles or conditional documents are inspected.
Concrete external-root policy, normalization and containment remain unchanged.

Eight new regressions failed on valid fixtures before implementation; four path
controls already passed. An earlier fixture missing required work was corrected
and is not counted as defect evidence. All 12 focused tests pass after the fix.
The broader selected scenario/import run passes 362 tests in 34 classes with zero
failures, errors or skips, including all 86 unchanged repository scenarios and
portable/numeric callId/trailing JSON regressions. Counts overlap. git diff --check
passes.

Independent read-only review compiled/probed the fix, exercised the original
request/schema examples and all six passes, with no finding in the correction
scope. Exact commands, evidence and limits: /tmp/ph533-root-fix-20261025.md.
No deployed PH execution, full monorepo build, operational-data changes, commit,
push or PR. PH/PP/PD remain untouched and pre-existing .playwright-mcp stays outside
changes. Existing documented expression-analysis limitations remain explicit.


## Path component containment correction — 2026-10-25

A subsequent review reproduced a concrete clearing regression: the shared root
mapper treated an ordinary directory beginning with two dots as parent traversal.
ScenarioBundleResourcePaths now checks the normalized Path component instead of
a string prefix. Contained ..name directories are admitted and inspected; actual
parent escapes are still rejected. No runtime registry, YAML or Protocol 2 change.

Nine new regression cases cover contained/normalized paths, real parent escapes
and selected HTTP/DB/clearing documents. The pre-fix run had six expected failures
in 21 tests. All 21 focused cases pass after the fix. The broader selected
scenario/import run passes 371 tests in 34 classes with zero failures, errors or
skips, including all 86 unchanged repository scenarios. Counts overlap.

Independent review completed all six passes with no finding in this correction
scope, independently executing 12 cases and rechecking the original full-config
admission/clearing-registry reproduction. git diff --check passes. Exact commands,
evidence and limits: /tmp/ph533-dot-fix-20261025.md.

No deployed PH execution, full monorepo build, operational-data changes, commit,
push or PR. PH/PP/PD remain untouched; pre-existing .playwright-mcp stays outside
changes. Previous expression-analysis limitations remain explicit.


## Reserved vars decision — 2026-10-26

After review reproduced locally bound vars bracket reads becoming false profile
dependencies, the maintainer explicitly selected a simpler contract: reserve the
identifier vars and prohibit declaring a local value by that name. This is an
intentional, specifically approved restriction beyond prior behavior preservation;
it replaces lexical shadow tracking or speculative dependency deferral as the fix.

Use the existing Pebble tag parsers and public declaration metadata. A small
transparent parser/stream decorator observes canonical import alias declarations;
expressions and all token advancement remain delegated. Install the same policy
for runtime, syntax validation and source inspection, including supplied/warm
engines. No token scan, copied grammar, reflection or new framework. Ordinary vars
reads and data/map keys remain allowed. Add declaration/reader controls and real
bundle regressions; rerun the unchanged PH scenario corpus and independent review.


## Reserved vars verification — 2026-10-26

Implemented the explicit keyword decision through three small adapters over
Pebble's canonical tag parsers. One reserved-name policy covers runtime rendering,
syntax validation and source inspection, including supplied/warm engines and
nested cache/embed/parallel declarations. Grammar, expressions and token advancement
remain with Pebble; no scanner, copied parser, reflection or new framework.
Ordinary profile reads, other locals, comments, strings and data-map keys pass.

The corrected pre-fix run had 17 expected failures in 84 tests; failure-ignore was
used only to collect both modules, not as passing evidence. All 84 focused tests
pass with normal failure handling after implementation. Broader selected compiler,
scenario and import verification passes 497 tests in 44 classes, zero failures,
errors or skips, including all 86 unchanged repository scenario bundles. Counts
overlap. Independent review completes all six passes with no finding in this scope,
42 direct probes, 84 component tests and separate 86-bundle admission passing.
Evidence and limits: /tmp/ph533-vars-fix-20261026.md.

This resolves the bracket-local finding by the maintainer-approved reservation of
vars, not by preserving local shadowing. YAML/Protocol 2 remain unchanged. PH/PP/PD,
operational data and .playwright-mcp remain untouched; no deployed execution, full
monorepo build, commit, push or PR. Existing source-analysis limits remain explicit.


### 2026-10-26 — selected nested auth profile correction

Confirmed admission regression: runtime selected a nearest ancestor authProfiles.yaml,
while authoring checked only the bundle-root document. Preserve runtime ordering and
YAML/Protocol 2 behavior. Extract the existing search into the pure
TemplateAuthProfileLocation (auth-contracts), with explicit default file and boundary
probe; remove the loader's old search. Extract auth admission into
ScenarioAuthProfileFindings, reuse decoded request documents and root projection, and
associate each root with one selected file. Cache by file path, never profile ID.
Keep standalone root checks, opaque secret references, optional-variable semantics,
and deferred/external-root policy. A malformed nearest file must fail without retry.

Order: pre-fix regressions (12 tests: 7 failures, 0 errors); responsibility update and
independent plan review; implementation; focused tests and unchanged scenario corpus;
independent final review. No stack restart, operational data, commit or publication.

Verification of the nested-auth correction: focused Maven passed 28 tests (12 new
scenario-auth regressions and 16 AuthRuntimeTest cases). The broader selected reactor
passed 580 tests in 50 classes, with zero failures, errors or skips, including the
unchanged 86-bundle ScenarioRepositoryValidationTest corpus, templating/portable
regressions, root/deferred/optional-variable controls, selector cases and the existing
RepositoryImportBoundaryTest. Log: /tmp/ph533-auth-verification.log.

Independent review found no actionable defect in this correction. Separate probes
passed 404 baseline/current selection comparisons (including 14 actual loader/file
cases) and 12 actual admission-owner assertions for overlapping roots, path caches,
missing IDs, malformed nearest files and opaque secrets. Report:
/tmp/ph533-auth-location-review-20261026.md. The original independent admission/runtime
probe now rejects missing nestedToken and succeeds through real variable resolution,
nearest-profile loading and Bearer material creation when a synthetic value is supplied.

These are local admission/runtime-component checks, not deployed scenario execution.
No full-reactor clean build or running PH/PP/PD verification was performed. No scenario
files, .playwright-mcp, operational data, commits, pushes or PRs were changed/created.


## Final verification checkpoint — 2026-10-26

The canonical isolated full rebuild passed: 3,643 Java tests in 534 classes and
266 UI tests in 36 files, with zero failures, errors or skips. The full deployed
acceptance matrix then ran 131 adapter-specific cases: 123 passed, two failed and
six errored. These original results remain unchanged in the evidence.

Diagnosis identified missing explicit scenario reloads in three acceptance fixture
flows and an unstable WSL clock. The fixtures now call the existing official
Scenario Manager reload API after completing their files. Six lines across four
acceptance files change; assertions, timeouts and product behavior are unchanged.
With separate maintainer approval, the competing Ubuntu systemd-timesyncd service
was stopped and disabled; WSL's internal chrony remains active. This host setting
is outside the repository. Independent clock measurements observed no steps in
two approximately 124-second windows, including during the retry.

All eight previously failing cases passed on the targeted retry, with zero
failures, errors or skips. The unchanged delayed-TCP assertion measured 5,037 ms
against its 5,000 ms minimum, and the STOP operation succeeded. The acceptance
reactor also passed 412 unit/component tests in 67 classes. Counts from different
runs overlap; no fresh full 131-case matrix after the fixture/clock correction is
claimed. All 86 repository scenario bundles remain unchanged and admitted.

Independent six-pass source reviews and separate result/clock audits found no
remaining blocker in the reviewed scope. Full-run evidence:
/tmp/ph533-e2e-20261026-gvihIfge/REPORT.md. Retry evidence:
/tmp/ph533-e2e-retry-20261026-38i50n9z/REPORT.md. Clock evidence:
/tmp/ph533-clock-fix-20261026-ILGlWub6/INDEPENDENT-CLOCK-REVIEW.md.

Both isolated acceptance projects and their test resources were removed. The 92
pre-existing containers retain their identities, image IDs and observed state;
PH remains stopped and PP/PD were not restarted. Operational data and the existing
.playwright-mcp directory remain outside this change. The initial full build
replaced 20 local latest image tags; their original unreferenced OCI image indices
were unavailable for exact restoration. Existing containers were preserved. The
retry left those current tags unchanged. These checks do not qualify remote Swarm,
long-term clock stability or absolute UTC accuracy.
