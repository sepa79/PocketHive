# Scenario validation simplification (#533)

## Scope and decisions

Preserve Scenario Protocol 2, existing YAML, `{{ ... }}` expressions and optional
variable behavior. The maintainers retained the current variable format and
reserved `vars` for Scenario Variables. Templates may read `vars`, but may not
declare a local binding with that name. Ordinary map keys named `vars` remain
valid. No scenario migration is required.

Validation works on parsed, owned sections. Scenario Manager does not interpret
the opaque Orchestrator plan. Comments, unrelated files and ordinary business
data do not create executable dependencies. Validate actual template sources and
selectors. Defer dynamic selectors to runtime without skipping concrete checks
that can already be made.

## Owners and behavior

- `ScenarioConfigurationPreparation` owns bootstrap substitution and SUT base URL
  preparation. It preserves nested paths, full-value versus embedded-string
  substitution and the existing variable-map precedence. Required value
  resolution remains with `ScenarioVariablesService`.
- `ScenarioTemplateAnalysis` coordinates authoring checks. Source projections
  come from the owners of the worker fields and the resolved capability manifest
  role. DB parameter references use `DbParameterSource`; SQL is not Pebble.
- Runtime rendering, syntax validation and source inspection share the canonical
  Pebble declaration policy. SpEL analysis uses the existing parser and respects
  root, selection, projection and index-expression context. Blank `eval` retains
  its existing behavior. No extra expression grammar is maintained.
- HTTP Sequence selectors are literal after bootstrap. Request Builder uses
  actual Work selectors, including Generator Work headers where applicable.
  Generator SIMPLE and HTTP headers have different destinations. Symbolic
  whole-value headers and set objects are deferred while concrete neighbors
  retain their checks.
- Runtime and authoring share the request-template document decoder. JSON rejects
  duplicate keys and trailing content. YAML retains its existing document policy.
  `RequestTemplateParser` owns document semantics. Numeric portable `callId`
  uses runtime binding before `HttpSequencePortablePolicy` applies the step rules.
- `ScenarioBundleResourcePaths` handles concrete root containment and symbolic
  deferral for request, DB and clearing sources. A directory name beginning with
  two dots is allowed when the normalized path stays within the bundle. A symbolic
  clearing mode is deferred explicitly. Concrete modes use their selected sources.
- Runtime and authoring share `AuthProfileTemplateValues` and
  `TemplateAuthProfileLocation`. Select the nearest applicable profile file for
  each template root, cache by path, and report an invalid selected file without
  trying another. Authoring treats env and file secret references as opaque.
- Pure source projections live in the existing templating and Redis configuration
  modules. `MessageBodyType` keeps its Java name and enum values in `templating-api`.

See [runtime responsibilities](../architecture/runtime-responsibilities.md) for
canonical ownership and [upgrade guidance](../UPGRADING.md#01539-from-01538) for
changes visible to scenario authors.

## Verification scope

Use the existing repository scenario corpus and focused authoring and runtime
tests for optional variables, static and dynamic selectors, numeric portable
calls, trailing JSON, resource roots and nested auth profiles. For deployed
acceptance, use the official Scenario Manager reload API after writing fixtures
and follow the [control-plane testing strategy](../ci/control-plane-testing.md).

## Known limits

Dynamic Pebble method and index lookups and compiler nodes without public child
accessors can be deferred. Dynamic SpEL indexing is not resolved statically and
has no corresponding deferred warning. Lexical and flow analysis and external
template dialects are outside this scope. Native Pebble missing-value behavior
remains unchanged. The work does not redesign the remaining validator or
controller responsibilities or introduce a new SUT reference format.
