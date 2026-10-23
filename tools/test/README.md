# Disposable test infrastructure

This directory contains maintained test support, not generated test results.
It provides isolated Redis and RabbitMQ fixtures for tests that need those
services. CI and the root `build-hive.sh` use this wrapper.

## Run

From the repository root:

```sh
tools/test/with-infrastructure.sh ./mvnw -B -ntp test
```

The wrapper accepts any command and its arguments. It requires Bash, Docker
with Docker Compose, and OpenSSL on `PATH` (or an absolute executable path in
`AUTH_OPENSSL_TEST_EXECUTABLE`), plus the dependencies of the supplied command.
The Maven example requires Java 21.

## What it starts and cleans up

[`with-infrastructure.sh`](with-infrastructure.sh) creates a unique Compose
project, starts the services defined in
[`infrastructure.compose.yml`](infrastructure.compose.yml), and passes their
connection settings to the supplied command. Host ports are allocated dynamically
on the loopback interface.

It removes its containers, volumes, and temporary project directory when the
command finishes or receives a handled interruption. Failed runs also print the
fixture logs. Missing container images may be downloaded. The wrapper does not
start the PocketHive application stack or use that stack's data.

## Related sources

- [CI workflow](../../.github/workflows/ci.yml)
- [Local build entrypoint](../../build-hive.sh)
- [Authentication testing](../../docs/ci/auth-testing.md)
- [Control-plane testing](../../docs/ci/control-plane-testing.md)
