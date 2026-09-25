# Intake skill checks and artifacts

The [Intake skill workflow](../../.github/workflows/intake-skill.yml) verifies
the committed contract snapshot and builds the portable skill automatically.
It runs for relevant pull requests and pushes to `main`, or by manual dispatch.
Its path filters include the skill, generator, common Java modules, lifecycle
schema, Maven build inputs and workflow itself. Common modules are covered
together so new reactor dependencies do not require a second dependency list in
CI. Changes outside these paths do not trigger this workflow.

## Checks and ownership

1. The public `verify-package` command checks packaged files against their
   committed integrity manifest.
2. `tools/intake-contracts/generate.sh --check` compiles the canonical value
   owners and rejects a missing or stale generated schema without rewriting it.
3. The complete public CLI suite runs with isolated Python. It includes offline
   ZIP relocation, source isolation and deterministic archive checks.
4. The existing `scripts/package.py` builds and verifies the ZIP. CI never passes
   `--refresh-manifest`, so it cannot silently bless unreviewed file changes.
5. After all checks pass, CI uploads the ZIP and its SHA-256 checksum as
   `pockethive-intake-<commit SHA>`, retained for 14 days. The package manifest
   records the skill version; the artifact name identifies the tested revision.

The workflow only orchestrates existing owners. Schema export remains owned by
`tools/intake-contracts/ExportRuntimeVocabulary.java`; integrity and ZIP contents
remain owned by the skill's `PackageContext`, `release_files` and packager.
Java 21 and Maven are build prerequisites. Installed skills still require only
their declared Python runtime and bundled files.

## Isolation from product builds

The projection drift check belongs exclusively to this workflow. Default Maven
tests do not read the packaged intake schema. Product builds can test the Java
contracts even when the skill directory is absent or its snapshot is stale.
The image-publishing workflow has no dependency on intake qualification.

The intake workflow still rejects missing or stale snapshots and withholds its
artifact on failure. Keep it separate from required product-release checks;
branch-protection settings are managed independently from this workflow.

## Updating the committed snapshot

When a canonical value owner changes, regenerate and review the projection:

```sh
tools/intake-contracts/generate.sh
```

After reviewing changes to any packaged skill file, refresh the manifest through
the existing maintainer command, using a new external output directory:

```sh
intake_release_dir="$(mktemp -d)"
python3 -B -I -S .agents/skills/pockethive-intake/scripts/package.py \
  --refresh-manifest --output "$intake_release_dir/pockethive-intake.zip"
```

Commit the reviewed source changes, changed generated schemas and updated
manifest. ZIPs and checksum sidecars are distribution artifacts, not repository
sources; conventional intake ZIP filenames are ignored by Git. No version bump
or snapshot refresh is needed for unrelated repository changes.

## Downloading a build

Open a successful **Intake skill** workflow run and download its artifact. Extract
the artifact wrapper to obtain `pockethive-intake.zip` and its checksum, then verify:

```sh
sha256sum -c pockethive-intake.zip.sha256
```

Extract the skill ZIP and run its documented installation verification. Manual
dispatch builds the selected revision through the same gates. CI artifacts expire;
long-term release distribution can retain the verified ZIP outside Git.

The workflow uses a read-only repository token and does not publish a GitHub
Release, commit generated files or push. Path-filtered checks are unsuitable as
an unconditional required branch-protection check because unrelated PRs skip
them. See [GitHub's workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)
and [artifact handling](https://github.com/actions/upload-artifact/tree/v4).
