package io.pockethive.scenarios.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.worker.sdk.auth.AuthType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AuthProfileStorageFindingsTest {
    private static final String PROFILE_PATH = "authProfiles.yaml:profiles.api";
    private final AuthProfileStorageFindings projection = new AuthProfileStorageFindings();

    @ParameterizedTest
    @EnumSource(value = AuthType.class,
        names = {"OAUTH2_CLIENT_CREDENTIALS", "OAUTH2_PASSWORD_GRANT", "OAUTH2_HTTP_SIGNATURE"})
    void acceptsRedisForEveryOAuthTypeAndPreservesBoundaryNormalization(AuthType type) {
        assertThat(findings(profile(type.name(), "REDIS", "api:token"))).isEmpty();
        assertThat(findings(profile(" " + type.key() + " ", " redis ", " api:token "))).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = AuthType.class,
        names = {"OAUTH2_CLIENT_CREDENTIALS", "OAUTH2_PASSWORD_GRANT", "OAUTH2_HTTP_SIGNATURE"})
    void rejectsStorageNoneForEveryOAuthType(AuthType type) {
        assertThat(findings(profile(type.name(), "NONE", "api:token"))).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("AUTH_STORAGE_INVALID");
            assertThat(finding.path()).isEqualTo(PROFILE_PATH + ".storage.mode");
            assertThat(finding.message()).isEqualTo("Refreshable auth profile 'api' must use storage.mode=REDIS.");
            assertThat(finding.fix()).isEqualTo("Set storage.mode: REDIS and provide a tokenKey.");
        });
    }

    @Test
    void rejectsUnknownTypeBeforeInspectingStorage() {
        assertThat(findings(profile("invented", "INVALID", "../token"))).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("AUTH_PROFILES_INVALID");
            assertThat(finding.path()).isEqualTo(PROFILE_PATH + ".type");
            assertThat(finding.message()).isEqualTo("Auth profile 'api' declares unsupported type 'invented'.");
            assertThat(finding.fix()).isEqualTo("Use one of: bearer-token, basic-auth, api-key, "
                + "oauth2-client-credentials, oauth2-password-grant, hmac-signature, tls-client-cert, "
                + "message-field-auth, static-token, aws-signature-v4, iso8583-mac, oauth2-http-signature.");
        });
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "NONE"})
    void preservesRequiredTypeFinding(String type) {
        assertThat(findings(profile(type, "REDIS", "api:token"))).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("AUTH_PROFILES_INVALID");
            assertThat(finding.path()).isEqualTo(PROFILE_PATH + ".type");
            assertThat(finding.message()).isEqualTo("Auth profile 'api' must declare type.");
            assertThat(finding.fix()).isEqualTo("Set a concrete auth profile type.");
        });
    }

    @Test
    void preservesUnknownStorageFindingAndDoesNotContinueToTokenKey() {
        assertThat(findings(profile("OAUTH2_HTTP_SIGNATURE", "custom-store", "../token")))
            .singleElement().satisfies(finding -> {
                assertThat(finding.code()).isEqualTo("AUTH_STORAGE_INVALID");
                assertThat(finding.path()).isEqualTo(PROFILE_PATH + ".storage.mode");
                assertThat(finding.message())
                    .isEqualTo("Auth profile 'api' declares unsupported storage.mode 'custom-store'.");
                assertThat(finding.fix()).isEqualTo("Use one of: REDIS, NONE.");
            });
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "../token", "api/token", "api..token", "token?query"})
    void reportsInvalidRedisTokenKeyAtItsExistingPath(String tokenKey) {
        assertThat(findings(profile("OAUTH2_HTTP_SIGNATURE", "REDIS", tokenKey)))
            .singleElement().satisfies(finding -> {
                assertThat(finding.code()).isEqualTo("AUTH_STORAGE_INVALID");
                assertThat(finding.path()).isEqualTo(PROFILE_PATH + ".storage.tokenKey");
                assertThat(finding.message()).isEqualTo("Auth profile 'api' must declare a valid storage.tokenKey.");
                assertThat(finding.fix())
                    .isEqualTo("Use a non-blank tokenKey matching [A-Za-z0-9._:-]{1,128} without '..'.");
            });
    }

    @Test
    void preservesTokenKeyThenModeFindingOrderForNonRefreshProfile() {
        List<ValidationFinding> findings = findings(profile("STATIC_TOKEN", "REDIS", "../token"));
        assertThat(findings).extracting(ValidationFinding::code)
            .containsExactly("AUTH_STORAGE_INVALID", "AUTH_STORAGE_INVALID");
        assertThat(findings).extracting(ValidationFinding::path)
            .containsExactly(PROFILE_PATH + ".storage.tokenKey", PROFILE_PATH + ".storage.mode");
        assertThat(findings.get(1).message()).isEqualTo("Non-refresh auth profile 'api' must use storage.mode=NONE.");
        assertThat(findings.get(1).fix()).isEqualTo("Set storage.mode: NONE.");
    }

    @Test
    void retainsExistingNoneDefaultForMissingOrNonTextStorageInputs() {
        for (Object storage : List.of(Map.of(), Map.of("mode", ""), Map.of("mode", " "),
            Map.of("mode", 7), "not-an-object")) {
            assertThat(findings(Map.of("type", "STATIC_TOKEN", "storage", storage))).isEmpty();
            assertThat(findings(Map.of("type", "OAUTH2_HTTP_SIGNATURE", "storage", storage)))
                .singleElement().satisfies(finding -> assertThat(finding.path())
                    .isEqualTo(PROFILE_PATH + ".storage.mode"));
        }
        assertThat(findings(Map.of("type", "STATIC_TOKEN"))).isEmpty();
        assertThat(findings(Map.of("type", "OAUTH2_HTTP_SIGNATURE"))).hasSize(1);
    }

    @Test
    void leavesResolvedSigningConfigurationToTheWorkerBoundary() {
        Map<String, Object> profile = profile("OAUTH2_HTTP_SIGNATURE", "REDIS", "api:token");
        profile.put("privateKey", Map.of("file", "/not-mounted-at-authoring/signing.pem"));
        profile.put("tokenUrl", "{{ sut.endpoints.auth.baseUrl }}/token");
        profile.put("scopes", List.of());
        assertThat(findings(profile)).isEmpty();
    }

    private List<ValidationFinding> findings(Map<?, ?> profile) {
        List<ValidationFinding> findings = new ArrayList<>();
        projection.validate("authProfiles.yaml", "api", profile, findings);
        assertThat(findings).allSatisfy(finding -> {
            assertThat(finding.category()).isEqualTo(ValidationCategory.AUTH);
            assertThat(finding.severity()).isEqualTo(ValidationSeverity.ERROR);
        });
        return findings;
    }

    private static Map<String, Object> profile(String type, String mode, String tokenKey) {
        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("mode", mode);
        storage.put("tokenKey", tokenKey);
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("type", type);
        profile.put("storage", storage);
        return profile;
    }
}
