# Scenario Custom Editor (MVP Plan)

Goal: give junior users a form-first editor for `scenario.yaml` inside VS Code and avoid manual YAML edits.

## Audience
- Junior operators who need to change common scenario parameters safely.
- Power users can still open raw YAML when needed.

## MVP scope
- Scenario info: `id`, `name`, `description`.
- Bees list (repeatable rows): `role`, `image`, `work.in`, `work.out`.
- Plan timeline (simple blocks): `hold`, `ramp`, `pause` with `atSec`, `durationSec`, `rate`/`ratio` fields.
- Read-only raw YAML tab (for visibility only).

## UX flow (simple)
1) Open `scenario.yaml` -> custom editor opens.
2) Top section: Scenario info fields.
3) Bees section:
   - list with Add / Remove.
   - each row is a compact form.
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
- Editing advanced templating blocks, interceptors, or exotic config sections.
- Full visual topology editor.

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
