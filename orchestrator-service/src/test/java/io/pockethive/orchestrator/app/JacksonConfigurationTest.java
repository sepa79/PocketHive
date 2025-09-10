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
}
