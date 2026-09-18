package io.pockethive.worker.sdk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AuthTypeTest {
    @ParameterizedTest
    @EnumSource(value = AuthType.class,
        names = {"OAUTH2_CLIENT_CREDENTIALS", "OAUTH2_PASSWORD_GRANT", "OAUTH2_HTTP_SIGNATURE"})
    void oauthProfilesRequireSharedRedisStorageInBothAcceptedTypeForms(AuthType type) {
        assertThat(AuthType.parse(type.name()).requiredStorageMode()).isEqualTo(AuthStorageMode.REDIS);
        assertThat(AuthType.parse(type.key()).requiredStorageMode()).isEqualTo(AuthStorageMode.REDIS);
    }

    @ParameterizedTest
    @EnumSource(value = AuthType.class, mode = EnumSource.Mode.EXCLUDE,
        names = {"OAUTH2_CLIENT_CREDENTIALS", "OAUTH2_PASSWORD_GRANT", "OAUTH2_HTTP_SIGNATURE"})
    void otherAuthTypesDoNotSelectTokenStorage(AuthType type) {
        assertThat(type.requiredStorageMode()).isEqualTo(AuthStorageMode.NONE);
    }
}
