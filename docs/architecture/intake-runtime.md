# Intake runtime ownership

Status: current implementation; documentation of existing owners, not an ownership
transfer or a release qualification. Module: `.agents/skills/pockethive-intake`.
Names below refer to `scripts/intake_lib/` unless an entrypoint is stated.

The packaged [intake contract](https://github.com/sepa79/PocketHive/blob/main/.agents/skills/pockethive-intake/contract/intake-contract.md)
owns CLI/document behaviour; its schemas own document structure. These records
name implementation boundaries without copying those contracts. Architecture
links in Python headers are contributor references, never files required by an
installed skill. Installed packages use only their sealed local resources.

## RESP-INTAKE-CLI

**Owners:** `scripts/intake.py` locates its installed CLI; `cli.main` decodes
arguments and formats command outcomes; `commands.execute` dispatches and composes
the selected workflow. `errors.IntakeError` carries safe boundary diagnostics;
`diagnostics` describes failures without exposing exception data.
**Consumers/effect:** CLI callers receive the owning workflow's result and exit code.
**Must not:** duplicate validation, infer document state or repair failed commands.
**Verification:** `test_intake_cli.py`, `test_diagnostics.py`.

## RESP-INTAKE-PACKAGE-CONTEXT

**Owner:** `package_context.PackageContext` resolves the installed package and
explicit workspace/evidence paths, bounds reads, verifies sealed files and loads
packaged libraries. Its hash helpers supply canonical byte/content hashes.
**Consumers/effect:** all workflows use this path/integrity boundary; missing,
escaping, linked or corrupt inputs fail explicitly according to the local contract.
**Must not:** discover substitute roots, repair hashes or choose client sources.
**Contract:** packaged `manifest.json`; **verification:** `test_package.py`,
`test_bundle_documents.py`, `test_portability.py`.

## RESP-INTAKE-PACKAGING

**Owners:** `release_files.release_files` enumerates manifest-declared release
roots; `packaging.build` owns explicit manifest resealing and verified archive
publication; `scripts/package.py` exposes that maintainer operation.
**Consumers/effect:** maintenance and intake CI produce a deterministic ZIP with
readback verification. This writer changes package artifacts, not client documents.
**Must not:** reseal implicitly, alter source snapshots or include workspace data.
**Contract:** packaged manifest and intake entrypoints; **verification:** `test_package.py`.

## RESP-INTAKE-YAML

**Owner:** `yaml_codec.YamlCodec` parses, bounds and round-trips the supported YAML
model. Document loading, inspection and authoring consume it.
**Effect:** callers receive supported data or explicit parse/limit errors.
**Must not:** execute tags, evaluate expressions or insert domain defaults.
**Verification:** `test_intake_cli.py`, `test_behavioural_safeguards.py`.

## RESP-INTAKE-DOCUMENT-STORE

**Owner:** `document_store.DocumentStore` owns exact-byte document revisions,
cooperative writer exclusion and individual file replacement/readback.
**Consumers:** command workflows, projections, snapshots and report persistence.
**Effect:** stale revisions and competing writers fail; successful writes are
verified. Publication is not a whole-set transaction; interrupted sets need inspection.
**Must not:** construct projections, infer lock staleness or decide business validity.
**Verification:** `test_document_concurrency.py`, `test_projections.py`.

## RESP-INTAKE-SCHEMA

**Owner:** `schema_validation.SchemaValidation` resolves packaged schema references,
delegates structural validation to packaged fastjsonschema and exposes read-only
field-schema projections. `Validation`, field views and workspace assessment consume it.
**Effect:** schema-invalid documents are reported without inserting defaults.
**Must not:** download schemas or decide semantic readiness.
**Contract:** packaged schemas and `field-help.md`; **verification:** `test_intake_cli.py`,
`test_field_help.py`, `test_runtime_auth.py`.

## RESP-INTAKE-DOCUMENT-VALUES

**Owners:** `pointers` resolves exact JSON Pointers and enumerates scalar facts;
`identity_index` indexes declared identities and reports duplicates;
`input_values` identifies explicit missing/placeholder scalars. These are distinct
shared operations consumed by validation, authoring and projections.
**Effect:** all callers use the same pointer, identity and missing-value semantics.
**Must not:** infer aliases, repair identities or decide applicability.
**Verification:** `test_identity_ownership.py`, `test_field_help.py`, `test_readiness.py`.

## RESP-INTAKE-APPLICABILITY

**Owner:** `applicability` selects participating APIs and the explicitly named SUT
for semantic checks. **Effect:** absent declarations remain absent.
**Must not:** choose another SUT or infer an execution mode.
**Verification:** `test_references.py`, `test_readiness.py`.

## RESP-INTAKE-VALIDATION

**Owner:** `validation.Validation` composes structural, projection, reference,
readiness, data, measurement and evidence checks. Commands and staged authoring
consume the aggregate; specialized owners below decide their own disjoint checks.
**Effect:** one result carries errors, gaps, warnings and review identity.
**Must not:** repair documents, approve work or validate runtime worker configuration.
**Verification:** `test_handoff.py`, `test_qa_existing_gates.py`.

## RESP-INTAKE-REFERENCES

**Owner:** `reference_rules.check_references` checks schema-valid identity links,
selected-SUT references and dependency/correlation relationships through its private
`_References` helper. `Validation` consumes its diagnostics.
**Must not:** perform IO, select missing targets or decide runtime transport support.
**Verification:** `test_references.py`, `test_identity_ownership.py`.

## RESP-INTAKE-READINESS

**Owner:** `readiness_rules.check_readiness` reports applicable missing decisions,
incompatible choices and representation gaps. `Validation` consumes those results.
Recognizing a canonical runtime type does not establish complete representation.
**Must not:** approve execution, assess live readiness or calculate test outcomes.
**Verification:** `test_readiness.py`, `test_runtime_auth.py`, `test_handoff.py`.

## RESP-INTAKE-DATA

**Owner:** `data_rules` checks declared preparation against client usage constraints
and supplies participating-entity/secret-requirement projections to readiness checks.
**Effect:** data/secret-binding gaps stay explicit in `Validation` results.
**Must not:** execute preparation, infer credentials or choose alternative sources.
**Verification:** `test_readiness.py`, `test_handoff.py`.

## RESP-INTAKE-PROVENANCE

**Owners:** `evidence_validation.EvidenceValidation` verifies source identities and
material-fact coverage against `provenance-policy.json`; `evidence_references`
enumerates only declared evidence links for evidence/portability workflows;
`review_digest.review_digest` binds recorded review to material content and ledger.
**Effect:** `Validation` exposes missing/stale evidence; changed content cannot
silently retain a current review digest. Recorded review is not authenticated approval.
**Must not:** interpret source meaning, fetch remote evidence or grant approval.
**Verification:** `test_evidence.py`, `test_qa_existing_gates.py`, `test_portability.py`.

## RESP-INTAKE-PROJECTIONS

**Owner:** `projections.Projections` derives document references, question projections
and hashes, then delegates publication to `DocumentStore`. Validation and field
policy consume its assignments; authoring workflows call prepare/save.
**Effect:** explicit finalisation refreshes derived fields without adopting client facts.
**Must not:** create another question ledger or grant approvals.
**Verification:** `test_projections.py`, `test_document_concurrency.py`.

## RESP-INTAKE-INSPECTION

**Owners:** `bundle_inspector.BundleInspector` inventories source files and extracts
allowlisted observations; `inspection_coverage` projects that traversal's coverage;
`source_comparison` compares explicit snapshots and their evidence targets.
**Consumers:** inspection, population, evidence checks and review projections.
**Effect:** source hashes exclude the owner-resolved intake subtree; unknown content
and extraction gaps remain visible. Runtime vocabulary is consumed from its existing
[projection owner](runtime-responsibilities.md#resp-intake-runtime-vocabulary).
**Must not:** execute source content, resolve endpoints, infer adapters or semantic impact.
**Contract:** `bundle-observations.json`; **verification:** `test_runtime_observations.py`,
`test_inspection_coverage.py`, `test_bundle_documents.py`.

## RESP-INTAKE-EDIT-POLICY

**Owner:** `authoring_policy.AuthoringPolicy` describes editable/protected fields
from schemas, projections and the intake contract for field views and updates.
**Effect:** those consumers share the same edit boundary.
**Must not:** validate client facts or reconstruct projection assignments.
**Verification:** `test_field_help.py`, `test_sourced_updates.py`.

## RESP-INTAKE-UPDATES

**Owner:** `sourced_updates.apply_batch` validates explicit revision-bound edit
batches, stages their evidence and delegates checks/publication to existing owners;
`apply_updates` decodes the selected input file. Commands and enrichment consume it.
**Effect:** dry runs do not persist; accepted batches retain supplied provenance.
**Must not:** infer source meaning, adopt proposals or duplicate schema validation.
**Verification:** `test_sourced_updates.py`, `test_update_preview.py`.

## RESP-INTAKE-FIELD-VIEWS

**Owners:** `field_view` builds one requested field view; `field_context` projects
its recorded ledger context; `field_views` decodes bulk requests and composes those
same views. CLI callers consume read-only schema, edit-policy and diagnostic guidance.
**Must not:** write values, infer evidence support or independently validate schemas.
**Verification:** `test_field_help.py`, `test_bulk_fields.py`.

## RESP-INTAKE-HUMAN-INPUT

**Owner:** `human_input.review_input` inventories explicitly supplied raw forms.
CLI and enrichment planning consume evidence references rather than inferred answers.
**Must not:** treat raw forms as validated working documents or migrate facts implicitly.
**Verification:** `test_human_input.py`, `test_enrichment.py`.

## RESP-INTAKE-WORKSPACE

**Owner:** `workspace_assessment.assess_workspace` derives start/resume guidance
from explicit directory contents, delegating parsing and schema checks.
**Effect:** the CLI distinguishes incomplete/raw/working document sets.
**Must not:** infer client intent, repair a set or implement another schema validator.
**Verification:** `test_workspace_help.py`.

## RESP-INTAKE-INITIALISATION

**Owner:** `initialisation.initialise` creates a partial set from mandatory packaged
templates, using package paths, inspection, projections and document persistence.
**Consumers:** CLI initialisation and staged enrichment.
**Must not:** overwrite existing work, infer client facts or adopt inspected configuration.
**Verification:** `test_intake_cli.py`, `test_bundle_documents.py`, `test_enrichment.py`.

## RESP-INTAKE-POPULATION

**Owners:** `population_fields.TemplatePopulation` maps explicit template observations
into empty fields; `population.populate_from_inspection` verifies recorded inspection
identity and orchestrates that mapping and projection publication.
**Consumers/effect:** explicit population and its review preview preserve client facts
and attach observation provenance. The preview does not create a second mapper.
**Must not:** parse bundle files again, replace the source or infer adoption.
**Verification:** `test_population.py`, `test_display_population.py`.

## RESP-INTAKE-ENRICHMENT

**Owners:** `enrichment_plan.prepare_enrichment` maps explicit source pointers into
canonical authoring batches; `enrichment.enrich_input` stages, validates and publishes
the new document set using initialisation, updates, snapshots and persistence owners.
**Effect:** source facts remain attributed; existing working revisions are not overwritten.
**Must not:** guess mappings, adopt examples or implement another validator.
**Verification:** `test_enrichment.py`.

## RESP-INTAKE-EVIDENCE-SNAPSHOTS

**Owner:** `evidence_snapshots` computes content-addressed snapshot references and
retains bounded immutable evidence through `DocumentStore`.
**Consumers:** enrichment and portability. **Effect:** retained bytes are verified;
collisions fail instead of replacing evidence.
**Must not:** infer provenance or rewrite document references.
**Verification:** `test_enrichment.py`, `test_portability.py`.

## RESP-INTAKE-PORTABILITY

**Owner:** `portability.make_portable` explicitly plans and rewrites declared local
evidence references, delegates snapshot retention and validates resulting documents.
**Effect:** CLI delivery can survive relocation for supported references; arbitrary
URLs or runtime links in prose are not claimed portable.
**Must not:** search for missing sources, rewrite payloads or refresh approval implicitly.
**Verification:** `test_portability.py`.

## RESP-INTAKE-REVIEW-VIEWS

**Owners:** `review_brief` composes saved evidence and canonical validation;
`review_summary` reduces that result; `decision_review` groups recorded decisions;
`blank_review` explains nulls using the canonical population preview;
`review_comparison` compares exact fields and identity-indexed ledger entries;
`authoring_advisories` reports notices from explicit facts.
**Consumers/effect:** CLI review and reports expose read-only guidance with gaps
and unresolved decisions preserved. These projections do not own readiness.
**Must not:** invent questions, infer source fidelity or turn diagnostic totals into decisions.
**Verification:** `test_review_views.py`, `test_review_summary.py`, `test_review_counts.py`,
`test_ledger_comparison.py`, `test_authoring_advisories.py`.

## RESP-INTAKE-REPORT

**Owners:** `report_format.render_report` renders canonical facts, review and
validation; `stakeholder_report.write_report` enforces revision/edit protection and
delegates verified persistence to `DocumentStore`.
**Effect:** an explicitly written stakeholder report remains a derived artifact.
**Must not:** author facts, execute sources or infer acceptance/readiness.
**Verification:** `test_stakeholder_report.py`.

## Existing shared records

Runtime vocabulary remains owned by
[RESP-INTAKE-RUNTIME-VOCABULARY](runtime-responsibilities.md#resp-intake-runtime-vocabulary).
Recorded measurement/run checks in `measurement_rules` remain owned by
[RESP-INTAKE-RESULT-EVIDENCE](runtime-responsibilities.md#resp-intake-result-evidence).
This index does not duplicate those definitions. `__init__.py` has no runtime behaviour.
All verification entrypoints above are under the skill's `tests/`; execution and
qualification limits remain in its [qualification guidance](https://github.com/sepa79/PocketHive/blob/main/.agents/skills/pockethive-intake/tests/QUALIFICATION.md).
