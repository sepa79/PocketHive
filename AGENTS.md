# PocketHive AI Rules

AI contributors must load the following resources before generating code or documentation:

- [docs/ai/AI_GUIDELINES.md](docs/ai/AI_GUIDELINES.md)
- [docs/rules/](docs/rules/)
- [policy/project-policy.yaml](policy/project-policy.yaml)

Key guidelines include:

- Work within the requested scope and respect service boundaries.
- Favour domain logic over framework code; keep domains free of technical noise.
- Provide tests and documentation when applicable.
- Reject null, empty, or blank parameters by failing fast (e.g., throw `IllegalArgumentException`) instead of defaulting or returning quietly.
- Ensure new or modified handlers include unit tests that cover these guard clauses.
- Never expose credentials or personal data.
- Stick to approved technologies (Java 21, React, JUnit 5, Cucumber, ArchUnit, existing AMQP/HTTP libs).
- Output must be production ready: typed, formatted and secure.
- Follow project policy and Conventional Commits.
- Keep the root `pom.xml` `<modules>` list in sync with actual module folders to prevent `Child module ... does not exist` build errors.
- Update service Dockerfiles to copy any new modules' `pom.xml` and sources so container builds succeed.
