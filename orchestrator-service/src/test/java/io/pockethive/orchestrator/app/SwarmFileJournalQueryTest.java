package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.swarm.model.NetworkMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SwarmFileJournalQueryTest {
    private final SwarmStore store = new SwarmStore();
    private final SwarmJournalFiles files = mock(SwarmJournalFiles.class);
    private final SwarmFileJournalQuery query = new SwarmFileJournalQuery(store, files);
    private final List<Map<String, Object>> entries = List.of(Map.of("type", "observed"));

    @Test
    void explicitRunWinsOverActiveRunAndMissingFileDoesNotSelectAnotherRun() {
        register("active");
        when(files.read("alpha", "requested", "ERROR")).thenReturn(entries);
        when(files.read("alpha", "missing", null)).thenReturn(null);
        assertThat(query.read(" alpha ", " requested ", "ERROR")).isEqualTo(entries);
        assertThat(query.read("alpha", "missing", null)).isNull();
        verify(files).read("alpha", "requested", "ERROR");
        verify(files).read("alpha", "missing", null);
        verifyNoMoreInteractions(files);
    }

    @Test
    void omittedOrBlankRunUsesActiveRunWithoutConsultingDiskDiscovery() {
        register("active");
        when(files.read("alpha", "active", null)).thenReturn(entries);
        assertThat(query.read("alpha", null, null)).isEqualTo(entries);
        assertThat(query.read("alpha", " ", null)).isEqualTo(entries);
        verify(files, times(2)).read("alpha", "active", null);
        verifyNoMoreInteractions(files);
    }

    @Test
    void absentRegistryRunUsesObservedDirectory() {
        when(files.latestRunDirectory("alpha")).thenReturn("observed");
        when(files.read("alpha", "observed", null)).thenReturn(entries);
        assertThat(query.read("alpha", null, null)).isEqualTo(entries);
        register(" ");
        assertThat(query.read("alpha", null, null)).isEqualTo(entries);
    }

    @Test
    void missingDirectoryAndInvalidSwarmReturnAbsence() {
        assertThat(query.read("../outside", null, null)).isNull();
        verifyNoInteractions(files);
        assertThat(query.read("alpha", null, null)).isNull();
        verify(files).latestRunDirectory("alpha");
        verifyNoMoreInteractions(files);
    }

    private void register(String run) {
        store.register(new Swarm("alpha", "controller", "container", run, NetworkMode.DIRECT));
    }
}
