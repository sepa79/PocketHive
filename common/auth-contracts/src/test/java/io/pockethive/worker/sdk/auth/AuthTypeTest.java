package io.pockethive.worker.sdk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AuthTypeTest {
    @ParameterizedTest
    @EnumSource(AuthType.class)
    @ResourceLock("default-locale-timezone")
    void acceptedNamesAndCanonicalKeysAreIndependentOfDefaultLocale(AuthType type) {
        Locale original = Locale.getDefault();
        Locale originalDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        Locale originalFormat = Locale.getDefault(Locale.Category.FORMAT);
        String canonical = type.key();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(AuthType.parse(canonical)).isEqualTo(type);
            assertThat(AuthType.parse(type.name())).isEqualTo(type);
            assertThat(AuthType.normalize(type.name())).isEqualTo(canonical);
            assertThat(type.key()).isEqualTo(canonical);
        } finally {
            Locale.setDefault(original);
            Locale.setDefault(Locale.Category.DISPLAY, originalDisplay);
            Locale.setDefault(Locale.Category.FORMAT, originalFormat);
        }
    }

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
