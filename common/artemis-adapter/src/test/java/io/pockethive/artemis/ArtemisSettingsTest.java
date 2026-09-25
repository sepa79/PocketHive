package io.pockethive.artemis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.artemis.api.ArtemisConnectionSettings;
import io.pockethive.artemis.api.ArtemisInputSettings;
import io.pockethive.artemis.api.ArtemisOutputSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ArtemisSettingsTest {
    @ParameterizedTest
    @ValueSource(strings = {"", "amqp://host:5672", "tcp://host", "tcp://host:70000", "tcp://user:secret@host:61616",
        "tcp://host:61616?reconnectAttempts=99", "tcp://host:61616/path", "vm://-1"})
    void rejectsMissingOrUnsupportedEndpointsAndEmbeddedOverrides(String uri) {
        assertThatThrownBy(() -> new ArtemisConnectionSettings(uri, "user", "secret", 2000))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresExplicitCredentialsAndBoundedTimeout() {
        assertThatThrownBy(() -> new ArtemisConnectionSettings("tcp://host:61616", null, "secret", 2000))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArtemisConnectionSettings("tcp://host:61616", "user", "", 2000))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArtemisConnectionSettings("tcp://host:61616", "user", "secret", 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void connectionIdentityExcludesSecretButIncludesEndpointAndPrincipal() {
        var settings = new ArtemisConnectionSettings("tcp://host:61616", "user", " secret ", 2000);
        assertThat(settings.password()).isEqualTo(" secret ");
        assertThat(settings.toString()).doesNotContain("secret", "host", "user");
        assertThat(settings.identity()).isEqualTo(new ArtemisConnectionSettings("tcp://host:61616", "user", "rotated", 500).identity());
        assertThat(settings.identity()).isNotEqualTo(new ArtemisConnectionSettings("tcp://other:61616", "user", " secret ", 2000).identity());
        assertThat(settings.identity()).isNotEqualTo(new ArtemisConnectionSettings("tcp://host:61616", "other", " secret ", 2000).identity());
    }

    @Test
    void settingsValidateResolvedRoutesAndConsumerWindow() {
        assertThatThrownBy(() -> new ArtemisInputSettings(" ", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArtemisInputSettings("jobs", -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArtemisOutputSettings(null, true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ArtemisInputSettings(" jobs ", 0).inboundRoute()).isEqualTo("jobs");
        assertThat(new ArtemisOutputSettings(" results ", false).outboundRoute()).isEqualTo("results");
    }
}
