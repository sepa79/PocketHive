package io.pockethive.orchestrator.planner;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.pockethive.journal.api.JournalRunMetadata;
import io.pockethive.journal.api.JournalRunQueries;
import io.pockethive.journal.api.PlannerRunLink;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PlannerRunServiceTest {
    @Test
    void currentRunUsesTheRegisteredIdentityWithoutGuessingFromStatusOrJournalOrder() {
        var store = mock(io.pockethive.orchestrator.domain.SwarmStore.class);
        var queries = mock(JournalRunQueries.class);
        var swarm = mock(io.pockethive.orchestrator.domain.Swarm.class);
        when(store.find("reused-name")).thenReturn(java.util.Optional.of(swarm));
        when(swarm.getRunId()).thenReturn("registered-run");
        var service = new PlannerRunService(mock(PocketPlannerClient.class), mock(JournalRunMetadata.class), queries, store, "postgres");
        assertThatThrownBy(() -> service.current("reused-name")).isInstanceOf(ResponseStatusException.class);
        verify(queries).metadataSummary("reused-name", "registered-run");
    }

    @Test
    void onlyValidatedSelectionsReachTheSingleMetadataWriter() {
        var planner = mock(PocketPlannerClient.class);
        var metadata = mock(JournalRunMetadata.class);
        var service = new PlannerRunService(planner, metadata, mock(JournalRunQueries.class), mock(io.pockethive.orchestrator.domain.SwarmStore.class), "postgres");
        var selection = new PlannerSelection("qa", 0, "p", "s", "a");
        var link = new PlannerRunLink("pp:qa:p", "pp.activity:" + "A".repeat(43));
        when(planner.resolve(selection, "Bearer user")).thenReturn(link);
        assertThatThrownBy(() -> service.assign("swarm", "old-run", selection, "Bearer user"))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> assertThat(exception.getStatusCode().value()).isEqualTo(404));
        verify(metadata).linkPlanner("swarm", "old-run", link);
        when(metadata.linkPlanner("swarm", "old-run", link)).thenThrow(new IllegalStateException("Already linked"));
        assertThatThrownBy(() -> service.assign("swarm", "old-run", selection, "Bearer user"))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void unsupportedJournalAndInvalidSelectionNeverWrite() {
        var planner = mock(PocketPlannerClient.class);
        var metadata = mock(JournalRunMetadata.class);
        var selection = new PlannerSelection("qa", 0, "p", "s", "a");
        var disabled = new PlannerRunService(planner, metadata, mock(JournalRunQueries.class), mock(io.pockethive.orchestrator.domain.SwarmStore.class), "file");
        assertThatThrownBy(() -> disabled.assign("s", "r", selection, "Bearer user")).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(planner, metadata);
        when(planner.resolve(selection, "Bearer user")).thenThrow(new IllegalArgumentException("Invalid selection"));
        var enabled = new PlannerRunService(planner, metadata, mock(JournalRunQueries.class), mock(io.pockethive.orchestrator.domain.SwarmStore.class), "postgres");
        assertThatThrownBy(() -> enabled.assign("s", "r", selection, "Bearer user")).hasMessage("Invalid selection");
        verifyNoInteractions(metadata);
    }
}
