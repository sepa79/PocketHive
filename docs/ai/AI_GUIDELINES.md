# AI Contribution Guidelines

- Work within the requested scope and respect service boundaries.
- Favour domain logic over framework code; keep domains free of technical noise.
- All changes require tests and documentation when applicable.
- Never expose credentials or personal data.
- Stick to approved technologies: Java 21, React, JUnit 5, Cucumber, ArchUnit and AMQP/HTTP libraries already in use.
- Output must be production ready: typed, formatted and secure.
- Review the [rules](../rules/) and [specifications](../spec/) before making changes.
- Follow project policy and Conventional Commits.
- When adding or removing Maven modules, update the root `pom.xml` `<modules>` section and commit any new module folders; otherwise builds fail with `Child module ... does not exist`.
