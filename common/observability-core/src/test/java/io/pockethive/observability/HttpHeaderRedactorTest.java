package io.pockethive.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpHeaderRedactorTest {
    @ParameterizedTest
    @ValueSource(strings = {"Authorization", "authorization", "aUtHoRiZaTiOn",
        "Proxy-Authorization", "proxy-authorization", "pRoXy-AuThOrIzAtIoN",
        "Cookie", "cookie", "cOoKiE", "Set-Cookie", "set-cookie", "sEt-CoOkIe"})
    void redactsTheWholeScalarCredentialWithoutChangingSourceHeaders(String name) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(name, "Custom disposable-credential-A9+/=");
        headers.put("Content-Type", "application/json");
        Map<String, String> before = new LinkedHashMap<>(headers);

        Map<String, String> projected = HttpHeaderRedactor.redact(headers);

        assertThat(projected).containsEntry(name, "[REDACTED]")
            .containsEntry("Content-Type", "application/json");
        assertThat(projected.keySet()).containsExactlyElementsOf(headers.keySet());
        assertThat(headers).isEqualTo(before);
        assertThat(projected.toString()).doesNotContain("disposable-credential");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Authorization", "authorization", "aUtHoRiZaTiOn",
        "Proxy-Authorization", "proxy-authorization", "pRoXy-AuThOrIzAtIoN",
        "Cookie", "cookie", "cOoKiE", "Set-Cookie", "set-cookie", "sEt-CoOkIe"})
    void redactsEveryCredentialValueWithoutChangingSourceLists(String name) {
        List<String> credentials = new ArrayList<>(List.of("Bearer first-credential", "Basic second-credential"));
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put(name, credentials);
        headers.put("Accept", List.of("application/json", "text/plain"));

        Map<String, List<String>> projected = HttpHeaderRedactor.redactValues(headers);

        assertThat(projected.get(name)).containsExactly("[REDACTED]", "[REDACTED]");
        assertThat(projected.get("Accept")).containsExactly("application/json", "text/plain");
        assertThat(projected.keySet()).containsExactlyElementsOf(headers.keySet());
        assertThat(credentials).containsExactly("Bearer first-credential", "Basic second-credential");
        assertThat(headers.get(name)).isSameAs(credentials);
        assertThat(projected.toString()).doesNotContain("first-credential", "second-credential");
    }

    @Test
    void preservesScalarOrderAndSafeNearNamesWhileRedactingDuplicateCaseVariants() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer first");
        headers.put("X-Authorization", "safe-auth-description");
        headers.put("authorization", "Bearer second");
        headers.put("X-Cookie", "safe-cookie-description");
        headers.put("X-Nullable", null);

        Map<String, String> projected = HttpHeaderRedactor.redact(headers);
        headers.put("X-Authorization", "changed-after-projection");
        headers.put("New-Header", "new-value");

        assertThat(projected.keySet()).containsExactly("Authorization", "X-Authorization", "authorization",
            "X-Cookie", "X-Nullable");
        assertThat(projected).containsEntry("Authorization", "[REDACTED]")
            .containsEntry("authorization", "[REDACTED]")
            .containsEntry("X-Authorization", "safe-auth-description")
            .containsEntry("X-Cookie", "safe-cookie-description")
            .containsEntry("X-Nullable", null);
    }

    @Test
    void takesIndependentSnapshotsOfMultiValueHeadersIncludingSafeNullValues() {
        List<String> safeValues = new ArrayList<>(Arrays.asList("first-safe", null, "second-safe"));
        List<String> credentials = new ArrayList<>(List.of("session=first", "session=second"));
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("X-Safe", safeValues);
        headers.put("Set-Cookie", credentials);
        headers.put("set-cookie", List.of("session=third"));

        Map<String, List<String>> projected = HttpHeaderRedactor.redactValues(headers);
        safeValues.set(0, "changed-after-projection");
        credentials.clear();
        headers.clear();

        assertThat(projected.keySet()).containsExactly("X-Safe", "Set-Cookie", "set-cookie");
        assertThat(projected.get("X-Safe")).containsExactly("first-safe", null, "second-safe");
        assertThat(projected.get("Set-Cookie")).containsExactly("[REDACTED]", "[REDACTED]");
        assertThat(projected.get("set-cookie")).containsExactly("[REDACTED]");
    }

    @Test
    void preservesEmptyMapsAndEmptyValueLists() {
        assertThat(HttpHeaderRedactor.redact(Map.of())).isEmpty();
        assertThat(HttpHeaderRedactor.redactValues(Map.of())).isEmpty();
        Map<String, List<String>> projected = HttpHeaderRedactor.redactValues(
            Map.of("Authorization", List.of(), "X-Safe", List.of()));
        assertThat(projected).containsEntry("Authorization", List.of()).containsEntry("X-Safe", List.of());
    }
}
