package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.controller.WireMockCompatController;
import io.pockethive.tcpmock.model.TcpRequest;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompatibilityCommandsTest {
    // Avoid ScenarioManager's constructor IO; persistence is outside this command boundary.
    private final ScenarioManager scenarios = mock(ScenarioManager.class);
    private final RequestStore requests = new RequestStore();
    private final CompatibilityCommands commands = new CompatibilityCommands(requests, scenarios);
    private final WireMockCompatController controller = new WireMockCompatController(null, commands);

    @BeforeEach
    void populateBothJournals() {
        var request = new TcpRequest("request-1", "client", "hello", Map.of(), "MAPPING", Instant.EPOCH, "OK");
        requests.addRequest(request);
        requests.addUnmatchedRequest(request);
    }

    @Test
    void resetClearsBothJournalsBeforeResettingScenarios() {
        doAnswer(invocation -> {
            assertTrue(requests.getAllRequests().isEmpty());
            assertTrue(requests.getUnmatchedRequests().isEmpty());
            return null;
        }).when(scenarios).resetAllScenarios();

        assertEquals(Map.of("status", "Reset completed"), controller.reset());
        verify(scenarios).resetAllScenarios();
        assertTrue(requests.getAllRequests().isEmpty());
        assertTrue(requests.getUnmatchedRequests().isEmpty());
    }

    @Test
    void scenarioResetFailurePropagatesAfterBothJournalsWereCleared() {
        var failure = new IllegalStateException("scenario reset failed");
        doThrow(failure).when(scenarios).resetAllScenarios();

        assertSame(failure, assertThrows(IllegalStateException.class, controller::reset));
        assertTrue(requests.getAllRequests().isEmpty());
        assertTrue(requests.getUnmatchedRequests().isEmpty());
    }

    @Test
    void requestClearFailurePreventsScenarioReset() {
        var failingRequests = mock(RequestStore.class);
        var failure = new IllegalStateException("journal clear failed");
        doThrow(failure).when(failingRequests).clearRequests();
        var failingCommands = new CompatibilityCommands(failingRequests, scenarios);

        assertSame(failure, assertThrows(IllegalStateException.class, failingCommands::reset));
        verifyNoInteractions(scenarios);
    }

    @Test
    void updatePassesExactScenarioAndStateWithoutTouchingJournals() {
        assertEquals(Map.of("status", "updated", "scenario", "flow", "state", "Ready"),
            controller.setScenarioState("flow", Map.of("state", "Ready")));
        verify(scenarios).setScenarioState("flow", "Ready");
        assertJournalsRetained();
    }

    @Test
    void deletePassesExactScenarioWithoutTouchingJournals() {
        assertEquals(Map.of("status", "deleted", "scenario", "flow"), controller.deleteScenario("flow"));
        verify(scenarios).removeScenario("flow");
        assertJournalsRetained();
    }

    @Test
    void singleResetPreservesNullStateAndPropagatesOwnerRejection() {
        var failure = new NullPointerException("null scenario state");
        doThrow(failure).when(scenarios).setScenarioState("flow", null);

        assertSame(failure, assertThrows(NullPointerException.class, () -> controller.resetScenario("flow")));
        verify(scenarios).setScenarioState("flow", null);
        assertJournalsRetained();
    }

    @Test
    void missingUpdateStateIsPassedToOwnerWithoutInventingADefault() {
        var failure = new NullPointerException("null scenario state");
        doThrow(failure).when(scenarios).setScenarioState("flow", null);

        assertSame(failure, assertThrows(NullPointerException.class,
            () -> controller.setScenarioState("flow", Map.of())));
        verify(scenarios).setScenarioState("flow", null);
        assertJournalsRetained();
    }

    @Test
    void updateFailureDoesNotBecomeASuccessResponse() {
        var failure = new IllegalStateException("update failed");
        doThrow(failure).when(scenarios).setScenarioState("flow", "Ready");

        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> controller.setScenarioState("flow", Map.of("state", "Ready"))));
        assertJournalsRetained();
    }

    @Test
    void deleteFailureDoesNotBecomeASuccessResponse() {
        var failure = new IllegalStateException("delete failed");
        doThrow(failure).when(scenarios).removeScenario("flow");

        assertSame(failure, assertThrows(IllegalStateException.class, () -> controller.deleteScenario("flow")));
        assertJournalsRetained();
    }

    private void assertJournalsRetained() {
        assertEquals(1, requests.getAllRequests().size());
        assertEquals("request-1", requests.getAllRequests().getFirst().getId());
        assertEquals(1, requests.getUnmatchedRequests().size());
        assertEquals("request-1", requests.getUnmatchedRequests().getFirst().getId());
    }
}
