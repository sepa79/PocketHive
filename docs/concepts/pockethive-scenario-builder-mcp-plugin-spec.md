# PocketHive Scenario Builder MCP — VS Code Plugin Draft Spec

## 1. Goal

The VS Code plugin should enable AI-assisted creation and modification of PocketHive scenarios **without allowing the AI to directly edit final YAML files**.

The AI should interact with a **Scenario Builder MCP Server**, which:
- maintains an in-session working copy of a canonical PocketHive scenario bundle,
- performs domain operations that map to the existing scenario contract,
- validates the bundle against the current PocketHive docs/spec,
- generates canonical bundle artifacts,
- returns execution logs and quality feedback.

## 2. Core Principles

- **AI must not write final PocketHive YAML directly**
- **All scenario changes must go through MCP tools**
- **MCP is not a separate scenario SSOT**
- **The canonical scenario contract remains the existing PocketHive docs/spec**
- The plugin should surface:
  - action log,
  - validation log,
  - AI feedback report,
  - output diff / generated artifacts
- The same builder core should later be reusable in:
  - VS Code plugin,
  - local/containerized service,
  - web UI,
  - ZIP import/export workflows

## 3. MVP Scope

MVP should support:
- creating a new scenario
- modifying an existing scenario
- adding / removing bees
- connecting bees
- setting config / env / ports / topology in a basic safe scope
- adding a simple `plan`
- adding HTTP templates
- validating the working bundle copy
- generating output bundle artifacts
- producing a session feedback report

MVP should **not** support initially:
- arbitrary direct editing of every YAML field
- unrestricted repo-wide file writes
- automatic commit / push
- global platform refactors
- unrestricted free-form file system access

## 3.1 Contract Alignment

The builder MCP must be a **safe authoring facade** over the existing PocketHive
scenario contract and bundle layout.

It must not define an alternative scenario model.

Authoritative references remain:
- `docs/scenarios/README.md`
- `docs/scenarios/SCENARIO_CONTRACT.md`
- worker capability manifests under `scenario-manager-service/capabilities/*.latest.yaml`

Implications:
- session state is a working copy of a canonical scenario bundle,
- generated output must match the standard PocketHive bundle layout,
- every MCP tool must have an explicit mapping to existing bundle/YAML fields,
- validation errors must describe violations of the canonical contract, not MCP-only rules.

## 4. Session Model

Each authoring session should contain:
- `session_id`
- `workspace_path`
- `mode`: `create` | `modify`
- `scenario_ref` (optional)
- `working_bundle_path`
- `input_artifacts` (optional)
- `action_log`
- `validation_log`
- `ai_feedback_report`
- `output_bundle_path` (optional)

### Expected session flow

1. Plugin starts MCP session
2. AI performs domain operations via tools
3. MCP records action log
4. AI or plugin requests validation
5. MCP returns validation result
6. AI submits final feedback report
7. MCP generates artifacts / diff
8. Plugin shows result to user

## 5. Minimal MCP Tool Set

### 5.1 Session lifecycle

#### `start_session`
Create a new working session.

Input:

```json
{
  "mode": "create",
  "workspacePath": "/path/to/workspace",
  "baseScenarioRef": null
}
```

Output:

```json
{
  "sessionId": "sess-123",
  "status": "started"
}
```

#### `load_existing_scenario`
Load an existing scenario into the working session.

Input:

```json
{
  "sessionId": "sess-123",
  "scenarioRef": "examples/local-rest"
}
```

#### `close_session`
Close session and finalize outputs derived from the working bundle copy.

#### `discard_session`
Discard working state without exporting bundle artifacts.

---

### 5.2 High-level authoring tools

These tools are preferred where possible, because they reduce the number of low-level calls AI must make.

#### `create_scenario`
Create a canonical scenario bundle scaffold.

Input:

```json
{
  "sessionId": "sess-123",
  "bundleName": "my-scenario",
  "scenarioName": "My Scenario",
  "templateType": "rest-basic"
}
```

#### `create_rest_pipeline`
Create a typical REST-oriented pipeline by updating canonical scenario fields.

Example output structure may include:
- generator
- request-builder (optional)
- processor
- postprocessor
- moderator (optional)

Input:

```json
{
  "sessionId": "sess-123",
  "useRequestBuilder": true,
  "includeModerator": false
}
```

#### `import_postman_collection`
Import a Postman collection into an intermediate representation.

Input:

```json
{
  "sessionId": "sess-123",
  "filePath": "/path/input/postman.json"
}
```

#### `processor.set_config`
Set canonical `template.bees[role=processor].config`.

#### `generator.set_inputs`
Set canonical `template.bees[role=generator].config.inputs`.

#### `plan.set_swarm`
Set canonical `plan.swarm[]`.

Input:

```json
{
  "sessionId": "sess-123",
  "stageType": "steady",
  "startAfter": "10s",
  "duration": "2m",
  "targetRate": 200
}
```

---

### 5.3 Mid-level domain tools

#### `add_bee`
Add a bee to `template.bees[]`.

#### `remove_bee`
Remove a bee from `template.bees[]`.

#### `connect_bees`
Create or update a logical topology edge in `topology.edges[]`.
This tool must only operate on the canonical topology/ports/work contract.
It must not invent a separate graph model.

#### `set_bee_config`
Set or merge a safe subset of canonical bee `config`.

#### `set_env_var`
Set a scenario or bee environment variable.

#### `set_bee_ports`
Add or update logical `ports[]` for a bee as defined by the scenario contract.

#### `set_bee_work_io`
Add or update canonical `template.bees[].work.in/out` mappings.

#### `set_topology`
Set `topology` fields in a constrained way that remains consistent with `ports[]` and `work`.

#### `set_traffic_policy`
Set a basic traffic policy.

#### `set_variables`
Create or update scenario variables.

#### `generator.set_worker_message`
Set canonical `template.bees[role=generator].config.worker.message`.

#### `request_builder.set_config`
Set canonical `template.bees[role=request-builder].config`.

#### `template_http.put`
Create or replace a canonical `templates/http/*.yaml` entry.

#### `attach_template_to_request_builder`
Map template assets to request-builder config.

---

### 5.4 Validation and output tools

#### `validate_session`
Run validation against the working bundle copy and canonical scenario contract.

Validation should return:
- structural issues
- missing references
- invalid bee types
- invalid topology edges
- invalid `work` / `ports` alignment
- missing templates
- incomplete plan definitions
- unsupported config fragments

#### `preview_diff`
Return a diff between the source bundle and the generated canonical bundle output.

#### `generate_bundle`
Generate final canonical bundle artifacts in a temporary or target location.

#### `export_bundle`
Write final bundle to output path or ZIP.

#### `get_action_log`
Return recorded execution log.

#### `get_validation_log`
Return validation result log.

#### `submit_ai_feedback_report`
Store AI-authored summary of work done, assumptions and recommended follow-ups.

## 6. AI Feedback Report Requirement

The plugin should require AI to submit a final structured feedback report before session close.

This report is **not** the same as MCP execution log.

### Purpose

The feedback report helps answer:
- what AI believes it completed,
- what failed or remains uncertain,
- what assumptions were made,
- what user should review next.

### Minimum report contract

```json
{
  "summary": "Created initial REST scenario scaffold from Postman input.",
  "done": [
    "Created scenario bundle",
    "Added generator, request-builder, processor and postprocessor bees",
    "Imported 4 HTTP templates"
  ],
  "warnings": [
    "Traffic profile could not be inferred from input",
    "SUT mapping uses placeholder values"
  ],
  "failed": [
    "Could not generate final plan from collection only"
  ],
  "assumptions": [
    "Assumed REST/HTTP scenario",
    "Assumed request-builder should be used"
  ],
  "suggestedFixes": [
    "Confirm target rate profile",
    "Review auth headers",
    "Bind final SUT endpoint values"
  ],
  "nextActions": [
    "Run validation",
    "Review generated templates",
    "Add warmup and steady plan"
  ]
}
```

## 7. Logging Model

Three separate logs should exist:

### 7.1 Action log
System-generated. Records actual tool calls and results.

Examples:
- session started
- bee added
- topology edge created
- template imported
- bundle generated

### 7.2 Validation log
System-generated. Records validation results.

Examples:
- missing template root
- invalid topology edge target
- missing bee port declaration
- missing required config
- unresolved endpoint binding

### 7.3 AI feedback report
AI-generated. Describes perceived outcome, assumptions and suggested improvements.

These logs must remain separate.

## 8. Plugin UX Requirements

The VS Code plugin should provide:
- session start / stop controls
- visible MCP session status
- AI interaction pane or integration point
- action log view
- validation log view
- AI feedback view
- output diff preview
- approve / discard flow

### Suggested minimal UX flow

1. User starts “Scenario Builder Session”
2. User provides target intent or loads existing scenario
3. AI performs MCP-driven authoring
4. User sees generated state and logs
5. User runs validation or AI triggers validation
6. AI submits feedback report
7. Plugin shows diff
8. User approves export or discards session

## 9. Safety Constraints

The MCP server must not allow unrestricted file access.

Allowed scope:
- current scenario workspace
- controlled artifact folders
- explicitly uploaded input assets

Disallowed scope:
- arbitrary repo writes
- arbitrary shell commands
- unrelated config mutation
- hidden silent auto-fixes outside allowed domain operations

## 10. Recommended Design Direction

### Prefer domain tools over raw structure editing

Bad direction:
- `write_yaml_path`
- `replace_block`
- `write_file_anywhere`

Preferred direction:
- `create_rest_pipeline`
- `add_bee`
- `connect_bees`
- `set_bee_ports`
- `set_bee_work_io`
- `add_plan_stage`
- `attach_template_to_request_builder`
- `validate_session`

Reason:
- fewer AI errors
- stronger invariants
- better auditability
- easier future reuse in UI/API/container

## 11. Future Evolution

Later phases may add:
- containerized Scenario Builder service
- REST wrapper around the same core
- ZIP-based offline AI help pack flow
- lightweight intent parser
- optional small local LLM for intent-to-tool translation
- web UI integration using the same authoring engine

## 12. Implementation Notes

Recommended implementation priorities:

1. Session lifecycle
2. In-session working copy of the canonical scenario bundle
3. High-level MCP tools
4. Validation pipeline
5. Action / validation logs
6. AI feedback report contract
7. Diff preview
8. Export bundle

## 13. Success Criteria for MVP

MVP is successful if the plugin can reliably support:
- creating a basic REST scenario scaffold,
- modifying an existing scenario via MCP only,
- importing Postman input into a controlled intermediate flow,
- generating output artifacts without AI writing YAML directly,
- exposing clear logs and actionable validation feedback.

