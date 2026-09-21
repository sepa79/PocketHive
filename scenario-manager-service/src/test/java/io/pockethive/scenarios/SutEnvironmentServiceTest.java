package io.pockethive.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.swarm.model.SutEndpoint;
import io.pockethive.swarm.model.SutEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SutEnvironmentServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsCanonicalSharedSutEndpointContract() throws IOException {
        SutEnvironmentService service = service();

        service.updateFromRaw("""
            - id: wiremock-local
              name: WireMock local
              type: sandbox
              endpoints:
                default:
                  kind: " HTTP "
                  baseUrl: " http://wiremock:8080 "
            """);

        SutEnvironment environment = service.find("wiremock-local");
        SutEndpoint endpoint = environment.endpoints().get("default");
        assertThat(environment.id()).isEqualTo("wiremock-local");
        assertThat(endpoint.kind()).isEqualTo("HTTP");
        assertThat(endpoint.baseUrl()).isEqualTo("http://wiremock:8080");
    }

    @Test
    void rejectsFormerScenarioManagerUiProjection() {
        SutEnvironmentService service = service();

        assertThatThrownBy(() -> service.updateFromRaw("""
            - id: wiremock-local
              name: WireMock local
              endpoints: {}
              ui:
                panelId: wiremock
            """))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Unrecognized field \"ui\"");
    }

    @Test
    void rejectsMissingEndpointsAtCanonicalBoundary() {
        SutEnvironmentService service = service();

        assertThatThrownBy(() -> service.updateFromRaw("""
            - id: wiremock-local
              name: WireMock local
            """))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("SUT environment endpoints must be provided");
    }

    @Test
    void rejectsDuplicateEnvironmentIdentity() {
        SutEnvironmentService service = service();

        assertThatThrownBy(() -> service.updateFromRaw("""
            - id: duplicate
              name: First
              endpoints: {}
            - id: duplicate
              name: Second
              endpoints: {}
            """))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Duplicate SUT environment id 'duplicate'");
    }

    @Test
    void directoryRegistryFailsWhenAnySourceIsInvalid() throws IOException {
        Path registry = tempDir.resolve("sut");
        Files.createDirectories(registry);
        Files.writeString(registry.resolve("valid.yaml"), """
            - id: valid
              name: Valid
              endpoints: {}
            """);
        Files.writeString(registry.resolve("invalid.yaml"), """
            - id: invalid
              name: Invalid
              endpoints:
                default:
                  kind: HTTP
                  baseUrl: " "
            """);
        SutEnvironmentService service = new SutEnvironmentService(registry.toString());

        assertThatThrownBy(service::init)
            .isInstanceOf(IOException.class)
            .hasMessageContaining("SUT endpoint baseUrl must not be blank");
    }

    @Test
    void rejectsNestedEndpointIdThroughCanonicalSharedContract() {
        SutEnvironmentService service = service();

        assertThatThrownBy(() -> service.updateFromRaw("""
            - id: wiremock-local
              name: WireMock local
              endpoints:
                default:
                  id: default
                  kind: HTTP
                  baseUrl: http://wiremock:8080
            """))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Unrecognized field \"id\"");
    }

    @Test
    void rejectsBlankEndpointBaseUrlThroughCanonicalSharedContract() {
        SutEnvironmentService service = service();

        assertThatThrownBy(() -> service.updateFromRaw("""
            - id: wiremock-local
              name: WireMock local
              endpoints:
                default:
                  kind: HTTP
                  baseUrl: " "
            """))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("SUT endpoint baseUrl must not be blank");
    }

    private SutEnvironmentService service() {
        return new SutEnvironmentService(tempDir.resolve("sut-environments.yaml").toString());
    }
}
