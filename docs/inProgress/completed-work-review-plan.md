# PocketHive completed-work review plan

| Field | Current state |
| --- | --- |
| Status | In progress; local contracts, receipts, assembler, projections, and PR 511 migration implemented; evaluator execution provenance and protected trust gates remain |
| Architecture | [Completed-work review architecture](../architecture/completed-work-review.md) |
| Integration base | `b0bca602`; compatibility reference, not automation authority |
| Current scope | Local files and tests only |
| Initial verdict scope | `LOCAL_CANDIDATE` |
| Authority | No commit, push, merge, publication, deployment, workflow, or ruleset action |

The delivery sequence preserves the Compare Completed Work lifecycle: capture
an exact baseline before mutation, implement a bounded slice, verify it, compare
equivalent evidence under one profile, apply blockers, and report confidence
and the publication boundary.

## Completed foundation

- [x] Define canonical schema-v1 values and limits.
- [x] Define closed committed and dirty-worktree identity modes.
- [x] Define closed evidence-receipt and review-result contracts.
- [x] Separate comparison status, readiness verdict, and authority boundary.
- [x] Define structured gates, blockers, regressions, gaps, unlocks, freshness,
  confidence, and independent review.
- [x] Add explicit `POCKETHIVE_DOCUMENTATION_V1` and
  `POCKETHIVE_DOCS_AUTOMATION_V1` profiles.
- [x] Bind `ANCHORED_RUBRIC_V1`, one criterion, and ordered `0`/`5`/`10`
  anchors into every profile dimension, canonical result, scorecard, and
  report projection.
- [x] Add deterministic profile loading, canonical digesting, projection drift
  checks, semantic checks, and an independent validator with tested `oneOf`
  support.
- [x] Retain `TIMEOUT` as a first-class evidence outcome.

Focused command:

```text
node --test tools/completed-work-review/profile.test.mjs
```

Current profile and contract result after candidate-identity-v2 and
producer-trust hardening: 6 passed, 0 failed, 0 skipped.

## P0 — Evidence seam

- [ ] Add a trusted candidate-identity producer for both explicit modes.
- [x] Extend documentation validation to emit schema-v1 receipts without
  replacing its own result authority.
- [x] Bind a stable post-load tool-source snapshot, source-plus-runtime tool,
  profile, platform, browser/runtime, and emitted artifact digests in local
  validation receipts and review results without claiming the snapshot proves
  which bytes executed.
- [x] Report evaluator execution provenance explicitly as `NOT_VERIFIED` via
  `POST_LOAD_FILESYSTEM_SNAPSHOT`, keep executed-source and controller
  attestations `null`, and emit the material
  `evaluator-execution-provenance-unverified` blocker.
- [x] Add explicit committed and dirty-worktree identity-capture CLI flows that
  write canonical v2 identities only to ignored `.test-results` paths.
- [ ] Bind screenshot digests for an evidence producer that claims a visual
  reviewer pass.
- [x] Represent `PASS`, `FAIL`, `SKIP`, `ERROR`, and `TIMEOUT` without coercion.
- [ ] Prove official-ingress use for each applicable PocketHive runtime check.
- [x] Add deterministic evidence-manifest construction and digest validation.
- [x] Require an explicit producer registry and expose its authority and digest
  in the result.
- [x] Require exact receipt ID, evidence ID, and producer ID authorization for
  every receipt considered under an external `OPERATOR_SUPPLIED` registry.
- [x] Require externally pinned registry digest and candidate/baseline/deployment
  identity IDs, plus a current trusted evaluation time, on every assembly.
- [x] Require an externally supplied expected Git-executable SHA-256 and verify
  its stable direct-file capture before the first Git command in identity
  capture or assembly.
- [x] Require an externally pinned expected digest when verifying a bundle and
  report `EXPECTED_DIGEST_MATCH` only on exact agreement.
- [ ] Supply a protected producer registry or protected controller; current
  local authority remains `CANDIDATE_UNVERIFIED` or `OPERATOR_SUPPLIED`.
- [ ] Add a separately approved protected launcher that authenticates and
  digests evaluator inputs before module load, launches the pinned evaluator,
  and emits an externally verifiable execution attestation. Do not reinterpret
  or auto-upgrade the local post-load snapshot method.

Acceptance:

- An identity or digest mismatch fails closed.
- A dirty candidate binds every tracked change and every non-ignored untracked
  regular file.
- Stable candidate configuration, attributes, and metadata cannot cause the
  assembler to execute candidate code; active same-account mutation remains an
  explicit local-v1 limitation pending a protected launcher.
- The same inputs produce byte-identical canonical receipts and manifest.
- A post-load snapshot match never claims executed-source provenance or clears
  its material blocker.

## P1 — Review assembler

- [x] Load exactly one declared profile; reject missing, unknown, or multiple
  profiles.
- [x] Validate candidate identity, evidence receipts, and documentation-impact
  authority.
- [x] Calculate canonical dimension deltas and weighted Overall only from
  trusted score attestations; quarantine request-submitted values separately.
- [x] Reject scoring-method, criterion, or score-anchor drift from the selected
  digest-bound profile.
- [x] Derive required gates and propagate open material blockers.
- [x] Quarantine request confidence and derive canonical confidence from fresh,
  trusted gate evidence, score verification, and gate completeness.
- [x] Derive independent reviewer IDs and pass kinds only from trusted passing
  receipt claims; the request cannot assert them.
- [x] Emit canonical `review.json`, scorecard, readiness, and bundle digest.
- [x] Bundle the exact candidate reconstruction patch, untracked bytes, and
  reviewer source files for offline blind inspection.

Acceptance:

- Required evidence missing from any current dimension makes readiness
  `NOT_READY`.
- A required failed or unverified gate blocks readiness.
- An open material blocker overrides a high score.
- Current-only evidence never invents a baseline score or delta.
- Unverified submitted scores leave canonical dimension and Overall values
  `N/V` with comparison status `UNVERIFIED`.
- Informational documentation-impact output never becomes protected evidence.

## P1 — Deterministic report projections

- [x] Render the mandated Markdown-first report order.
- [x] Render an accessible HTML dashboard from the same result.
- [x] Show exact identity, profile, evidence-manifest, freshness, trust-control,
  and authority state.
- [x] Render the honest evaluator-execution status, capture method, two explicit
  `null` attestations, and canonical limitation statement in both projections.
- [x] Render baseline/candidate tables, evidence notes, readiness, Overall,
  regressions, gaps, verdict, confidence, and publication boundary.
- [x] Render submitted inputs in a visually distinct audit-only quarantine while
  keeping canonical unverified scores at `N/V`.
- [x] Render every receipt's producer, execution, exact check/configuration
  tuple, timestamp, claims, observations, and artifacts in both projections.
- [x] Keep approve/defer controls out of the dashboard and direct reviewers to
  native pull-request controls without implying repository authority.

Acceptance:

- Markdown and HTML contain identical decisions and arithmetic.
- Renderer tests prove they do not recalculate verdicts.
- Canonical comparison markers render as valid UTF-8 and use text/symbols as
  well as colour.

Current renderer result:

```text
node --test tools/completed-work-review/render.test.mjs
```

9 passed, 0 failed, 0 skipped. This includes score/confidence-quarantine enforcement,
nested-fact parity, adversarial
escaping, no-script/no-external-asset checks, section-order checks, collapsed
blocker details, exact receipt-set binding, evidence drill-down parity,
unknown-enum rejection, and required-fact rejection.

## P1 — PR 511 migration fixture

- [x] Import the retained PR 511 documentation evidence as legacy receipts.
- [x] Bind candidate commit `b0bca602` and tree `fb10512d`.
- [x] Preserve the documentation rubric and legacy submitted score inputs with
  the v1 profile without promoting them to canonical verified scores.
- [x] Record unavailable historical baseline/merge-base, policy/tool identity,
  and per-report tree bindings as explicit gaps.
- [x] Re-run the local report in the hardened assembler without claiming
  present merge, publication, or deployment readiness.
- [x] Add the intended candidate-unverified producer-registry fixture after the
  post-gate profile digest stabilizes, then regenerate the retained demo bundle.

Acceptance:

- The migrated 16/16 validation result remains measured evidence.
- Legacy omissions remain visible and force the appropriate unverified gates.
- No historical fact or authority is fabricated.

## P2 — Reusable core/policy seam and HiveForge pilot

- [ ] Extract a repository-neutral executable core with no PocketHive,
  HiveForge, docs-impact, Docusaurus, or application-path import.
- [ ] Introduce one explicitly supplied, digest-pinned policy-pack contract for
  profiles, gates, adapters, scoring criteria, ingress, and reviewer policy.
- [ ] Move documentation-impact and search/discovery assessments into the
  PocketHive policy pack instead of the generic result shape.
- [ ] Add a non-PocketHive conformance fixture proving that onboarding needs no
  core edit and loads no candidate executable code.
- [ ] Pilot HiveForge with its explicit `npm ci` then `npm run check` sequence,
  exact Node/npm/lockfile identity, environment allow-list, working directory,
  timeouts, and `LOCAL_CANDIDATE`-only authority.

Acceptance:

- PocketHive behavior is reproduced by a PocketHive pack with zero fallback.
- HiveForge adds policy and adapter files only; any core/schema edit stops the
  pilot and reopens the extraction boundary.
- Per-dimension scoring criteria and per-dimension verification state are
  digest-bound before sibling adoption.

## P3 — PocketHive expansion

- [ ] Propose and separately approve UI, MCP, control-plane, local-delivery, and
  HiveForge-delivery profiles.
- [ ] Keep each profile's dimensions, gates, adapters, identity, and authority
  explicit.
- [ ] Use only official ingress/API paths for supported runtime tests.
- [ ] Add protected-controller integration only after the documentation-impact
  trust and ownership gates close.

## P4 — Edenred portability rollout

- [ ] Add HiveWatch only with separate explicit Maven and UI TypeScript/Vite
  receipts; its image-building/Compose convenience script is outside the first
  pilot.
- [ ] Add HiveMind only with explicit `npm run test:hivemind` and
  `npm run pack:mcp` bindings and its required work-unit/session workflow.
- [ ] Require every later Edenred app to add only a policy pack, adapter
  contracts, and conformance tests; any core edit is a rollout no-go.

## Completion gates

The local feature is complete only when:

1. all schemas and profile projections pass;
2. identity, evidence, arithmetic, blocker, stale-evidence, and authority
   negative tests pass;
3. an independent read-only review has been reconciled;
4. equivalent baseline/candidate evidence has been scored with one profile;
5. no open material blocker remains for the declared local scope;
6. the final report states confidence and the exact publication boundary.

Workflow, protected-controller, CODEOWNERS, ruleset, merge, publication, and
deployment integration remain separately approved work.
