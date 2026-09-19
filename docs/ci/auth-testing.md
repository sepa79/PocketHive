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

The CI test and scoped Java mutation jobs each start their own Redis service and
supply these values. Both jobs check the explicit OpenSSL executable before
running the relevant gates. The OpenSSL verifier fails explicitly if its executable
is missing or invalid; it
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
acceptance.

## Scoped Java mutation gates

CI runs the configured Java MCP, product `auth-service`, and HTTP Sequence PIT
gates with Java 21 and repository-pinned Maven 3.9.6. After installing the reactor
dependencies, the commands are:

```bash
./mvnw -B -ntp -DskipTests install
./mvnw -B -ntp -f pockethive-mcp-service/pom.xml org.pitest:pitest-maven:mutationCoverage
./mvnw -B -ntp -f auth-service/pom.xml org.pitest:pitest-maven:mutationCoverage
./mvnw -B -ntp -f http-sequence-service/pom.xml -Pmutation test-compile org.pitest:pitest-maven:mutationCoverage
```

The HTTP Sequence profile retains its configured target classes and thresholds
in `http-sequence-service/pom.xml`; its discovered tests use the Redis settings
above and JDK keytool/JCA for TLS/signature checks. The independent OpenSSL oracle
runs in worker-sdk tests during the root Java gate; the mutation job separately
checks that OpenSSL is available. These are scoped mutation gates, not mutation
coverage of the worker OAuth provider or every class in the modules.

## Environment verification

Tests of a deployed PocketHive stack must use its supported public ingress/API or
canonical MCP interface. Test-owned issuer/resource/Redis fixtures are component
integration dependencies, not substitute entrypoints into a running stack.
Provider sandbox exchange and deployed-bundle execution remain separate checks.
