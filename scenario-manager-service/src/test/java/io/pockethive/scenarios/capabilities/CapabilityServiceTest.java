package io.pockethive.scenarios.capabilities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class CapabilityServiceTest {

    @TempDir
    Path tempDir;

    CapabilityService capabilityService;

    @BeforeEach
    void setUp() {
        capabilityService = new CapabilityService(new Jackson2ObjectMapperBuilder(), tempDir.toString());
    }

    @Test
    void loadsCapabilitiesFromJsonAndYaml() throws IOException {
        copyResource("capabilities/valid-generator.json", "generator.json");
        copyResource("capabilities/valid-processor.yaml", "processor.yaml");

        capabilityService.reload();

        assertThat(capabilityService.getAllCapabilities()).hasSize(2);
        assertThat(capabilityService.findByDigest(
                "sha256:1111111111111111111111111111111111111111111111111111111111111111")).isPresent();
        assertThat(capabilityService.findByImageNameAndTag("ghcr.io/pockethive/processor", "2.0.0")).isPresent();
    }

    @Test
    void throwsWhenSchemaVersionUnsupported() throws IOException {
        copyResource("capabilities/invalid-schema.json", "invalid.json");

        assertThatThrownBy(() -> capabilityService.reload())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported schemaVersion");
    }

    @Test
    void throwsWhenDuplicateDigestFound() throws IOException {
        copyResource("capabilities/valid-generator.json", "generator.json");
        copyResource("capabilities/duplicate-digest.json", "duplicate.json");

        assertThatThrownBy(() -> capabilityService.reload())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate manifest for digest");
    }

    @Test
    void throwsWhenDuplicateNameAndTagFound() throws IOException {
        copyResource("capabilities/valid-processor.yaml", "processor.yaml");
        copyResource("capabilities/duplicate-tag.json", "duplicate-tag.json");

        assertThatThrownBy(() -> capabilityService.reload())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate manifest for image");
    }

    @Test
    void matchesManifestByDigestAndTag() throws IOException {
        copyResource("capabilities/valid-generator.json", "generator.json");

        capabilityService.reload();

        CapabilityManifest byDigest = capabilityService
                .findByDigest("sha256:1111111111111111111111111111111111111111111111111111111111111111")
                .orElseThrow();
        CapabilityManifest byTag = capabilityService
                .findByImageNameAndTag("ghcr.io/pockethive/generator", "1.12.0")
                .orElseThrow();

        assertThat(byDigest).isSameAs(byTag);
    }

    private void copyResource(String resource, String targetFileName) throws IOException {
        ClassPathResource classPathResource = new ClassPathResource(resource);
        try (InputStream inputStream = classPathResource.getInputStream()) {
            Files.copy(inputStream, tempDir.resolve(targetFileName));
        }
    }
}
