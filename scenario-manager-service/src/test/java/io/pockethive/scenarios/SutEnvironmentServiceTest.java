package io.pockethive.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.swarm.model.SutEndpoint;
import java.io.IOException;
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

        SutEndpoint endpoint = service.find("wiremock-local").getEndpoints().get("default");
        assertThat(endpoint.kind()).isEqualTo("HTTP");
        assertThat(endpoint.baseUrl()).isEqualTo("http://wiremock:8080");
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
