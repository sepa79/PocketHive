# PocketHive intake context

Reference revision: **2**. Reviewed: **2026-09-22**.
PocketHive source baseline: **6757b2f6c488bc196aa551abc86203a6ccdff10c**,
including MCP baseline **aedd336b588005d800a4045afbd5b19496a08f59**.

This is a read-only guide derived from canonical PocketHive owners. It is not a
runtime schema, capability registry, validator or deployment observation. Source
identifiers below provide provenance; no PocketHive source checkout is needed to
use this skill. Consult the selected source's version and available live owner
contracts before making a capability claim.

## Terms and owners

| Term | Meaning and boundary |
| --- | --- |
| SUT | System under test: the selected target environment/profile. Its identity does not prove access, readiness or production equivalence. |
| Scenario Bundle | Source files describing a worker topology and its related configuration/assets. It is not the client requirements document. |
| Swarm | A running topology of workers. Similar bundles or shared endpoints do not identify the same runtime swarm. |
| Intake document set | Filled requirements, one selected-SUT test plan, traceability and results. Git/workspace files own intake authoring. |
| ScenarioWorkflow | Existing MCP coordination for QA, generation and publication. Intake does not create or mutate it. |

Scenario Manager owns bundle contracts, semantic validation and the supported
worker capability catalogue. Orchestrator and the existing runtime services own
runtime lifecycle and observations. The MCP exposes their contracts and enforces
tool scopes using Auth Service identity and grants. Destructive cleanup requires
explicit human approval of the current plan. Model text, skills and local
validation cannot grant operational permissions or approve actions.

Keep one authority for each fact. Use explicit adapters, settings, source modes
and failures. Do not implement a compatibility search chain, choose another
protocol after failure or duplicate the runtime validator. Topologies are
scenario-defined; a sample pipeline is not a universal PocketHive pipeline.

## Source capabilities and proposals

| Source work | Status at the pinned baseline |
| --- | --- |
| Java PocketHive MCP, `merge/rewrite-lifecycle-mcp` at `aedd336b` | Included source-defined contract; still requires actual client/environment qualification. |
| MCP improvements at `377cbcfa` | Included. |
| HTTP Sequence SUT endpoint work, branch head `0374a769` | Endpoint support included; the branch-exclusive change is a release bump. Check the selected worker's exact capabilities. |
| Global SUT/mocks specification at `6757b2f6` | Proposed lifecycle, not implemented runtime capability. Current SUT read tools expose bundle-local definitions. |
| Body-predicate polling at `f26aa25b` | Separate, unmerged work. Do not advertise `whileJson` or `failJson` on this baseline. |
| Managed datasets proposal at `0c5e5c48` | Separate proposal. Desired behaviour may be captured as a gap; do not claim a running managed-data service. |

An older source may contain bundle-local SUT generation instructions. Record
that source's meaning without implementing a future global-to-bundle fallback.
The intake documents do not change the canonical `scenario.yaml` descriptor or
its schema.

## Optional MCP source inspection

Use the authorised MCP access path for the selected environment. The canonical
PocketHive server is Java 21 Streamable HTTP through its public ingress: a
declared local development endpoint may be `http://localhost:8088/mcp`; a
declared remote endpoint uses its environment's `/mcp` ingress. These examples
do not select an environment. Do not probe alternate ports, protocols or paths.
The removed Node/stdio server and dotted tool aliases are not compatibility paths.

After connection, use the available authorised discovery interface to read:

- `pockethive://knowledge/overview`
- `pockethive://capabilities/current`
- `pockethive://tools/catalogue`
- `pockethive://skills/catalogue`

These are resource identifiers, not tool names. If the client cannot access a
required resource, report that limitation rather than inventing a tool alias.
Use the installed governance facade where required; direct development access
must be explicitly permitted by that environment's rules.

Source-defined read tools useful for intake include:

- `scenario_contracts_get`, `scenario_capabilities_get`
- `scenario_list`, `scenario_get`, `scenario_raw_read`
- `scenario_bundle_tree_read`, `scenario_bundle_file_read`
- `scenario_suts_list`, `scenario_sut_get`

Take input shapes and permissions from the actual catalogue, not this list.
Unavailable binary content is an evidence gap. No local inspection or MCP read
proves that a deployment matches Git or that a test passed.

## Later scenario-authoring handoff

This section is reference for a separately requested handoff. Intake itself only
prepares source documents; it does not start or update a ScenarioWorkflow.

The existing QA owner stores twelve coarse topic answers, not the typed fields
of this intake package. Topics cover goal/risk; SUT/endpoints; journeys/schemas/
expectations; SLA/stopping; load; data; authentication; setup/dependencies;
background traffic/isolation; observability/triage; reporting/retention; and
safety/governance. Reuse the completed documents; do not repeat the interview.

At handoff, choose an available capture mode explicitly: `AGENT_MEDIATED`,
`MCP_FORM` or `COMPACT_REVIEW`. Do not change modes after failure without an
explicit choice. For compact review:

1. Use the finalised traceability instance as the named `ConfirmedSource`. It
   binds the other documents by hash; retain its exact bytes and digest.
2. Prepare the complete candidate through `scenario_workflow_review_prepare`.
   Present the returned review unchanged and wait for explicit user acceptance.
3. Call `scenario_workflow_review_submit` only with the same accepted candidate
   and owner evidence. Edits require a new prepare/review. Intake answers alone
   cannot manufacture valid workflow confirmation.

The current compact contract requires every topic and accepts no unknowns.
The domain's `DERIVED` enum is not exposed by current answer submission; do not
invent that tool input. A partial intake cannot be submitted with guessed
answers or false not-applicable values.

Keep YAML as the editable source. MCP answers are confirmed snapshots for that
workflow. Changes belong in YAML first, followed by explicit re-confirmation/
import and invalidation of dependent evidence under the existing owner contract.

`scenario_workflow_generate` records caller-supplied files and their metadata;
it does not render these templates or prove bundle semantics. Later validation
uses the owner's applicable `scenario_bundle_direct_validation_prepare` or
`scenario_bundle_validation_prepare` flow and
`scenario_bundle_validation_receipt_get`. Obtain exact prerequisites and schemas
from the live catalogue. Do not replace it with the intake validator or the
obsolete `paths.check`, `bundle.validate` and `bundle.validate.result` names.

Generation, publication, deployment, load, SQL and cleanup remain separate
operations under their owners and existing authorisation rules.

## Source provenance

The pinned guide was checked against PocketHive's README, `docs/ARCHITECTURE.md`,
`docs/mcp/README.md`, the Java MCP `ToolCatalogue`, `ScenarioWorkflowToolExecutor`
and QA domain/input schemas, plus the named branch proposals. These identifiers
are evidence citations, not instructions to load files outside this package.
