package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.MessageTypeMapping;
import io.pockethive.tcpmock.model.MockState;
import io.pockethive.tcpmock.util.AdvancedRequestMatcher;
import io.pockethive.tcpmock.util.PatternCache;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MappingExecutorTest {
    private final MessageTypeRegistry registry = TestMappingCatalogues.fresh();
    private final RequestVerificationService verification = new RequestVerificationService();

    private MappingExecutor executor(StateManager state) {
        return new MappingExecutor(registry, new PatternCache(), new AdvancedRequestMatcher(),
            state, new EnhancedTemplateEngine(), verification);
    }

    @Test
    void combinesPriorityEnabledAndAdvancedMatchingAndPreservesDeliverySettings() {
        var selected = new MessageTypeMapping("selected", "^HELLO.*", "reply:{{message}}", "test");
        selected.setPriority(30);
        selected.setAdvancedMatching(Map.of("contains", "Alice"));
        selected.setResponseDelimiter("\r\n");
        selected.setFixedDelayMs(123);
        registry.addMapping(selected);
        var disabled = new MessageTypeMapping("disabled", ".*", "wrong", "test");
        disabled.setPriority(100);
        disabled.setEnabled(false);
        registry.addMapping(disabled);
        var executor = executor(null);
        assertEquals("OK", executor.processMessage("HELLO Bob").getResponse());
        var result = executor.processMessage("HELLO Alice");
        assertEquals("reply:HELLO Alice", result.getResponse());
        assertEquals("\r\n", result.getDelimiter());
        assertEquals(123, result.getDelayMs());
        assertEquals(1, selected.getMatchCount());
        assertEquals(0, disabled.getMatchCount());
    }

    @Test
    void recordsUnmatchedRequestsAfterCatalogueIsCleared() {
        registry.getAllMappings().forEach(m -> registry.removeMapping(m.getId()));
        verification.addExpectation("HELLO", "exactly", 1);
        var result = executor(null).processMessage("HELLO");
        assertEquals("UNKNOWN_MESSAGE_TYPE", result.getResponse());
        assertEquals("\n", result.getDelimiter());
        assertEquals(1, verification.getVerificationResults().getFirst().get("actual"));
    }

    @Test
    void scenarioGuardPrecedesRenderingAndTransitionFollowsIt() {
        var state = new TestState();
        var mapping = new MessageTypeMapping("scenario", "HELLO", "{{message}}", "test");
        mapping.setPriority(100);
        mapping.setScenarioName("flow");
        mapping.setRequiredScenarioState("Ready");
        mapping.setNewScenarioState("Done");
        registry.addMapping(mapping);
        var executor = executor(state);
        assertEquals("OK", executor.processMessage("HELLO").getResponse());
        assertEquals(0, mapping.getMatchCount());
        state.current = "Ready";
        assertEquals("HELLO", executor.processMessage("HELLO").getResponse());
        assertEquals("Done", state.current);
        assertEquals(1, mapping.getMatchCount());
    }

    @Test
    void templateFailureIsRecordedButDoesNotAdvanceScenario() {
        var state = new TestState();
        var mapping = new MessageTypeMapping("bad", "HELLO", "fault:NOT_A_FAULT", "test");
        mapping.setPriority(100);
        mapping.setScenarioName("flow");
        mapping.setNewScenarioState("Done");
        registry.addMapping(mapping);
        verification.addExpectation("HELLO", "exactly", 1);
        assertThrows(IllegalArgumentException.class, () -> executor(state).processMessage("HELLO"));
        assertEquals("Started", state.current);
        assertEquals(1, mapping.getMatchCount());
        assertEquals(1, verification.getVerificationResults().getFirst().get("actual"));
    }

    @Test
    void preservesFaultAndProxyMetadata() {
        var mapping = new MessageTypeMapping("special", "HELLO", "fault:EMPTY_RESPONSE", "test");
        mapping.setPriority(100);
        mapping.setResponseDelimiter("");
        registry.addMapping(mapping);
        var executor = executor(null);
        assertTrue(executor.processMessage("HELLO").hasFault());
        mapping.setResponseTemplate("proxy:example:1234");
        var result = executor.processMessage("HELLO");
        assertEquals("example:1234", result.getProxyTarget());
        assertEquals("", result.getDelimiter());
    }

    private static class TestState extends StateManager {
        String current = "Started";
        TestState() { super(null); }
        @Override public MockState getOrCreateScenarioState(String name) { return new MockState(name, current); }
        @Override public boolean isInState(String name, String expected) { return expected.equals(current); }
        @Override public void updateScenarioState(String name, String next) { current = next; }
    }
}
