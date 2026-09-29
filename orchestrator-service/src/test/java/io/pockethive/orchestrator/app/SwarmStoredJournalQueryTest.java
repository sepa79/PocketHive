package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.pockethive.journal.api.JournalEventQueries;
import io.pockethive.journal.api.JournalRunQueries;
import io.pockethive.journal.api.JournalRunSummary;
import io.pockethive.journal.api.JournalPageResponse;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.swarm.model.NetworkMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SwarmStoredJournalQueryTest {
    private final SwarmStore store = new SwarmStore();
    private final JournalEventQueries events = mock(JournalEventQueries.class);
    private final JournalRunQueries runQueries = mock(JournalRunQueries.class);
    private final SwarmStoredJournalQuery query = new SwarmStoredJournalQuery(store, new SwarmJournalRunSelector(store), events, runQueries);
    private final JournalPageResponse empty = new JournalPageResponse(List.of(), null, false);

    @Test
    void distinguishesEmptyLiveReadsForRegisteredAndUnknownSwarms() {
        when(events.swarmPage("alpha", "run-1", null, null, null, null, 10)).thenReturn(empty);
        when(events.swarmTimeline("alpha", "run-1", null)).thenReturn(List.of());
        assertThat(query.page("alpha", "run-1", null, null, null, null, 10)).isNull();
        assertThat(query.timeline("alpha", "run-1", null)).isNull();
        register();
        assertThat(query.page("alpha", "run-1", null, null, null, null, 10)).isEqualTo(empty);
        assertThat(query.timeline("alpha", "run-1", null)).isEmpty();
    }

    @Test
    void pinnedEmptyArchiveRemainsAResultEvenForUnknownSwarmAndNeverFallsBackToLive() {
        UUID capture = UUID.randomUUID();
        when(events.pinnedSwarmCapture("alpha", "run-1")).thenReturn(capture);
        when(events.archivedSwarmPage(capture, "alpha", "run-1", "corr", "WARN", null, null, 10)).thenReturn(empty);
        when(events.archivedSwarmTimeline(capture, "alpha", "run-1", "WARN")).thenReturn(List.of());
        assertThat(query.page("alpha", "run-1", "corr", "WARN", null, null, 10)).isEqualTo(empty);
        assertThat(query.timeline("alpha", "run-1", "WARN")).isEmpty();
        verify(events, org.mockito.Mockito.times(2)).pinnedSwarmCapture("alpha", "run-1");
        verify(events).archivedSwarmPage(capture, "alpha", "run-1", "corr", "WARN", null, null, 10);
        verify(events).archivedSwarmTimeline(capture, "alpha", "run-1", "WARN");
        verifyNoMoreInteractions(events);
    }

    @Test
    void readsUnknownSwarmHistoryWithResolvedRunAndForwardsFilters() {
        when(events.latestSwarmRun("alpha")).thenReturn("run-1");
        var entries = List.<Map<String, Object>>of(Map.of("type", "event"));
        when(events.swarmTimeline("alpha", "run-1", "ERROR")).thenReturn(entries);
        assertThat(query.timeline(" alpha ", null, "ERROR")).isEqualTo(entries);
    }

    @Test
    void rejectsInvalidSwarmBeforeStorageAndStopsWhenNoRunIsAvailable() {
        assertThat(query.page("../outside", null, null, null, null, null, 10)).isNull();
        assertThat(query.timeline("../outside", null, null)).isNull();
        verifyNoInteractions(events);
        assertThat(query.timeline("alpha", null, null)).isNull();
        verify(events).latestSwarmRun("alpha");
        verifyNoMoreInteractions(events);
    }

    @Test
    void runListingPreservesUnknownEmptyAndRegisteredEmptyResults() {
        when(runQueries.swarmRuns("alpha")).thenReturn(List.of());
        assertThat(query.runs("alpha")).isNull();
        register();
        assertThat(query.runs(" alpha ")).isEmpty();
        var history = List.of(new JournalRunSummary("old", null, null, 2, true));
        when(runQueries.swarmRuns("removed")).thenReturn(history);
        assertThat(query.runs("removed")).isEqualTo(history);
        assertThat(query.runs("../outside")).isNull();
    }

    private void register() {
        store.register(new Swarm("alpha", "controller", "container", "run-1", NetworkMode.DIRECT));
    }
}
