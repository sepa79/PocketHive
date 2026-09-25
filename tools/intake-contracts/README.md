# Intake runtime vocabulary projection

Generate the portable intake skill's runtime vocabulary from compiled
`AuthType` and `RequestTemplateProtocol` contracts. Those Java types own the
values; the generated JSON Schema is a read-only projection governed by
[RESP-INTAKE-RUNTIME-VOCABULARY](../../docs/architecture/runtime-responsibilities.md#resp-intake-runtime-vocabulary).

From the repository root, with Java 21 and the Maven wrapper prerequisites:

```sh
tools/intake-contracts/generate.sh
tools/intake-contracts/generate.sh --check
```

Both commands compile the existing `common/request-templates` reactor and its
dependencies, then invoke the Java source exporter with explicit compiled-class
paths. Generation writes and reads back
`.agents/skills/pockethive-intake/contract/schemas/runtime-vocabulary.schema.json`.
Check mode compares exact bytes and fails for missing or stale output without
changing it. Neither command parses Java source, downloads runtime schemas,
adds an alternate value list, or refreshes the package manifest.

The projection carries every canonical auth key and request-template protocol.
Unknown/null draft values and intake readiness remain the intake contract's
responsibility. This snapshot does not certify a deployed worker's capabilities.

The `--check` command above is the compiled-owner drift gate. It runs in the
dedicated intake workflow; normal Maven tests have no dependency on packaged
skill files. Missing or stale projections fail intake qualification explicitly.
No optional-file skip or automatic repair is used.

After reviewing all skill changes, its maintainer must explicitly refresh the
manifest and build a new external ZIP with the existing
[`scripts/package.py --refresh-manifest --output FILE`](../../.agents/skills/pockethive-intake/contract/intake-contract.md#entry-points)
workflow, then verify the package and run the public CLI qualification suite.
The immutable `assets/source/` snapshots and their `SHA256SUMS` remain unchanged.
Installed intake packages use only the sealed local schema; they never invoke
this generator or require Java, Maven, or a PocketHive checkout.

The [Intake skill CI workflow](../../docs/ci/intake-skill.md) checks the committed
projection and package integrity, runs the CLI suite, and uploads a verified ZIP
plus checksum. CI never regenerates or reseals source files. Commit reviewed
sources, generated schemas and manifest changes; keep ZIPs as build artifacts.
