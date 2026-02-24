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
Recommended entrypoint: `http://localhost:3000/` (Start Here).

To mirror the UI-mounted path (`/docs/*`), run:

```bash
DOCS_URL=http://localhost:8088 DOCS_BASE_URL=/docs/ npm start
```

## Build

```bash
cd docs-site
npm run build
```

Notes:
- The site reads docs from `../docs` (it does not duplicate content).
- `docs/archive/**` and `docs/inProgress/**` are excluded from the site build.
- GitHub Pages publishing is handled by `.github/workflows/docs-pages.yml`.
