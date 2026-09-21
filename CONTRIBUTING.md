# Contributing

## Branching
Use feature branches from `main`. Keep commits focused and rebase when needed.

## Commit style
Follow [Conventional Commits](https://www.conventionalcommits.org/) and keep messages in English.

## Code style
Respect `.editorconfig`, the mandatory [engineering rules](docs/ENGINEERING_RULES.md), and keep the domain layer free from frameworks. Use one Java production type or one TypeScript/React module concern per file by default, with one clear responsibility per file. UI services must bind to port **80** by default.

## Local setup
- The React UI resides in `ui/` (Vite + TypeScript). Run `cd ui && npm install` once, then `npm run dev` for development or `npm run build` for production.
- After changing `ui/nginx.conf`, rebuild with `docker compose up -d --build ui`.
- Node tooling lives at the repo root: `npm run lint` and `npm test`.
- Services use `RABBITMQ_HOST=rabbitmq` inside the Compose network.

## Tests
Provide JUnit 5 and, when appropriate, Cucumber tests. Keep existing static analysis checks green.

## Pull requests
- Include tests and relevant docs.
- Run the CI workflow before requesting review.
- Apply [review rules](docs/REVIEW_RULES.md); reviews check ownership, module separation, automated analysis, and security practices.

## Security
Never commit secrets or sensitive data. Report vulnerabilities privately.
