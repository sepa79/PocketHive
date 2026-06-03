# E2E Target Profiles

`start-e2e-tests.sh --target <name>` loads `deploy/e2e-targets/<name>.env`
before applying wrapper defaults. Variables already exported in the shell win
over values from the profile.

Profiles are intentionally plain `KEY=value` files. Keep them free of secrets
unless the target is strictly local and disposable.

The PocketHive API endpoints should use the deployment ingress where available.
Some current E2E helpers still need direct public test-service ports for
RabbitMQ, Redis, ClickHouse, and TCP mock setup.
