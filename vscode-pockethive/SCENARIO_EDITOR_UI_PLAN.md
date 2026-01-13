# Scenario Custom Editor (MVP Plan)

Goal: give junior users a form-first editor for `scenario.yaml` inside VS Code and avoid manual YAML edits.

## Audience
- Junior operators who need to change common scenario parameters safely.
- Power users can still open raw YAML when needed.

## MVP scope
- Scenario info: `id`, `name`, `description`.
- Bees list (repeatable rows): `role`, `image`, `work.in`, `work.out`.
- Per-bee config editing: `config`, `inputs`, `outputs`, `interceptors`.
- Template editor launcher for any field containing `{{ ... }}` (optional button "Template editor").
- Template editor is a universal component, reusable for other template-backed editors (HTTP/TCP, etc).
- Plan timeline (v1): `plan.bees[].steps` and `plan.swarm[]` with `stepId`, `time` (ISO-8601 duration), `type` (`config-update`/`start`/`stop`), and `config` payload.
- Read-only raw YAML tab (for visibility only).

## UX flow (simple)
1) Open `scenario.yaml` -> custom editor opens.
2) Top section: Scenario info fields.
3) Bees section:
   - list with Add / Remove.
   - each row is a compact form with tabs: Info, Config, Inputs, Outputs, Interceptors.
   - If a field contains `{{ ... }}`, show a "Template editor" button next to it.
4) Plan section:
   - list of blocks with Add / Remove.
   - inline validation (required fields, ranges).
5) Save writes back to `scenario.yaml`.

## Data handling rules
- Preserve unknown YAML fields (do not drop or reformat unrelated sections).
- Only update fields controlled by the form.
- On parse errors, show raw YAML view with a clear error summary.

## Validation
- Client-side checks for required fields and basic ranges.
- Optional server-side validation (Scenario Manager) on save if API is available.

## Non-goals (for MVP)
- Full parity with Hive UI.
- Editing advanced templating blocks or exotic config sections not covered above.
- Full visual topology editor.

## Template editor (universal component)
- Trigger: any text field that includes `{{ ... }}` (or user clicks "Template editor" explicitly).
- Behavior: open a dedicated editor panel for templates, with both raw text and guided edits.
- Reuse: the same component should be used across scenario fields, HTTP/TCP templates, and future template-backed areas.
- Inputs: template string + template capabilities metadata (see below).
- Outputs: updated template string; never mutates other fields.

## Template capabilities (SSOT)
Goal: one shared contract for template functions and editor hints, used by Web UI + VS Code.
- Source of truth: a new `docs/...` contract file + shared DTO/schema (avoid multiple definitions).
- API surface: `GET /api/template-capabilities` (or similar) returns the manifest list.
- Manifest shape (draft):
  - `engine`: `spel` | `pebble` | `mustache` (or explicit engine id).
  - `function`: name, description, return type, and argument list.
  - `args[]`: name, type, required, default, allowed values, validation.
  - `ui`: editor hints (e.g. `kind: weighted-list`, `label`, `help`, `group`, `examples`).
  - `scopes`: where this function is valid (headers/body/inputs/outputs/etc).
- Example: `pickWeighted` defined as a template function with a weighted-list editor.

## Implementation approach (technical)
- Use VS Code Custom Editor with a webview.
- Form state is derived from parsed YAML.
- Write-back merges only the edited fields into the original document.
- Keep YAML formatting stable as much as possible.

## Success criteria
- Junior users can create or edit a basic scenario without touching YAML.
- No data loss in unknown sections after save.
- Clear validation errors for missing/invalid inputs.

## Open questions
- Which bee fields are required in your most common scenarios?
- Should plan editing be included in MVP or phase 2?
- Which template engines are in scope first (SpEL vs Pebble)?
- Where should template capabilities live in the docs tree?
