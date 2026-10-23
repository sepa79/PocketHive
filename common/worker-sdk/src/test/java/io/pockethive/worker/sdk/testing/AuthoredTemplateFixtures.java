package io.pockethive.worker.sdk.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Path;

public final class AuthoredTemplateFixtures {
    private AuthoredTemplateFixtures() {
    }

    public static JsonNode read(String repositoryPath) throws IOException {
        Path fixture = Path.of("../..").resolve(repositoryPath);
        return new ObjectMapper(new YAMLFactory()).readTree(fixture.toFile());
    }
}
