# AI Contribution Guidelines

- Work within the requested scope and respect service boundaries.
- Favour domain logic over framework code; keep domains free of technical noise.
- All changes require tests and documentation when applicable.
- Never expose credentials or personal data.
- Stick to approved technologies: Java 21, React, JUnit 5, Cucumber, and AMQP/HTTP libraries already in use.
- Output must be production ready: typed, formatted and secure.
- No cascading defaults, no implicit back-compat: do not create fallback property chains, and assume breaking changes are acceptable unless backward compatibility is explicitly requested.
- Review the [rules](../rules/) and [specifications](../spec/) before making changes.
- Follow project policy and Conventional Commits.
- When adding or removing Maven modules, update the root `pom.xml` `<modules>` section and commit any new module folders; otherwise builds fail with `Child module ... does not exist`.
- Ensure service Dockerfiles copy new modules' POMs and sources so Docker builds can resolve them.
