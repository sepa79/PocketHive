package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.pockethive.journal.api.*;
import io.pockethive.orchestrator.auth.OrchestratorEndpointAuthorization;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JournalWriteHttpTest {
    @Test
    void metadataMapsAbsenceConflictAndStorageFailureAndAuthorizesBeforeWrite() throws Exception {
        var auth = mock(OrchestratorEndpointAuthorization.class);
        var metadata = mock(JournalRunMetadata.class);
        var controller = new JournalController(auth, mock(JournalEventQueries.class), mock(JournalRunQueries.class), metadata);
        ReflectionTestUtils.setField(controller, "journalSink", "postgres");
        assertThat(controller.updateRunMetadata("missing", null).getStatusCode().value()).isEqualTo(404);
        when(metadata.update("conflict", null)).thenThrow(new IllegalStateException("ambiguous"));
        assertThat(controller.updateRunMetadata("conflict", null).getStatusCode().value()).isEqualTo(409);
        when(metadata.update("broken", null)).thenThrow(new RuntimeException("storage"));
        assertThat(controller.updateRunMetadata("broken", null).getStatusCode().value()).isEqualTo(500);
        clearInvocations(metadata);
        doThrow(new IllegalStateException("forbidden")).when(auth).requireManageDeployment();
        assertThatThrownBy(() -> controller.updateRunMetadata("run", null)).hasMessage("forbidden");
        verifyNoInteractions(metadata);
    }

    @Test
    void pinningMapsAbsenceConflictAndStorageFailureAndKeepsConflictBody() {
        var auth = mock(OrchestratorEndpointAuthorization.class);
        var pinning = mock(SwarmJournalPinning.class);
        var controller = new SwarmJournalController(pinning, auth, mock(SwarmFileJournalQuery.class), mock(SwarmStoredJournalQuery.class));
        ReflectionTestUtils.setField(controller, "journalSink", "postgres");
        assertThat(controller.pinSwarmJournalRun("alpha", null).getStatusCode().value()).isEqualTo(404);
        var conflict = new PinRunResponse(null, "alpha", "run", "FULL", 0, 0);
        when(pinning.pin("alpha", null)).thenThrow(new JournalCaptureConflictException(conflict));
        var response = controller.pinSwarmJournalRun("alpha", null);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isEqualTo(conflict);
        doThrow(new RuntimeException("storage")).when(pinning).pin("alpha", null);
        assertThat(controller.pinSwarmJournalRun("alpha", null).getStatusCode().value()).isEqualTo(500);
        clearInvocations(pinning);
        doThrow(new IllegalStateException("forbidden")).when(auth).requireManageSwarm("alpha");
        assertThatThrownBy(() -> controller.pinSwarmJournalRun("alpha", null)).hasMessage("forbidden");
        verifyNoInteractions(pinning);
    }
}
