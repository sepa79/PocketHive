# Contributing

## Branching
Use feature branches from `main`. Keep commits focused and rebase when needed.

## Commit style
Follow [Conventional Commits](https://www.conventionalcommits.org/) and keep messages in English.

## Code style
Respect `.editorconfig` and keep the domain layer free from frameworks. UI services must bind to port **80** by default.

## Local setup
- The React UI resides in `ui/` (Vite + TypeScript). Run `cd ui && npm install` once, then `npm run dev` for development or `npm run build` for production.
- After changing `ui/nginx.conf`, rebuild with `docker compose up -d --build ui`.
- Node tooling lives at the repo root: `npm run lint` and `npm test`.
- Services use `RABBITMQ_HOST=rabbitmq` inside the Compose network.

## Tests
Provide JUnit 5 and, when appropriate, Cucumber tests. ArchUnit rules must stay green.

## Pull requests
- Include tests and relevant docs.
- Run the CI workflow before requesting review.
- Reviews check ArchUnit rules and security practices.

## Security
Never commit secrets or sensitive data. Report vulnerabilities privately.
