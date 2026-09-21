package io.pockethive.mcp.adapter.mcp;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.mcp.application.ToolCatalogue;
import io.pockethive.mcp.application.ToolDescriptor;
import io.pockethive.mcp.application.ToolExecutionException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolOutputValidatorTest {
    private final ToolCatalogue catalogue = ToolCatalogue.canonical();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsDeclaredObjectArrayAndTextRootShapes() {
        assertThatCode(() -> ToolOutputValidator.validate(tool("swarm_get"), Map.of("id", "sw1")))
            .doesNotThrowAnyException();
        assertThatCode(() -> ToolOutputValidator.validate(tool("swarm_list"), List.of()))
            .doesNotThrowAnyException();
        assertThatCode(() -> ToolOutputValidator.validate(tool("scenario_raw_read"), "scenario: yaml"))
            .doesNotThrowAnyException();
        assertThatCode(() -> ToolOutputValidator.validate(
            tool("swarm_get"), mapper.createObjectNode().put("id", "sw1")))
            .doesNotThrowAnyException();
        assertThatCode(() -> ToolOutputValidator.validate(
            tool("scenario_capabilities_get"), List.of(Map.of("role", "generator"))))
            .doesNotThrowAnyException();
        assertThatCode(() -> ToolOutputValidator.validate(
            tool("scenario_capabilities_get"), Map.of("role", "generator")))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsResultsThatContradictTheCanonicalDescriptor() {
        assertThatThrownBy(() -> ToolOutputValidator.validate(tool("swarm_list"), Map.of()))
            .isInstanceOf(ToolExecutionException.class)
            .extracting(exception -> ((ToolExecutionException) exception).code())
            .isEqualTo("TOOL_RESULT_SCHEMA_MISMATCH");
        assertThatThrownBy(() -> ToolOutputValidator.validate(tool("swarm_get"), null))
            .isInstanceOf(ToolExecutionException.class);
        assertThatThrownBy(() -> ToolOutputValidator.validate(tool("scenario_capabilities_get"), "invalid"))
            .isInstanceOf(ToolExecutionException.class);
    }

    private ToolDescriptor tool(String id) {
        return catalogue.requireTool(id);
    }
}
