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

1. Merge all pending docs-only branches first.
2. Merge `origin/feat/network-proxy-plan`.
3. Merge `origin/feature/processor-iso8583-v1-v2-plan`.
4. Port template layout/API changes that still need explicit follow-up on top of
   `main`.
5. Port the TCP template/UI follow-up.
6. Port the scenario editor height fix.
7. Audit the scenario variables / bundle-local SUT follow-up commits.
8. Handle ISO8583 follow-up separately after the above is stable.

## Merge Notes

- Recommended docs-first order:
  - `origin/docs/inprogress-archive-cleanup`
  - `origin/docs/sut-dataset-simulation-model`
  - `origin/postman-setup-teardown`
- Recommended feature order:
  - `origin/feat/network-proxy-plan`
  - `origin/feature/processor-iso8583-v1-v2-plan`
- `network-proxy-plan` should land before `processor-iso8583-v1-v2-plan`
  because it is more infrastructure-oriented and has much smaller overlap with
  the template/API churn from the ISO branch.
- Known overlap between `network-proxy-plan` and `processor-iso8583-v1-v2-plan`
  is limited to a few files in processor tests/runtime and two TCP SSL scenario
  files, so either order should be workable, but proxy-first is the cleaner
  sequence.

## Merge Workflow

- Create one temporary integration branch from current `main`.
- Merge the docs branches there first.
- Merge `origin/feat/network-proxy-plan` next.
- Merge `origin/feature/processor-iso8583-v1-v2-plan` after that.
- Apply any explicit follow-up ports/fixes on the same integration branch.
- Run validation there and merge that branch back into `main` only after the
  full stack is known-good.

## Done When

- `main` no longer depends on legacy `http-templates` / `tcp-templates`
  layout.
- Scenario template handling is no longer HTTP-only where 0.14 already
  generalized it.
- The useful scenario editor height fix is present on `main`.
- The variables/SUT follow-up commits are either ported or explicitly rejected
  after review.
