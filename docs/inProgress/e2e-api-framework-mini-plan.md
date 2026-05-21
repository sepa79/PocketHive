# E2E API Framework Mini-Plan

> Status: **completed**  
> Scope: lightweight refactor of the PocketHive ingress/API acceptance pack into
> reusable support layers without replacing the existing Cucumber harness

Related:

- `docs/archive/auth-api-rollout-plan.md`
- `docs/ci/control-plane-testing.md`
- `e2e-tests/README.md`

## Goal

Keep the current ingress-based E2E/auth pack, but make it easier to extend with
new APIs by extracting small dedicated support layers for:

1. endpoint/service routing,
2. raw ingress API execution,
3. placeholder/value resolution,
4. reusable auth rollout fixtures,
5. clear rules for where contract/component/ingress checks belong.

This is intentionally **not** a full custom framework rewrite. The target is a
thin, explicit support layer that can grow incrementally.

## Guardrails

- Keep the public entrypoint for tests at ingress/API base URLs only.
- Do not replace service-local controller/contract tests with E2E.
- Do not add fallback routing or hidden test shortcuts.
- Prefer small support types over a large generic abstraction layer.
- Keep Cucumber feature files readable and close to product policies.

## Progress Tracker

### Phase 1: Minimal extraction

- [x] Create a live mini-plan with explicit progress tracking.
- [x] Extract a reusable ingress API runner from `AuthSteps`.
- [x] Extract placeholder/value resolution from `AuthSteps`.
- [x] Extract auth rollout user provisioning fixtures from `AuthSteps`.
- [x] Re-run the auth pack after the refactor.

### Phase 2: Pack structure

- [x] Split auth/API support types into `support/api` and `support/auth` areas.
- [x] Reduce direct `WebClient`/service-switching logic inside step classes.
- [x] Add at least one focused unit test for placeholder or API runner support.
- [x] Document how agents should add a new protected API to the pack.

### Phase 3: Future evolution

- [ ] Decide whether to introduce a descriptor-driven API auth matrix.
- [ ] Decide whether component-level API tests need their own module or naming
      convention.
- [ ] Decide whether Cucumber should remain the thin declaration layer or share
      responsibility with JUnit parameterized API checks.

## Current Design Direction

### Keep in service-local tests

- controller/DTO contract validation,
- serialization details,
- edge-case business validation,
- policy branches that do not need full ingress coverage.

### Keep in the ingress auth pack

- unauthenticated rejection,
- scoped allow/deny behavior,
- one allowed request reaching business logic,
- one denied or stronger-policy request per protected surface.

### Candidate component-test layer

- per-service API tests using real Spring wiring and selected dependencies,
- faster than full-stack E2E,
- broader than a single controller test,
- still not a substitute for ingress auth coverage.

## Completion Rule

This mini-plan is complete when:

- `AuthSteps` no longer owns raw API execution, placeholder substitution, and
  auth rollout fixture provisioning directly,
- the auth pack remains green,
- the `e2e-tests` docs describe how to extend the support layer safely.
