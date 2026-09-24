package io.pockethive.tcpmock.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Service;

/**
 * Responsibility: read documentation using the existing locations and order, closing opened streams.
 * Must not: validate HTTP input or construct HTTP responses.
 * Contract: RESP-TCP-MOCK-WEB-TOOLS — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-web-tools.
 */
@Service
public class DocumentationReader {
    public String read(String filename) throws IOException {
        Path path = Path.of("/app/docs", filename);
        if (Files.exists(path)) {
            return Files.readString(path);
        }

        InputStream resource = getClass().getClassLoader().getResourceAsStream("docs/" + filename);
        if (resource == null) {
            resource = getClass().getClassLoader().getResourceAsStream(filename);
        }
        if (resource == null) {
            return null;
        }
        try (InputStream input = resource) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
