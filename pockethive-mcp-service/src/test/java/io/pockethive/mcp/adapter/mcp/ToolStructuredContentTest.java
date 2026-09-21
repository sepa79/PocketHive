package io.pockethive.mcp.adapter.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.pockethive.mcp.application.ToolExecutionException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolStructuredContentTest {
    @Test
    void convertsJavaValuesToPortableJsonNativeStructuredContent() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        Object normalized = ToolStructuredContent.normalize(
            mapper,
            Map.of("createdAt", Instant.parse("2026-08-25T10:00:00Z")));

        assertThat(normalized).isEqualTo(Map.of("createdAt", "2026-08-25T10:00:00Z"));
    }

    @Test
    void reportsSerializationFailureWithoutLeakingMapperDetails() {
        ObjectMapper mapper = new ObjectMapper();
        Object selfReferential = new Object() {
            public Object getSelf() {
                return this;
            }
        };

        assertThatThrownBy(() -> ToolStructuredContent.normalize(mapper, selfReferential))
            .isInstanceOf(ToolExecutionException.class)
            .satisfies(exception -> {
                ToolExecutionException failure = (ToolExecutionException) exception;
                assertThat(failure.code()).isEqualTo("TOOL_RESULT_SERIALIZATION_FAILED");
                assertThat(failure.getMessage()).isEqualTo("Tool result could not be represented as JSON");
            });
    }
}
