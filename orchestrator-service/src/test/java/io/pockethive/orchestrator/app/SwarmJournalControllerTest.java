package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.filesystem.RuntimeFilesystemLayout;
import io.pockethive.orchestrator.auth.OrchestratorEndpointAuthorization;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.orchestrator.infra.FileSwarmJournalReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class SwarmJournalControllerTest {
    @TempDir Path root;

    @Test
    void fileQueryPreservesHttpStatusFilteringAndMissingRunBehavior() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        SwarmStore store = new SwarmStore();
        var layout = RuntimeFilesystemLayout.of(root.toString(), "/runtime");
        Path file = layout.swarmJournalFile("alpha", "run-1");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"severity\":\"INFO\"}\n{\"severity\":\"ERROR\"}\n");
        SwarmJournalController controller = controller(mapper, store,
            mock(OrchestratorEndpointAuthorization.class),
            new SwarmFileJournalQuery(new SwarmJournalRunSelector(store), new FileSwarmJournalReader(mapper, layout)));
        var result = controller.journal("alpha", "run-1", " error ");
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).hasSize(1);
        assertThat(result.getBody().getFirst()).containsEntry("severity", "ERROR");
        assertThat(controller.journal("alpha", "missing", null).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.journal("alpha", "../run-1", null).getStatusCode().value()).isEqualTo(500);
    }

    @Test
    void rejectsUnauthorizedRequestAndInvalidSeverityBeforeReading() {
        var auth = mock(OrchestratorEndpointAuthorization.class);
        var query = mock(SwarmFileJournalQuery.class);
        var controller = controller(new ObjectMapper(), new SwarmStore(), auth, query);
        doThrow(new IllegalStateException("denied")).when(auth).requireReadSwarm("denied");
        assertThatThrownBy(() -> controller.journal("denied", null, null)).hasMessage("denied");
        assertThat(controller.journal("alpha", null, "invalid").getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(query);
    }

    @Test
    void mapsEmptyAndFailedQueriesWithoutReplacingTheirResults() {
        var query = mock(SwarmFileJournalQuery.class);
        when(query.read("alpha", "empty", null)).thenReturn(java.util.List.of());
        when(query.read("alpha", "broken", null)).thenThrow(new IllegalStateException("read failed"));
        var controller = controller(new ObjectMapper(), new SwarmStore(),
            mock(OrchestratorEndpointAuthorization.class), query);
        assertThat(controller.journal("alpha", "empty", null).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.journal("alpha", "empty", null).getBody()).isEmpty();
        assertThat(controller.journal("alpha", "broken", null).getStatusCode().value()).isEqualTo(500);
    }

    private SwarmJournalController controller(ObjectMapper mapper, SwarmStore store,
        OrchestratorEndpointAuthorization auth, SwarmFileJournalQuery query) {
        var controller = new SwarmJournalController(mock(SwarmJournalPinning.class), auth, query, mock(SwarmStoredJournalQuery.class));
        ReflectionTestUtils.setField(controller, "journalSink", "file");
        return controller;
    }
}
