# PR 511 migration fixture

This fixture migrates the retained PocketHive documentation evidence into the
completed-work schema-v1 shape without upgrading its historical authority.

It binds candidate commit `b0bca602dc802a0be9bd1290952b60a3f3ea1d98`
and tree `fb10512d4657fbb21e8fed58de67a73e7a8c2e10`. The retained validation
was produced against that tree before the final commit identity existed. Its
legacy integrity-manifest digest is preserved, but the fixture does not claim
the unavailable tool, policy, per-report tree, documentation-impact controller,
search/discovery, or independent-review provenance.

Run the read-only assembler from the repository root with explicit paths:

Before assembly, generate the v2 candidate and baseline identities at the exact
ignored paths declared by `request.json`. This historical request explicitly
uses `candidateVerificationTarget: GIT_OBJECT`: the assembler verifies commit
`b0bca602dc802a0be9bd1290952b60a3f3ea1d98`, its tree, merge base, and
reconstruction patch directly from Git objects while the current reviewer
source remains checked out. It does not claim that the historical candidate is
the live worktree; stable candidate Git configuration and attributes are
rejected rather than executed. Follow the architecture's
`capture-identity` commands and do not check generated identity files into the
repository.

Use the retained historical capture timestamp `2026-08-17T12:47:42.630Z` for
both identity commands. `GIT_OBJECT` permits that past timestamp because the
commit/tree objects are immutable; the assembler still requires the separate
`--evaluation-time` argument to be current. This reproduces candidate identity
`a60995f60562f57eca660b0cba328c7f783eb0b9b2f1390aab3e3531f0a6f29c`
and baseline identity
`402cd8a19ad6d5edad15dc214e1170a05531580ff3cf7d3bdbba196bcedf008b`.

```text
node tools/completed-work-review/cli.mjs assemble --repo "<absolute-repository-root>" --request "<absolute-repository-root>/tools/completed-work-review/fixtures/pr-511/request.json" --request-schema "<absolute-repository-root>/tools/completed-work-review/contracts/review-request.schema.json" --producer-registry "<absolute-repository-root>/tools/completed-work-review/fixtures/pr-511/producer-registry.json" --producer-registry-schema "<absolute-repository-root>/tools/completed-work-review/contracts/producer-registry.schema.json" --git-executable "<absolute-git-executable>" --git-executable-sha256 "<trusted-git-executable-sha256>" --repository-id "sepa79/PocketHive" --remote-name "origin" --remote-url "https://github.com/sepa79/PocketHive.git" --evaluation-time "<current-UTC-timestamp>" --producer-registry-digest "<trusted-producer-registry-sha256>" --candidate-identity-id "<trusted-candidate-identity-id>" --baseline-identity-id "<trusted-baseline-identity-id>" --deployment-identity-id "NONE"
```

All fifteen settings are mandatory. The first six are absolute paths. The
evaluation time must be current trusted UTC time; the expected Git-executable
digest, registry digest, and identity IDs must arrive through a trusted
external channel rather than be copied from the candidate-controlled fixture
files. Use that same externally trusted Git digest for both historical identity
captures. `NONE` explicitly records that this migration has no deployment
identity.

The request writes only beneath the ignored
`.test-results/completed-work-review/pr-511-migration-v2` directory. The expected
result retains the legacy submitted comparison (`5.4` to `8.7`, `+3.3`) only in
the quarantined submitted-input view. Because the legacy scores lack trusted
score attestations, canonical dimension and Overall scores are `N/V`, canonical
comparison status is `UNVERIFIED`, and local readiness is `NOT_READY`. It grants
no merge, publication, or deployment authority.

The fixture registry deliberately declares `CANDIDATE_UNVERIFIED`. The
assembler validates the candidate identity object and recomputes its Git
identity separately; a candidate evidence-subject `MATCH` means only that a
receipt names that validated identity. It does not upgrade the registry,
identity producer, receipt producer, or documentation-impact controller to a
trusted authority. Because this registry is in the candidate repository, none
of its receipt authorizations can establish receipt trust. An external
`OPERATOR_SUPPLIED` registry would still have to authorize each exact
`receiptId` / `evidenceId` / `producer.id` tuple.

Independent reviewer IDs and pass kinds are derived from trusted passing
receipt claims. They are not asserted by this migration request, so the absent
historical independent-review receipts remain visible as an unverified gate.

The output directory must not already exist because the assembler will not
overwrite evidence. After assembly, verify the immutable bundle separately:

```text
node tools/completed-work-review/cli.mjs verify --bundle "<absolute-repository-root>/.test-results/completed-work-review/pr-511-migration-v2" --expected-digest "<trusted-expected-bundle-sha256>"
```

Success reports `EXPECTED_DIGEST_MATCH`. The expected digest must come from a
trusted external channel; copying the bundle's own `bundle.sha256` value would
not independently authenticate it.
