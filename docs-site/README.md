# PocketHive Docs Site (Docusaurus)

This folder contains a Docusaurus site that renders the repository documentation
from `../docs` as a browsable, searchable web site.

By default, the `ui` image bundles a static build from this folder under `/docs/*`.
This folder remains the source for local docs authoring (`npm start`) and for GitHub Pages publishing.

## Prerequisites

- Node.js 18+

## Install

From the repo root:

```bash
cd docs-site
npm install
```

## Run locally

```bash
cd docs-site
npm start
```

The site runs at `http://localhost:3000/`.
Recommended entrypoint: `http://localhost:3000/` (Overview).

To mirror the UI-mounted path (`/docs/*`), run:

```bash
DOCS_URL=http://localhost:8088 \
DOCS_BASE_URL=/docs/ \
POCKETHIVE_APP_URL=http://localhost:8088/ \
npm start
```

`POCKETHIVE_APP_URL` controls the navbar link back to the application. When it
is omitted, the link is intentionally absent; PocketHive does not guess a
deployment URL. Set all three values explicitly for a published or frozen build
so canonical links, the mount path, and the application link match the target
deployment.

## Build

```bash
cd docs-site
npm run build
```

## Verify the rendered documentation

Run the release gate through the documentation-validation controller from the
repository root. Every controller invocation requires an explicit adapter
manifest; it selects each declared top-level executable from that manifest and
never switches to another browser or command adapter. This guarantee applies to the controller's declared
top-level adapters. Child processes still inherit an environment in which npm
lifecycle scripts and platform packaging may resolve transitive tools from
`PATH`; lockfiles and declared runtime identities bind that risk, but v2 does
not claim a closed transitive-tool environment.

The manifest must follow
[`adapter-manifest.schema.json`](../tools/docs-validation/contracts/adapter-manifest.schema.json).
Each adapter is either `CONFIGURED` with its canonical absolute path and exact
file identity, or `NOT_APPLICABLE` with the required `null` fields. The `node`,
`git`, and `commandShell` controller adapters must be configured; Windows also
requires `taskkill`. The
[`adapter-manifest.example.windows-static.json`](../tools/docs-validation/contracts/adapter-manifest.example.windows-static.json)
file illustrates a Windows static-profile declaration. Its paths, hashes, and
sizes are machine-specific example values and must be measured again for the
machine that will run the check.

Use the generator instead of hand-writing hashes and sizes. Every adapter option
is mandatory exactly once: pass one absolute executable/directory selection or
the literal `NOT_APPLICABLE`. The generator resolves the selected path to its
canonical target, captures its exact SHA-256 and size, validates the complete
manifest, and refuses to overwrite an existing output. This is the Linux static
profile form used by Pages CI:

```bash
node_executable="$(node --print 'process.execPath')"
"$node_executable" tools/docs-validation/generate-adapter-manifest.mjs \
  --output "$PWD/.test-results/docs-validation/adapter-manifest.json" \
  --platform linux \
  --node "$node_executable" \
  --git /usr/bin/git \
  --npm "$(dirname "$node_executable")/npm" \
  --command-shell /usr/bin/bash \
  --taskkill NOT_APPLICABLE \
  --bash /usr/bin/bash \
  --power-shell /usr/bin/pwsh \
  --java NOT_APPLICABLE \
  --maven NOT_APPLICABLE \
  --local-repository NOT_APPLICABLE \
  --docker NOT_APPLICABLE \
  --chromium /usr/bin/google-chrome
```

The paths above are explicit selections for GitHub's Ubuntu runner, not a
portable search list. Supply the exact paths for a different host. After
generation, resolve the manifest to an absolute path and pass it on the command
line. For a POSIX shell:

```bash
adapter_manifest="$(realpath .test-results/docs-validation/adapter-manifest.json)"
npm run test:docs:static -- --adapter-manifest "$adapter_manifest"
```

For PowerShell:

```powershell
$adapterManifest = (Resolve-Path '.test-results\docs-validation\adapter-manifest.json').Path
npm run test:docs:static -- --adapter-manifest $adapterManifest
```

The static profile builds a fresh site, discovers every generated documentation
route, and checks each route at desktop and narrow widths. It fails on
browser-console or page errors, Mermaid render failures, missing workflow
pagination, horizontal overflow, and broken internal links or fragments.

`npm run test:docs:rendered --prefix docs-site` is an internal stage invoked by
the controller after it validates the declared Node and Chromium adapters. It is
not a supported standalone entrypoint.

To verify an already deployed artifact, pass its URL and the same explicit
manifest to the `deployed` profile. For a POSIX shell:

```bash
npm run test:docs:deployed -- \
  --adapter-manifest "$adapter_manifest" \
  --docs-url https://sepa79.github.io/PocketHive/
```

The deployed-artifact form still builds the local source first. The generated
route set is the explicit expectation checked against the supplied `--docs-url`,
so a deployed site cannot pass merely because an omitted route was never
visited.

## Reusable documentation command suite

From the repository root, use the unified profiles:

```bash
npm run test:docs:setup -- --adapter-manifest "$adapter_manifest"  # first run or after a lockfile change
npm run test:docs:static -- --adapter-manifest "$adapter_manifest" # published docs and rendering
npm run test:docs -- --adapter-manifest "$adapter_manifest"        # safe local tool checks as well
npm run test:docs:deployed -- \
  --adapter-manifest "$adapter_manifest" \
  --docs-url https://sepa79.github.io/PocketHive/
```

The npm scripts forward arguments after `--`; omitting `--adapter-manifest` or
passing a relative path fails before validation begins. `test:docs` does not
start Docker or perform remote writes. An adapter declared `NOT_APPLICABLE`
causes a dependent optional stage to report `SKIP`; the controller does not
search for a replacement. Every profile writes its evidence receipt to the
fixed `.test-results/docs-validation/*.json` path declared by the corresponding
npm script. Runtime and packaging checks are explicit:

```bash
npm run test:docs:runtime -- \
  --adapter-manifest "$adapter_manifest" \
  --base-url http://localhost:8088
npm run test:docs:packaging -- --adapter-manifest "$adapter_manifest"
npm run test:docs:all -- \
  --adapter-manifest "$adapter_manifest" \
  --base-url http://localhost:8088 \
  --docs-url https://sepa79.github.io/PocketHive/
```

The `deployed` profile rebuilds the expected route inventory and audits every
route and internal link on the supplied documentation URL. The runtime profile
uses only the official PocketHive ingress and is read-only.
Swarm lifecycle mutations remain a manual acceptance step because they require
target confirmation and explicit approval.

Notes:
- The site reads docs from `../docs` (it does not duplicate content).
- `docs/archive/**` and `docs/inProgress/**` are excluded from the site build.
- GitHub Pages publishing is handled by `.github/workflows/docs-pages.yml`.
