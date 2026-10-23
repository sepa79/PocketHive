package io.pockethive.auth.service.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DeviceLogoutPageRendererTest {
    private final DeviceLogoutPageRenderer pages = new DeviceLogoutPageRenderer();

    @Test
    void confirmationEscapesEveryDisplayAndFormValue() {
        String html = pages.confirmation("https://ph.test/\"><script>issuer</script>",
            "\"><script>client</script>", "<img src=x onerror=alert(1)>",
            "\" autofocus onfocus=alert(1)", "\"><script>token</script>");
        assertThat(html).contains("&lt;img", "&lt;script&gt;", "&quot;", "auth-shell", "auth-card")
            .doesNotContain("<script>", "<img", "name=\"\" autofocus", "value=\"\"><script>");
    }

    @Test
    void completionUsesExistingThemeWithoutAnyAppDestination() {
        String html = pages.complete("https://ph.test/auth-service");
        assertThat(html).contains("https://ph.test/auth-service/oauth/pockethive-auth.css",
                "Shared login signed out", "You can close this tab.")
            .doesNotContain("<form", "<script", "http-equiv", "/auth/callback");
    }
}
