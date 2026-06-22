package io.pockethive.worker.sdk.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.worker.sdk.api.HistoryPolicy;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.config.RedisSequenceProperties;
import io.pockethive.worker.sdk.templating.PebbleTemplateRenderer;
import io.pockethive.worker.sdk.templating.TemplateRenderer;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class AuthRuntimeTest {

    @Test
    void appliesStaticBearerHeaderFromAuthProfile() throws Exception {
        Path scenario = Files.createTempDirectory("auth-profile");
        Path templates = Files.createDirectories(scenario.resolve("templates"));
        Files.writeString(scenario.resolve("authProfiles.yaml"), """
            profiles:
              "api:static":
                type: STATIC_TOKEN
                storage:
                  mode: NONE
                token: test-token
            """);

        AuthRuntime runtime = AuthRuntime.forTemplates(
            templates.toString(),
            List.of(new AuthRef("api:static", AuthApplyAs.HTTP_AUTHORIZATION_BEARER, null, null, null)),
            Map.of(),
            new TestContext(),
            (template, context) -> template,
            new RedisSequenceProperties());

        AuthRuntime.MutableHttpRequest request = new AuthRuntime.MutableHttpRequest("GET", "/accounts", Map.of(), "");
        runtime.applyHttp(
            new AuthRef("api:static", AuthApplyAs.HTTP_AUTHORIZATION_BEARER, null, null, null),
            request,
            null,
            new TestContext());

        assertThat(request.headers()).containsEntry("Authorization", "Bearer test-token");
    }

    @Test
    void resolvesSutContextInAuthProfileTemplates() throws Exception {
        Path templates = profiles("""
            profiles:
              "api:sut":
                type: STATIC_TOKEN
                storage:
                  mode: NONE
                token: "{{ sut.endpoints['default'].baseUrl }}/token"
            """);
        AuthRuntime runtime = AuthRuntime.forTemplates(
            templates.toString(),
            List.of(new AuthRef("api:sut", AuthApplyAs.HTTP_AUTHORIZATION_BEARER, null, null, null)),
            Map.of(),
            Map.of(
                "id", "wiremock-local",
                "endpoints", Map.of(
                    "default", Map.of("baseUrl", "http://wiremock:8080")
                )
            ),
            new TestContext(),
            new PebbleTemplateRenderer(),
            new RedisSequenceProperties());

        AuthRuntime.MutableHttpRequest request = new AuthRuntime.MutableHttpRequest("GET", "/accounts", Map.of(), "");
        runtime.applyHttp(
            new AuthRef("api:sut", AuthApplyAs.HTTP_AUTHORIZATION_BEARER, null, null, null),
            request,
            null,
            new TestContext());

        assertThat(request.headers()).containsEntry("Authorization", "Bearer http://wiremock:8080/token");
    }

    @Test
    void rejectsAuthProfilesYmlFallback() throws Exception {
        Path scenario = Files.createTempDirectory("auth-profile-yml");
        Path templates = Files.createDirectories(scenario.resolve("templates"));
        Files.writeString(scenario.resolve("authProfiles.yml"), """
            profiles:
              "api:static":
                type: STATIC_TOKEN
                storage:
                  mode: NONE
                token: test-token
            """);

        assertThatThrownBy(() -> AuthRuntime.forTemplates(
            templates.toString(),
            List.of(new AuthRef("api:static", AuthApplyAs.HTTP_AUTHORIZATION_BEARER, null, null, null)),
            Map.of(),
            new TestContext(),
            (template, context) -> template,
            new RedisSequenceProperties()))
            .isInstanceOf(AuthFailureException.class)
            .hasMessageContaining("authProfiles.yaml was not found");
    }

    @Test
    void rejectsSutReferencesWhenNoSutContextIsProvided() throws Exception {
        Path templates = profiles("""
            profiles:
              "api:sut":
                type: STATIC_TOKEN
                storage:
                  mode: NONE
                token: "{{ sut.endpoints['default'].baseUrl }}/token"
            """);

        assertThatThrownBy(() -> AuthRuntime.forTemplates(
            templates.toString(),
            List.of(new AuthRef("api:sut", AuthApplyAs.HTTP_AUTHORIZATION_BEARER, null, null, null)),
            Map.of(),
            Map.of(),
            new TestContext(),
            new PebbleTemplateRenderer(),
            new RedisSequenceProperties()))
            .isInstanceOf(AuthFailureException.class)
            .hasMessageContaining("references sut but no SUT context was provided");
    }

    @Test
    void rejectsDuplicateProfileIdsStructurally() throws Exception {
        Path scenario = Files.createTempDirectory("auth-profile-duplicate");
        Path templates = Files.createDirectories(scenario.resolve("templates"));
        Files.writeString(scenario.resolve("authProfiles.yaml"), """
            profiles:
              "api:static":
                type: STATIC_TOKEN
                storage:
                  mode: NONE
                token: one
              "api:static":
                type: STATIC_TOKEN
                storage:
                  mode: NONE
                token: two
            """);

        assertThatThrownBy(() -> AuthRuntime.forTemplates(
            templates.toString(),
            List.of(new AuthRef("api:static", AuthApplyAs.HTTP_AUTHORIZATION_BEARER, null, null, null)),
            Map.of(),
            new TestContext(),
            (template, context) -> template,
            new RedisSequenceProperties()))
            .isInstanceOf(AuthFailureException.class)
            .hasMessageContaining("Failed to read");
    }

    @Test
    void appliesHttpStrategiesWithoutLeakingStorageDetailsToTemplates() throws Exception {
        Path templates = profiles("""
            profiles:
              bearer:
                type: BEARER_TOKEN
                storage:
                  mode: NONE
                token: bearer-token
              basic:
                type: BASIC_AUTH
                storage:
                  mode: NONE
                username: alice
                password: wonder
              api:
                type: API_KEY
                storage:
                  mode: NONE
                key: api-key-1
              query:
                type: STATIC_TOKEN
                storage:
                  mode: NONE
                token: query-token
                queryParam: access_token
              hmac:
                type: HMAC_SIGNATURE
                storage:
                  mode: NONE
                secretKey: signing-secret
              aws:
                type: AWS_SIGNATURE_V4
                storage:
                  mode: NONE
                accessKeyId: AKIATEST
                secretAccessKey: aws-secret
                region: eu-west-1
                service: execute-api
            """);
        List<AuthRef> refs = List.of(
            ref("bearer", AuthApplyAs.HTTP_AUTHORIZATION_BEARER),
            ref("basic", AuthApplyAs.HTTP_HEADER),
            new AuthRef("api", AuthApplyAs.HTTP_HEADER, "X-Api-Key", null, null),
            ref("query", AuthApplyAs.HTTP_QUERY_PARAM),
            new AuthRef("hmac", AuthApplyAs.HMAC_HEADER, "X-Signature", null, null),
            new AuthRef("aws", AuthApplyAs.HTTP_HEADER, "Authorization", null, null)
        );
        TestContext context = new TestContext();
        AuthRuntime runtime = runtime(templates, refs, Map.of(), context, (template, ignored) -> template);

        AuthRuntime.MutableHttpRequest bearer = new AuthRuntime.MutableHttpRequest("GET", "/bearer", Map.of(), "");
        runtime.applyHttp(refs.get(0), bearer, null, context);
        assertThat(bearer.headers()).containsEntry("Authorization", "Bearer bearer-token");

        AuthRuntime.MutableHttpRequest basic = new AuthRuntime.MutableHttpRequest("GET", "/basic", Map.of(), "");
        runtime.applyHttp(refs.get(1), basic, null, context);
        assertThat(basic.headers()).containsEntry(
            "Authorization",
            "Basic " + Base64.getEncoder().encodeToString("alice:wonder".getBytes(StandardCharsets.UTF_8))
        );

        AuthRuntime.MutableHttpRequest api = new AuthRuntime.MutableHttpRequest("GET", "/api", Map.of(), "");
        runtime.applyHttp(refs.get(2), api, null, context);
        assertThat(api.headers()).containsEntry("X-Api-Key", "api-key-1");

        AuthRuntime.MutableHttpRequest query = new AuthRuntime.MutableHttpRequest("GET", "/search?q=1", Map.of(), "");
        runtime.applyHttp(refs.get(3), query, null, context);
        assertThat(query.path()).isEqualTo("/search?q=1&access_token=query-token");

        AuthRuntime.MutableHttpRequest hmac = new AuthRuntime.MutableHttpRequest("POST", "/signed", Map.of(), "payload");
        runtime.applyHttp(refs.get(4), hmac, null, context);
        assertThat(hmac.headers().get("X-Signature")).matches("[0-9a-f]{64}");

        AuthRuntime.MutableHttpRequest aws = new AuthRuntime.MutableHttpRequest("POST", "/aws", Map.of(), "payload");
        runtime.applyHttp(refs.get(5), aws, null, context);
        assertThat(aws.headers().get("Authorization"))
            .startsWith("AWS4-HMAC-SHA256 Credential=AKIATEST, Signature=");
    }

    @Test
    void appliesTcpIsoAndTransportStrategies() throws Exception {
        Path templates = profiles("""
            profiles:
              prefix:
                type: MESSAGE_FIELD_AUTH
                storage:
                  mode: NONE
                value: "AUTH:"
              hmac:
                type: HMAC_SIGNATURE
                storage:
                  mode: NONE
                secretKey: signing-secret
              iso:
                type: ISO8583_MAC
                storage:
                  mode: NONE
                macKey: iso-secret
              mtls:
                type: TLS_CLIENT_CERT
                storage:
                  mode: NONE
                keyStorePath: /certs/client.p12
                keyStorePassword: changeit
                keyStoreType: PKCS12
            """);
        List<AuthRef> refs = List.of(
            ref("prefix", AuthApplyAs.TCP_PAYLOAD_PREFIX),
            new AuthRef("hmac", AuthApplyAs.HMAC_PAYLOAD_FIELD, null, null, "mac"),
            ref("iso", AuthApplyAs.ISO8583_MAC_FIELD),
            ref("mtls", AuthApplyAs.MTLS_CLIENT_CERT)
        );
        TestContext context = new TestContext();
        AuthRuntime runtime = runtime(templates, refs, Map.of(), context, (template, ignored) -> template);

        assertThat(runtime.applyTcpBody(refs.get(0), "payload", null, context)).isEqualTo("AUTH:payload");
        assertThat(runtime.applyTcpBody(refs.get(1), "payload", null, context))
            .matches("payload\\nmac=[0-9a-f]{64}");
        assertThat(runtime.applyIsoPayloadHex(refs.get(2), "0200A1B2", null, context))
            .matches("0200A1B2[0-9A-F]{64}");
        assertThat(runtime.transportOptions(refs.get(3), context))
            .containsEntry("ssl", true)
            .containsEntry("keyStorePath", "/certs/client.p12")
            .containsEntry("keyStorePassword", "changeit")
            .containsEntry("keyStoreType", "PKCS12");
    }

    @Test
    void resolvesVarsSwarmWorkerAndFileReferences() throws Exception {
        Path secret = Files.createTempFile("auth-secret", ".txt");
        Files.writeString(secret, "file-token\n");
        Path templates = profiles("""
            profiles:
              templated:
                type: STATIC_TOKEN
                storage:
                  mode: NONE
                token: "{{ vars.token }}:{{ swarm.id }}:{{ worker.id }}"
              file:
                type: STATIC_TOKEN
                storage:
                  mode: NONE
                token:
                  file: "%s"
            """.formatted(secret.toString().replace("\\", "\\\\")));
        List<AuthRef> refs = List.of(
            ref("templated", AuthApplyAs.HTTP_AUTHORIZATION_BEARER),
            ref("file", AuthApplyAs.HTTP_AUTHORIZATION_BEARER)
        );
        TestContext context = new TestContext();
        AuthRuntime runtime = runtime(templates, refs, Map.of("token", "var-token"), context, new PebbleTemplateRenderer());

        AuthRuntime.MutableHttpRequest templated = new AuthRuntime.MutableHttpRequest("GET", "/templated", Map.of(), "");
        runtime.applyHttp(refs.get(0), templated, null, context);
        assertThat(templated.headers()).containsEntry("Authorization", "Bearer var-token:swarm-1:worker-1");

        AuthRuntime.MutableHttpRequest fromFile = new AuthRuntime.MutableHttpRequest("GET", "/file", Map.of(), "");
        runtime.applyHttp(refs.get(1), fromFile, null, context);
        assertThat(fromFile.headers()).containsEntry("Authorization", "Bearer file-token");
    }

    @Test
    void rejectsSameTokenKeyWithDifferentFingerprintsBeforeUsingRedis() throws Exception {
        Path templates = profiles("""
            profiles:
              one:
                type: OAUTH2_CLIENT_CREDENTIALS
                storage:
                  mode: REDIS
                  tokenKey: shared-token
                tokenUrl: http://auth-one/token
                clientId: client
                clientSecret: secret-one
              two:
                type: OAUTH2_CLIENT_CREDENTIALS
                storage:
                  mode: REDIS
                  tokenKey: shared-token
                tokenUrl: http://auth-two/token
                clientId: client
                clientSecret: secret-two
            """);

        assertThatThrownBy(() -> runtime(
            templates,
            List.of(ref("one", AuthApplyAs.HTTP_AUTHORIZATION_BEARER), ref("two", AuthApplyAs.HTTP_AUTHORIZATION_BEARER)),
            Map.of(),
            new TestContext(),
            (template, ignored) -> template))
            .isInstanceOf(AuthFailureException.class)
            .hasMessageContaining("tokenKey 'shared-token' with multiple configs");
    }

    @Test
    void reportsOnlyFirstFailureThenRecoveryWithRedactedStatus() throws Exception {
        clearAuthFailureState();
        TestContext context = new TestContext();
        AuthRef ref = ref("api:static", AuthApplyAs.HTTP_AUTHORIZATION_BEARER);

        reportFailure(ref, "HTTP", context, new IllegalStateException("secret-token-value"));
        reportFailure(ref, "HTTP", context, new IllegalStateException("another-secret-token"));

        assertThat(context.statusPublisher().updates()).isEqualTo(1);
        assertThat(context.statusPublisher().authData())
            .containsEntry("status", "failure")
            .containsEntry("profileId", "api:static")
            .containsEntry("count", 1);
        assertThat(context.statusPublisher().authData().toString()).doesNotContain("secret-token-value");
        assertThat(context.meterRegistry().find("pockethive.auth.apply.failure").counter().count()).isEqualTo(2.0);

        reportRecovery(ref, "HTTP", context);

        assertThat(context.statusPublisher().updates()).isEqualTo(2);
        assertThat(context.statusPublisher().authData())
            .containsEntry("status", "recovered")
            .containsEntry("count", 2);
        assertThat(context.meterRegistry().find("pockethive.auth.apply.recovery").counter().count()).isEqualTo(1.0);
        clearAuthFailureState();
    }

    @Test
    void authFailureJournalDeduplicatorAllowsOnlyFirstIdenticalFailurePerScope() {
        AuthFailureJournalDeduplicator deduplicator = new AuthFailureJournalDeduplicator();
        AuthFailureException failure = AuthFailureException.configuration(
            "legacy-inline-auth",
            "Template example.yaml uses legacy auth; use authRef",
            null
        );

        assertThat(deduplicator.record("swarm:worker:request-builder", failure).firstOccurrence()).isTrue();
        AuthFailureJournalDeduplicator.Decision repeated =
            deduplicator.record("swarm:worker:request-builder", failure);

        assertThat(repeated.firstOccurrence()).isFalse();
        assertThat(repeated.occurrences()).isEqualTo(2);
        assertThat(deduplicator.record("other-scope", failure).firstOccurrence()).isTrue();
    }

    private static Path profiles(String yaml) throws Exception {
        Path scenario = Files.createTempDirectory("auth-profile");
        Path templates = Files.createDirectories(scenario.resolve("templates"));
        Files.writeString(scenario.resolve("authProfiles.yaml"), yaml);
        return templates;
    }

    private static AuthRuntime runtime(
        Path templates,
        List<AuthRef> refs,
        Map<String, Object> vars,
        TestContext context,
        TemplateRenderer renderer
    ) {
        RedisSequenceProperties redis = new RedisSequenceProperties();
        redis.setHost("127.0.0.1");
        redis.setPort(6379);
        return AuthRuntime.forTemplates(templates.toString(), refs, vars, context, renderer, redis);
    }

    private static AuthRef ref(String profileId, AuthApplyAs applyAs) {
        return new AuthRef(profileId, applyAs, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static void clearAuthFailureState() throws Exception {
        var field = AuthRuntime.class.getDeclaredField("FAILURES");
        field.setAccessible(true);
        ((ConcurrentMap<String, ?>) field.get(null)).clear();
    }

    private static void reportFailure(AuthRef ref, String stage, WorkerContext context, RuntimeException ex) throws Exception {
        Method method = AuthRuntime.class.getDeclaredMethod(
            "reportFailure",
            AuthRef.class,
            String.class,
            WorkerContext.class,
            RuntimeException.class
        );
        method.setAccessible(true);
        method.invoke(null, ref, stage, context, ex);
    }

    private static void reportRecovery(AuthRef ref, String stage, WorkerContext context) throws Exception {
        Method method = AuthRuntime.class.getDeclaredMethod("reportRecovery", AuthRef.class, String.class, WorkerContext.class);
        method.setAccessible(true);
        method.invoke(null, ref, stage, context);
    }

    private static final class TestContext implements WorkerContext {
        private final MeterRegistry meters = new SimpleMeterRegistry();
        private final CapturingStatusPublisher statusPublisher = new CapturingStatusPublisher();

        @Override
        public WorkerInfo info() {
            return new WorkerInfo("worker", "swarm-1", "worker-1", null, null);
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public <C> C config(Class<C> type) {
            return null;
        }

        @Override
        public CapturingStatusPublisher statusPublisher() {
            return statusPublisher;
        }

        @Override
        public Logger logger() {
            return LoggerFactory.getLogger("test");
        }

        @Override
        public MeterRegistry meterRegistry() {
            return meters;
        }

        @Override
        public ObservationRegistry observationRegistry() {
            return ObservationRegistry.NOOP;
        }

        @Override
        public ObservabilityContext observabilityContext() {
            return new ObservabilityContext();
        }

        @Override
        public HistoryPolicy historyPolicy() {
            return HistoryPolicy.FULL;
        }
    }

    private static final class CapturingStatusPublisher implements StatusPublisher {
        private final Map<String, Object> data = new LinkedHashMap<>();
        private int updates;
        private final MutableStatus mutableStatus = new MutableStatus() {
            @Override
            public MutableStatus data(String key, Object value) {
                data.put(key, value);
                return this;
            }
        };

        @Override
        public void update(java.util.function.Consumer<MutableStatus> consumer) {
            updates++;
            consumer.accept(mutableStatus);
        }

        int updates() {
            return updates;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> authData() {
            return (Map<String, Object>) data.get("auth");
        }
    }
}
