Status: in progress

# Main Catch-Up From 0.14

Date: 2026-03-10

## Goal

Capture the remaining 0.14-only changes that still need to land on `main`
before 0.14 is retired. This plan intentionally excludes areas where `main`
already diverged with newer semantics.

## Port Now

1. Template layout + generic template API
   - Port the 0.14 template layout normalization from `eeb8564c`.
   - Move baked request-builder templates to `templates/http` and `templates/tcp`.
   - Replace HTTP-only scenario template endpoints/UI assumptions with generic
     template handling where appropriate.

2. TCP template path flattening + UI follow-up
   - Port the scenario/UI cleanup from `bf4765ea`.
   - Flatten TCP template paths in scenario bundles where the old nested layout
     still leaks into editor or API behavior.

3. Scenario editor height fix only
   - Port only the useful height/layout fix from `7312915e`.
   - Do not bring back older 0.14 topology/recovery semantics that are already
     superseded on `main`.

## Audit Before Porting

Review these 0.14 follow-up fixes and port only the parts still missing on
`main`:

- `d8b9fb34` fix(scenarios): remove Optional raw reads + accept YAML long ints + safer logs
- `6e5a08cf` fix(e2e): consume final queue directly + make variables-demo sink
- `c3520cca` fix(e2e): support variables-demo final queue
- `bb5a7f80` fix(e2e): add bundle-local SUTs for scenarios
- `edb6caa8` feat: bundle-local SUT CRUD + variables coverage warnings
- `36a24321` feat(ui): scenario variables and bundle-local SUT selection

## Explicitly Out Of Scope For Now

- Full ISO8583 work. Keep this separate from the 0.14 catch-up.
- 0.14 orchestrator/topology recovery fixes already covered by newer `main`
  semantics.
- Release bumps, changelog-only commits, CI-only 0.14 maintenance.

## Suggested Order

1. Merge the other pending feature branches into `main` first.
2. Port template layout/API changes.
3. Port the TCP template/UI follow-up.
4. Port the scenario editor height fix.
5. Audit the scenario variables / bundle-local SUT follow-up commits.
6. Handle ISO8583 separately after the above is stable.

## Done When

- `main` no longer depends on legacy `http-templates` / `tcp-templates`
  layout.
- Scenario template handling is no longer HTTP-only where 0.14 already
  generalized it.
- The useful scenario editor height fix is present on `main`.
- The variables/SUT follow-up commits are either ported or explicitly rejected
  after review.
