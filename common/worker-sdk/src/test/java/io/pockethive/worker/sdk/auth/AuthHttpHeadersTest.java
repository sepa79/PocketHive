package io.pockethive.worker.sdk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AuthHttpHeadersTest {
    @ParameterizedTest
    @ValueSource(strings = {"Authorization", "authorization", "aUtHoRiZaTiOn", "X-Api-Key"})
    void replacesEveryCaseVariantAndPreservesOtherHeaders(String name) {
        Map<String, String> original = Map.of(
            name.toLowerCase(java.util.Locale.ROOT), "old-lower",
            name.toUpperCase(java.util.Locale.ROOT), "old-upper",
            "X-Correlation-Id", "correlation");
        var request = new MutableHttpRequest("GET", "/test", original, "body");

        AuthHttpHeaders.replace(request.headers(), name, "new-credential");

        assertThat(request.headers()).containsExactlyInAnyOrderEntriesOf(Map.of(
            name, "new-credential", "X-Correlation-Id", "correlation"));
        assertThat(original).containsValue("old-lower").containsValue("old-upper");
        assertThat(original).doesNotContainValue("new-credential");
    }
}
