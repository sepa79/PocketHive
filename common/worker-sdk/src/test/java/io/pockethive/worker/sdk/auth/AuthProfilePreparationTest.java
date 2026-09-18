package io.pockethive.worker.sdk.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.pockethive.work.api.WorkerContext;
import io.pockethive.work.api.WorkerInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AuthProfilePreparationTest {
    private static final String PRIVATE_KEY = testPrivateKey();
    private static final String TOKEN_KEY = "signed-api";
    @TempDir Path temporary;

    @Test
    void rendersNestedValuesWithoutMutatingAuthoredProfile() {
        AuthProfile authored = profile();
        authored.putProperty("metadata", Map.of("values", List.of("{{ vars.id }}", "{{ sut.id }}")));
        assertThat(AuthProfilePreparation.referencesSut(authored)).isTrue();
        AuthProfile resolved = AuthProfilePreparation.resolveProfile(authored,
            Map.of("id", "client"), Map.of("id", "target"), context(), (text, values) -> {
                assertThat(values).containsEntry("vars", Map.of("id", "client"))
                    .containsEntry("sut", Map.of("id", "target"))
                    .containsEntry("swarm", Map.of("id", "swarm"))
                    .containsEntry("worker", Map.of("id", "instance", "role", "worker"));
                return text.replace("{{ vars.id }}", "client").replace("{{ sut.id }}", "target");
            });
        assertThat(resolved.mergedProperties().get("metadata"))
            .isEqualTo(Map.of("values", List.of("client", "target")));
        assertThat(AuthProfilePreparation.referencesSut(resolved)).isFalse();
        assertThat(authored.mergedProperties().get("metadata"))
            .isEqualTo(Map.of("values", List.of("{{ vars.id }}", "{{ sut.id }}")));
    }

    @Test
    void rejectsMissingSecretSourcesAndLeavesMultiEntryMapsAsConfiguration() {
        AuthProfile profile = profile();
        String missing = "PH_MISSING_AUTH_SECRET_1C14B5C0_A020_4575_B165_12F996201843";
        profile.putProperty("clientId", Map.of("env", missing));
        assertThatThrownBy(() -> AuthProfilePreparation.resolveProfile(profile, Map.of(), Map.of(), context(),
            (text, values) -> text)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Required auth env reference is not set");
        profile.putProperty("clientId", Map.of("file", temporary.resolve("absent").toString()));
        assertThatThrownBy(() -> AuthProfilePreparation.resolveProfile(profile, Map.of(), Map.of(), context(),
            (text, values) -> text)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Required auth file reference is not readable");
        Map<String, String> metadata = Map.of("env", missing, "label", "metadata");
        profile.putProperty("clientId", metadata);
        assertThat(AuthProfilePreparation.resolveProfile(profile, Map.of(), Map.of(), context(),
            (text, values) -> text).mergedProperties().get("clientId")).isEqualTo(metadata);
    }

    @ParameterizedTest
    @CsvSource({"OAUTH2_HTTP_SIGNATURE", "OAUTH2_CLIENT_CREDENTIALS", "OAUTH2_PASSWORD_GRANT"})
    void validatesRefreshableStorageAndTokenKeys(AuthType type) {
        AuthProfile profile = profile();
        profile.setType(type);
        assertThatCode(() -> AuthProfilePreparation.validateProfile("oauth", profile)).doesNotThrowAnyException();
        profile.getStorage().setTokenKey("invalid/key");
        assertThatThrownBy(() -> AuthProfilePreparation.validateProfile("oauth", profile))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid auth tokenKey");
        profile.getStorage().setMode(AuthStorageMode.NONE);
        assertThatThrownBy(() -> AuthProfilePreparation.validateProfile("oauth", profile))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must use storage.mode=REDIS");
    }

    @Test
    void rejectsMissingTypeAndStorageOnStaticProfiles() {
        AuthProfile profile = new AuthProfile();
        assertThatThrownBy(() -> AuthProfilePreparation.validateProfile("static", profile))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must declare type");
        profile.setType(AuthType.NONE);
        assertThatThrownBy(() -> AuthProfilePreparation.validateProfile("static", profile))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must declare type");
        profile.setType(AuthType.STATIC_TOKEN);
        assertThatCode(() -> AuthProfilePreparation.validateProfile("static", profile)).doesNotThrowAnyException();
        profile.getStorage().setMode(AuthStorageMode.REDIS);
        assertThatThrownBy(() -> AuthProfilePreparation.validateProfile("static", profile))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must use storage.mode=NONE");
    }

    @Test
    void distinctKeysScopesAndAudienceChangeFingerprint() throws Exception {
        String original = AuthProfilePreparation.fingerprint(profile());
        for (Map.Entry<String, Object> change : Map.<String, Object>of(
            "privateKey", testPrivateKey(), "keyId", "key-2", "scopes", List.of("write"), "audience", "api-2").entrySet()) {
            AuthProfile altered = profile();
            altered.putProperty(change.getKey(), change.getValue());
            assertThat(AuthProfilePreparation.fingerprint(altered)).isNotEqualTo(original);
        }
    }

    @Test
    void fingerprintIgnoresMapInsertionOrderIncludingNestedValues() throws Exception {
        AuthProfile first = profile();
        AuthProfile second = profile();
        Map<String, Object> forward = new java.util.LinkedHashMap<>();
        forward.put("alpha", "one");
        forward.put("beta", List.of(Map.of("left", 1, "right", 2)));
        Map<String, Object> reverse = new java.util.LinkedHashMap<>();
        reverse.put("beta", List.of(Map.of("right", 2, "left", 1)));
        reverse.put("alpha", "one");
        first.setHttp(Map.of("metadata", forward));
        second.setHttp(Map.of("metadata", reverse));
        first.putProperty("keyMetadata", forward);
        second.putProperty("keyMetadata", reverse);
        assertThat(AuthProfilePreparation.fingerprint(first)).isEqualTo(AuthProfilePreparation.fingerprint(second));
    }

    @ParameterizedTest
    @CsvSource({"OAUTH2_HTTP_SIGNATURE,true", "OAUTH2_CLIENT_CREDENTIALS,false", "OAUTH2_PASSWORD_GRANT,false"})
    void fileIdentifiersPreserveWhitespaceOnlyForNewProfile(AuthType type, boolean preserve) throws Exception {
        Path identifier = temporary.resolve("identifier.txt");
        Files.writeString(identifier, " exact-value ");
        AuthProfile profile = profile();
        profile.setType(type);
        for (String field : List.of("clientId", "keyId", "audience")) {
            profile.putProperty(field, Map.of("file", identifier.toString()));
        }
        AuthProfile resolved = AuthProfilePreparation.resolveProfile(profile, Map.of(), Map.of(), context(),
            (template, ignored) -> template);
        for (String field : List.of("clientId", "keyId", "audience")) {
            assertThat(resolved.mergedProperties().get(field)).isEqualTo(preserve ? " exact-value " : "exact-value");
        }
    }

    @Test
    void requiresLeaseToAllowAcquisitionBeforeTheSafetyReserve() {
        AuthProfile profile = profile();
        assertThatCode(() -> AuthProfilePreparation.validateProfile("signed", profile)).doesNotThrowAnyException();
        profile.getRefresh().setLeaseSeconds(2);
        assertThatCode(() -> AuthProfilePreparation.validateProfile("signed", profile)).doesNotThrowAnyException();
        profile.getRefresh().setLeaseSeconds(1);
        assertThatThrownBy(() -> AuthProfilePreparation.validateProfile("signed", profile))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("refresh.leaseSeconds must be at least 2");
    }

    private static WorkerContext context() {
        WorkerContext context = mock(WorkerContext.class);
        when(context.info()).thenReturn(new WorkerInfo("worker", "swarm", "instance", null, null));
        return context;
    }

    private static AuthProfile profile() {
        AuthProfile result = new AuthProfile();
        result.setType(AuthType.OAUTH2_HTTP_SIGNATURE);
        result.getStorage().setMode(AuthStorageMode.REDIS);
        result.getStorage().setTokenKey(TOKEN_KEY);
        result.putProperty("tokenUrl", "https://auth.example.test/oauth/token");
        result.putProperty("clientId", "client-1");
        result.putProperty("keyId", "key-1");
        result.putProperty("privateKey", PRIVATE_KEY);
        result.putProperty("scopes", List.of("read"));
        return result;
    }

    private static String testPrivateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }
}
