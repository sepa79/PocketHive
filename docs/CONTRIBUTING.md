# Contributing

## Branching
Use feature branches from `main`. Keep commits focused and rebase when needed.

## Commit style
Follow [Conventional Commits](https://www.conventionalcommits.org/) and keep messages in English.

## Code style
Respect `.editorconfig` and keep the domain layer free from frameworks. UI services must bind to port **80** by default.

## Tests
Provide JUnit 5 and, when appropriate, Cucumber tests. ArchUnit rules must stay green.

## Pull requests
- Include tests and relevant docs.
- Run the CI workflow before requesting review.
- Reviews check ArchUnit rules and security practices.

## Security
Never commit secrets or sensitive data. Report vulnerabilities privately.
