package io.pockethive.swarm.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SutEndpointTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void usesOnlyTheEnvironmentMapKeyAsEndpointIdentity() throws Exception {
        SutEnvironment environment = mapper.readValue("""
            {
              "id": "wiremock-local",
              "name": "WireMock local",
              "endpoints": {
                "default": {
                  "kind": "HTTP",
                  "baseUrl": "http://wiremock:8080"
                }
              }
            }
            """, SutEnvironment.class);

        assertEquals("HTTP", environment.endpoints().get("default").kind());
        assertFalse(mapper.writeValueAsString(environment).contains("\"id\":\"default\""));
    }

    @Test
    void rejectsNestedEndpointId() {
        assertThrows(JsonProcessingException.class, () -> mapper.readValue("""
            {
              "kind": "HTTP",
              "baseUrl": "http://wiremock:8080",
              "id": "default"
            }
            """, SutEndpoint.class));
    }

    @Test
    void rejectsBlankEndpointMapKey() {
        SutEndpoint endpoint = new SutEndpoint("HTTP", "http://wiremock:8080", null);
        assertThrows(IllegalArgumentException.class,
            () -> new SutEnvironment("wiremock-local", "WireMock local", "sandbox", Map.of(" ", endpoint)));
    }

    @Test
    void rejectsUnknownEnvironmentField() {
        assertThrows(JsonProcessingException.class, () -> mapper.readValue("""
            {
              "id": "wiremock-local",
              "name": "WireMock local",
              "endpoints": {},
              "legacy": true
            }
            """, SutEnvironment.class));
    }

    @Test
    void rejectsBlankOptionalUpstreamBaseUrlWhenProvided() {
        assertThrows(IllegalArgumentException.class,
            () -> new SutEndpoint("HTTP", "http://wiremock:8080", " "));
    }

    @Test
    void normalizesEnvironmentTextAtCanonicalBoundary() {
        SutEnvironment environment = new SutEnvironment(
            " wiremock-local ", " WireMock local ", " sandbox ", Map.of());

        assertEquals("wiremock-local", environment.id());
        assertEquals("WireMock local", environment.name());
        assertEquals("sandbox", environment.type());
    }

    @Test
    void rejectsMissingEndpoints() {
        assertThrows(IllegalArgumentException.class,
            () -> new SutEnvironment("wiremock-local", "WireMock local", "sandbox", null));
    }
}
