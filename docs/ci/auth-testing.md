# Authentication regression tests

Worker authentication tests use the normal JUnit/Maven test layout. The canonical
Java MCP is documented in [MCP overview](../mcp/README.md). Tests use disposable
credentials and test-owned endpoints; they do not establish acceptance by an
external OAuth provider.

## Java prerequisites

Use a Java 21 JDK, including its `keytool` executable for disposable TLS fixtures,
and the repository Maven wrapper (`mvnw.cmd` in Windows PowerShell), which pins
the Maven version used by CI.
The Redis-backed OAuth integration tests require an isolated Redis
fixture and explicit connection settings:

| Environment variable | Required value |
| --- | --- |
| `AUTH_REDIS_TEST_HOST` | Host of the test-owned Redis fixture. |
| `AUTH_REDIS_TEST_PORT` | TCP port of that fixture. |
| `AUTH_OPENSSL_TEST_EXECUTABLE` | Absolute path to the OpenSSL executable used by the independent signed-request verifier. |

The CI test job starts its own Redis service and supplies these values. The
OpenSSL verifier fails explicitly if its executable is missing or invalid; it
does not switch verification implementations. Redis-gated tests are skipped when
their connection settings are absent, so a passing run with those skips does not
establish Redis integration coverage.

With these prerequisites configured, run the same Java command as CI from the
repository root:

```bash
./mvnw -B -ntp test
```

The relevant coverage includes signed-request construction and independent
verification, profile isolation, token parsing and expiry, cache/lease behavior,
and consumer integration. `HttpSequenceOAuthCompatibilityTest` compares client
credentials, password grant, and HTTP Signature through real template loading,
a validating test HTTPS issuer, Redis, and downstream HTTP traffic. Separate
regressions check duplicate authorization headers, diagnostic disclosure, and
resource ownership; functional success does not supersede those checks.

Do not use failure-ignore options or omit the negative regressions when reporting
the full test result. Record failures, errors, and skips separately.

## MCP prerequisites and execution

The canonical Java MCP uses the repository's Java 21 Maven test and mutation
gates. See [MCP overview](../mcp/README.md) for its supported authoring flow and
Streamable HTTP interface. The removed Node MCP, stdio, and wizard tests are not
supported execution paths.

The focused authoring regressions preserve complete signed and ordinary OAuth
files through proposal generation and the HTTP upload workflow. The HTTP test
stubs the owner validation result; separate Scenario Manager API tests exercise
the real authored storage checks. Neither resolves credentials or proves provider
acceptance. The scoped mutation jobs cover Java MCP and product `auth-service`,
not mutation coverage of the worker OAuth provider.

## Environment verification

Tests of a deployed PocketHive stack must use its supported public ingress/API or
canonical MCP interface. Test-owned issuer/resource/Redis fixtures are component
integration dependencies, not substitute entrypoints into a running stack.
Provider sandbox exchange and deployed-bundle execution remain separate checks.
