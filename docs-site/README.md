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
is omitted for local authoring, the link defaults to
`http://localhost:8088/`. Set all three values explicitly for a published or
frozen build so canonical links, the mount path, and the application link match
the environment where that artifact will be served.

## Build

```bash
cd docs-site
npm run build
```

## Verify the rendered documentation

Run the release gate from the repository root:

```bash
npm run test:docs:rendered --prefix docs-site
```

The command builds a fresh site, discovers every generated documentation route,
and checks each route at desktop and narrow widths. It fails on browser-console
or page errors, Mermaid render failures, missing workflow pagination,
horizontal overflow, and broken internal links or fragments.

The check uses an installed Chrome or Edge browser. Set
`DOCS_TEST_BROWSER_EXECUTABLE` when automatic browser discovery is not suitable.
To verify an already deployed artifact without rebuilding, set
`DOCS_TEST_BASE_URL`, for example:

```bash
DOCS_TEST_BASE_URL=http://127.0.0.1:8094/ npm run test:docs:rendered --prefix docs-site
```

The deployed-artifact form still builds the local source first. The generated
route set is the explicit expectation checked against `DOCS_TEST_BASE_URL`, so
a deployed site cannot pass merely because an omitted route was never visited.

## Reusable documentation command suite

From the repository root, use the unified profiles:

```bash
npm run test:docs:setup   # first run or after a lockfile change
npm run test:docs:static  # published docs and rendering
npm run test:docs         # safe local tool checks as well
npm run test:docs:deployed -- --docs-url https://sepa79.github.io/PocketHive/
```

`test:docs` does not start Docker or perform remote writes. It reports missing
optional prerequisites as `SKIP` and writes no persistent report unless
`--report <path>` is supplied. Runtime and packaging checks are explicit:

```bash
npm run test:docs:runtime -- --base-url http://localhost:8088
npm run test:docs:packaging
npm run test:docs:all -- --base-url http://localhost:8088 --docs-url https://sepa79.github.io/PocketHive/
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
