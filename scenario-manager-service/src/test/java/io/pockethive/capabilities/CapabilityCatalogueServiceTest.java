package io.pockethive.capabilities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
    void bundledCapabilityConfigPublishesCanonicalAuthoringDefaults() throws Exception {
        CapabilityCatalogueService catalogue = new CapabilityCatalogueService(Path.of("capabilities"));
        catalogue.reload();

        assertThat(defaultValue(catalogue, "io-scheduler", "inputs.scheduler.maxMessages"))
                .isEqualTo(JsonNodeFactory.instance.numberNode(0));
        assertThat(defaultValue(catalogue, "request-builder", "passThroughOnMissingTemplate"))
                .isEqualTo(JsonNodeFactory.instance.booleanNode(false));
        assertThat(defaultValue(catalogue, "processor", "inputs.type"))
                .isEqualTo(JsonNodeFactory.instance.textNode("RABBITMQ"));
        assertThat(defaultValue(catalogue, "processor", "outputs.type"))
                .isEqualTo(JsonNodeFactory.instance.textNode("RABBITMQ"));
        assertThat(defaultValue(catalogue, "postprocessor", "forwardToOutput"))
                .isEqualTo(JsonNodeFactory.instance.booleanNode(false));
        assertThat(defaultValue(catalogue, "postprocessor", "txOutcomeSinkMode"))
                .isEqualTo(JsonNodeFactory.instance.textNode("NONE"));
        assertThat(defaultValue(catalogue, "postprocessor", "dropTxOutcomeWithoutCallId"))
                .isEqualTo(JsonNodeFactory.instance.booleanNode(true));
    }

    private JsonNode defaultValue(CapabilityCatalogueService catalogue, String role, String name) {
        return catalogue.findByImageName(role).orElseThrow().config().stream()
                .filter(entry -> name.equals(entry.name()))
                .findFirst()
                .orElseThrow()
                .defaultValue();
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
    void bundledRedisDatasetCapabilityExposesManualListNameRuntimeEdit() throws Exception {
        CapabilityCatalogueService catalogue = new CapabilityCatalogueService(Path.of("capabilities"));
        catalogue.reload();

        CapabilityManifest manifest = catalogue.findByImageName("io-redis-dataset").orElseThrow();
        CapabilityManifest.ConfigEntry listName = manifest.config().stream()
            .filter(entry -> "inputs.redis.listName".equals(entry.name()))
            .findFirst()
            .orElseThrow();

        assertThat(manifest.capabilitiesVersion()).isEqualTo("1.3");
        assertThat(listName.liveMutable()).isTrue();
        assertThat(listName.allowBlank()).isTrue();
    }

    @Test
    void bundledCapabilitiesDeclareLiveMutabilityForEveryConfigEntry() throws Exception {
        CapabilityCatalogueService catalogue = new CapabilityCatalogueService(Path.of("capabilities"));
        catalogue.reload();

        assertThat(catalogue.allManifests())
                .isNotEmpty()
                .allSatisfy(manifest -> assertThat(manifest.config())
                        .allSatisfy(entry -> assertThat(entry.liveMutable())
                                .as("%s:%s", manifest.role(), entry.name())
                                .isNotNull()));
    }

    @Test
    void rejectsMissingCapabilityConfigLiveMutability() throws IOException {
        String body = """
                schemaVersion: "1.0"
                capabilitiesVersion: "1.0"
                image:
                  name: "processor"
                role: "processor"
                config:
                  - name: threadCount
                    type: integer
                actions: []
                panels: []
                """;
        Files.writeString(capabilitiesDir.resolve("processor.yaml"), body);

        CapabilityCatalogueService catalogue = new CapabilityCatalogueService(capabilitiesDir);

        assertThatThrownBy(catalogue::reload)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("config[].liveMutable for 'threadCount' is required");
    }

    @Test
    void rejectsUnsafeIoCapabilityConfigMarkedLiveMutable() throws IOException {
        String body = """
                schemaVersion: "1.0"
                capabilitiesVersion: "1.0"
                image:
                  name: "io-redis-output"
                role: "io-redis-output"
                ui:
                  ioScope: OUTPUT
                  ioType: REDIS
                config:
                  - name: outputs.redis.host
                    type: string
                    liveMutable: true
                actions: []
                panels: []
                """;
        Files.writeString(capabilitiesDir.resolve("io-redis-output.yaml"), body);

        CapabilityCatalogueService catalogue = new CapabilityCatalogueService(capabilitiesDir);

        assertThatThrownBy(catalogue::reload)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("config[].liveMutable for unsafe IO field 'outputs.redis.host' must be false");
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
    void rejectsUnsupportedCapabilityConfigTypes() throws IOException {
        String body = """
                schemaVersion: "1.0"
                capabilitiesVersion: "1.0"
                image:
                  name: "processor"
                role: "processor"
                config:
                  - name: threadCount
                    type: int
                  - name: upperCase
                    type: INTEGER
                  - name: padded
                    type: " integer "
                actions: []
                panels: []
                """;
        Files.writeString(capabilitiesDir.resolve("processor.yaml"), body);

        CapabilityCatalogueService catalogue = new CapabilityCatalogueService(capabilitiesDir);

        assertThatThrownBy(catalogue::reload)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("config[].type for 'threadCount' is unsupported value 'int'")
                .hasMessageContaining("config[].type for 'upperCase' is unsupported value 'INTEGER'")
                .hasMessageContaining("config[].type for 'padded' is unsupported value ' integer '")
                .hasMessageContaining("expected one of: string, boolean, number, integer, json");
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
