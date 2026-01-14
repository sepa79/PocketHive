package io.pockethive.tcpmock.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class UiAuthHeaderTest {
    private static final Pattern RAW_PROTECTED_FETCH =
        Pattern.compile("(?<!\\.)fetch\\s*\\(['\\\"]/(api|__admin)");

    @Test
    void appUsesHttpClientForProtectedEndpoints() throws IOException {
        String content = Files.readString(Path.of("src/main/resources/static/app-ultimate.js"));
        assertFalse(
            RAW_PROTECTED_FETCH.matcher(content).find(),
            "app-ultimate.js uses raw fetch for protected endpoints"
        );
    }

    @Test
    void recordingUsesHttpClientForProtectedEndpoints() throws IOException {
        String content = Files.readString(Path.of("src/main/resources/static/recording.js"));
        assertFalse(
            RAW_PROTECTED_FETCH.matcher(content).find(),
            "recording.js uses raw fetch for protected endpoints"
        );
    }
}
