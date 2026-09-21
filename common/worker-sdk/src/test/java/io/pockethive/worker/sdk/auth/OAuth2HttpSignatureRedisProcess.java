package io.pockethive.worker.sdk.auth;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.work.api.StatusPublisher;
import io.pockethive.work.api.WorkerContext;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.worker.sdk.config.RedisSequenceProperties;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.LoggerFactory;

/** Separate-JVM entrypoint for the real Redis test; it activates the ordinary public profile path. */
public final class OAuth2HttpSignatureRedisProcess {
    static final String PROFILE_ID = "signed";
    static final String TOKEN_KEY = "shared-signed-token";
    static final AuthRef REF = new AuthRef(PROFILE_ID, AuthApplyAs.HTTP_AUTHORIZATION_BEARER, null, null, null);

    private OAuth2HttpSignatureRedisProcess() { }

    public static void main(String[] args) throws Exception {
        AuthRuntime runtime = open(Path.of(args[0]), args[1], "child", args[2], Integer.parseInt(args[3]));
        try {
            AuthRuntime.MutableHttpRequest request = new AuthRuntime.MutableHttpRequest("GET", "/accounts", Map.of(), "");
            boolean expectFailure = "EXPECT_FAILURE".equals(args[4]);
            try {
                runtime.applyHttp(REF, request, null, context(args[1], "child"));
                if (expectFailure) {
                    throw new AssertionError("The failed token endpoint must fail the downstream request");
                }
                if (!Map.of("Authorization", "Bearer " + args[4]).equals(request.headers())) {
                    throw new AssertionError("The public runtime did not apply the shared Redis token");
                }
                System.out.println("SHARED_TOKEN_OK");
            } catch (AuthFailureException failure) {
                if (!expectFailure) { throw failure; }
                if (!request.headers().isEmpty()) {
                    throw new AssertionError("Failed acquisition applied downstream auth material");
                }
                System.out.println("TOKEN_FAILURE_OK");
            }
        } finally {
            close(runtime);
        }
    }

    static AuthRuntime open(Path directory, String swarm, String worker, String host, int port) {
        RedisSequenceProperties redis = new RedisSequenceProperties();
        redis.setHost(host);
        redis.setPort(port);
        return AuthRuntime.forTemplates(directory.resolve("templates").toString(), List.of(REF), Map.of(),
            context(swarm, worker), (template, ignored) -> template, redis);
    }

    static WorkerContext context(String swarm, String worker) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        return (WorkerContext) Proxy.newProxyInstance(WorkerContext.class.getClassLoader(), new Class<?>[] {WorkerContext.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "info" -> new WorkerInfo("worker", swarm, worker, null, null);
                case "meterRegistry" -> registry;
                case "logger" -> LoggerFactory.getLogger(OAuth2HttpSignatureRedisProcess.class);
                case "statusPublisher" -> StatusPublisher.NO_OP;
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    static TokenStore store(AuthRuntime runtime) throws ReflectiveOperationException {
        return (TokenStore) field("tokenStore").get(runtime);
    }

    @SuppressWarnings("unchecked")
    static String fingerprint(AuthRuntime runtime) throws ReflectiveOperationException {
        return ((Map<String, String>) field("fingerprints").get(runtime)).get(PROFILE_ID);
    }

    static void close(AuthRuntime runtime) throws ReflectiveOperationException {
        try { store(runtime).close(); }
        finally { ((HttpClient) field("httpClient").get(runtime)).close(); }
    }

    static String newPrivateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static Field field(String name) throws ReflectiveOperationException {
        Field field = AuthRuntime.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
