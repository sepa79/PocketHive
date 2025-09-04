# PocketHive AI Rules

AI contributors must load the following resources before generating code or documentation:

- [docs/ai/AI_GUIDELINES.md](docs/ai/AI_GUIDELINES.md)
- [docs/rules/](docs/rules/)
- [policy/project-policy.yaml](policy/project-policy.yaml)

Key guidelines include:

- Work within the requested scope and respect service boundaries.
- Favour domain logic over framework code; keep domains free of technical noise.
- Provide tests and documentation when applicable.
- Never expose credentials or personal data.
- Stick to approved technologies (Java 21, React, JUnit 5, Cucumber, ArchUnit, existing AMQP/HTTP libs).
- Output must be production ready: typed, formatted and secure.
- Follow project policy and Conventional Commits.
