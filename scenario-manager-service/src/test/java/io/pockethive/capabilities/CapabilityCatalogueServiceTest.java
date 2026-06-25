package io.pockethive.capabilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityCatalogueServiceTest {

    @TempDir
    Path capabilitiesDir;

    @Test
    void imageNameLookupCanonicalizesRegistryNamespaceTagAndDigest() throws Exception {
        writeManifest("processor");
        CapabilityCatalogueService catalogue = new CapabilityCatalogueService(capabilitiesDir);
        catalogue.reload();

        assertThat(catalogue.findByImageName("processor")).isPresent();
        assertThat(catalogue.findByImageName("pockethive/processor:0.15")).isPresent();
        assertThat(catalogue.findByImageName("192.168.88.54:5000/pockethive/processor:0.15")).isPresent();
        assertThat(catalogue.findByImageName("192.168.88.54:5000/pockethive/processor@sha256:abc")).isPresent();
    }

    @Test
    void bundledDbQueryCapabilityLoads() throws Exception {
        CapabilityCatalogueService catalogue = new CapabilityCatalogueService(Path.of("capabilities"));
        catalogue.reload();

        CapabilityManifest manifest = catalogue.findByImageName("db-query").orElseThrow();
        assertThat(manifest.role()).isEqualTo("db-query");
        assertThat(manifest.config())
            .extracting(CapabilityManifest.ConfigEntry::name)
            .contains(
                "adapter",
                "connection.jdbcUrl",
                "pool.maxSize",
                "retry.on");
    }

    @Test
    void bundledCapabilitiesDoNotNeedCompatibilityTags() throws Exception {
        CapabilityCatalogueService catalogue = new CapabilityCatalogueService(Path.of("capabilities"));
        catalogue.reload();

        assertThat(catalogue.allManifests())
                .isNotEmpty()
                .allSatisfy(manifest -> assertThat(manifest.image().tag()).isNull());
    }

    @Test
    void bundledIoCapabilitiesExposeScopeAndType() throws Exception {
        CapabilityCatalogueService catalogue = new CapabilityCatalogueService(Path.of("capabilities"));
        catalogue.reload();

        CapabilityManifest manifest = catalogue.findByImageName("io-scheduler").orElseThrow();

        assertThat(manifest.ui()).isNotNull();
        assertThat(manifest.ui().ioScope()).isEqualTo("INPUT");
        assertThat(manifest.ui().ioType()).isEqualTo("SCHEDULER");
    }

    @Test
    void rejectsRuntimeStateFieldsInCapabilityConfig() throws IOException {
        String body = """
                schemaVersion: "1.0"
                capabilitiesVersion: "1.0"
                image:
                  name: "processor"
                role: "processor"
                config:
                  - name: enabled
                    type: boolean
                actions: []
                panels: []
                """;
        Files.writeString(capabilitiesDir.resolve("processor.yaml"), body);

        CapabilityCatalogueService catalogue = new CapabilityCatalogueService(capabilitiesDir);

        assertThatThrownBy(catalogue::reload)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not declare runtime worker state field 'enabled'");
    }

    @Test
    void rejectsLegacyWorkerConfigPathsInCapabilityConfigAndWhenClauses() throws IOException {
        String body = """
                schemaVersion: "1.0"
                capabilitiesVersion: "1.0"
                image:
                  name: "generator"
                role: "generator"
                config:
                  - name: worker.message.path
                    type: string
                    when:
                      worker.message.bodyType: HTTP
                actions: []
                panels: []
                """;
        Files.writeString(capabilitiesDir.resolve("generator.yaml"), body);

        CapabilityCatalogueService catalogue = new CapabilityCatalogueService(capabilitiesDir);

        assertThatThrownBy(catalogue::reload)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must use direct config paths, not 'worker' or 'worker.*'");
    }

    @Test
    void rejectsExactLegacyConfigRootsInCapabilityConfigAndWhenClauses() throws IOException {
        String body = """
                schemaVersion: "1.0"
                capabilitiesVersion: "1.0"
                image:
                  name: "generator"
                role: "generator"
                config:
                  - name: worker
                    type: json
                    when:
                      pockethive: true
                  - name: pockethive
                    type: json
                actions: []
                panels: []
                """;
        Files.writeString(capabilitiesDir.resolve("generator.yaml"), body);

        CapabilityCatalogueService catalogue = new CapabilityCatalogueService(capabilitiesDir);

        assertThatThrownBy(catalogue::reload)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must use direct config paths, not 'worker' or 'worker.*'")
                .hasMessageContaining("must use direct config paths, not 'pockethive' or 'pockethive.*'");
    }

    @Test
    void acceptsManifestWithoutTagOrDigest() throws IOException {
        String body = """
                schemaVersion: "1.0"
                capabilitiesVersion: "1.0"
                image:
                  name: "processor"
                role: "processor"
                config: []
                actions: []
                panels: []
                """;
        Files.writeString(capabilitiesDir.resolve("processor.yaml"), body);

        CapabilityCatalogueService catalogue = new CapabilityCatalogueService(capabilitiesDir);

        catalogue.reload();

        assertThat(catalogue.findByImageName("processor")).isPresent();
        assertThat(catalogue.findByImageName("registry.example/pockethive/processor:0.15")).isPresent();
    }

    private void writeManifest(String imageName) throws IOException {
        String body = """
                schemaVersion: "1.0"
                capabilitiesVersion: "1.0"
                image:
                  name: "%s"
                role: "processor"
                config: []
                actions: []
                panels: []
                """.formatted(imageName);
        Files.writeString(capabilitiesDir.resolve(imageName + ".yaml"), body);
    }
}
