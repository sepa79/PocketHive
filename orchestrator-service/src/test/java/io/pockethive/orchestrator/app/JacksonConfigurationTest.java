package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonConfigurationTest {
    @Test
    void providesObjectMapper() {
        ObjectMapper mapper = new JacksonConfiguration().objectMapper();
        assertThat(mapper).isNotNull();
    }

    @Test
    void serializesInstantsAsIsoStrings() throws Exception {
        ObjectMapper mapper = new JacksonConfiguration().objectMapper();
        String json = mapper.writeValueAsString(java.time.Instant.parse("2025-09-16T09:07:45.834Z"));
        assertThat(json).isEqualTo("\"2025-09-16T09:07:45.834Z\"");
    }
}
