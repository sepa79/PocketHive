package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.pockethive.journal.api.*;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.swarm.model.NetworkMode;
import org.junit.jupiter.api.Test;

class SwarmJournalPinningTest {
    @Test
    void pinsSelectedActiveRunAndReportsMissingWhenNoRunExists() {
        var store = new SwarmStore();
        var captures = mock(JournalCaptures.class);
        var events = mock(JournalEventQueries.class);
        var query = new SwarmStoredJournalQuery(store, new SwarmJournalRunSelector(store), events, mock(JournalRunQueries.class));
        var pinning = new SwarmJournalPinning(query, captures);
        assertThat(pinning.pin("alpha", null)).isNull();
        verifyNoInteractions(captures);
        store.register(new Swarm("alpha", "controller", "container", "active", NetworkMode.DIRECT));
        var response = new PinRunResponse("id", "alpha", "active", "SLIM", 3, 5);
        when(captures.pin("alpha", "active", null)).thenReturn(response);
        assertThat(pinning.pin("alpha", null)).isEqualTo(response);
        var explicit = new PinRunRequest(" other ", "FULL", "test");
        var explicitResponse = new PinRunResponse("other-id", "alpha", "other", "FULL", 0, 0);
        when(captures.pin("alpha", "other", explicit)).thenReturn(explicitResponse);
        assertThat(pinning.pin("alpha", explicit)).isEqualTo(explicitResponse);
    }
}
