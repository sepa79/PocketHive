package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.swarm.model.NetworkMode;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class SwarmJournalRunSelectorTest {
    private final SwarmStore store = new SwarmStore();
    private final SwarmJournalRunSelector selector = new SwarmJournalRunSelector(store);

    @Test
    void explicitRunWinsWithoutConsultingStorage() {
        register("active");
        Supplier<String> observation = observation();
        assertThat(selector.resolve("alpha", " requested ", observation)).isEqualTo("requested");
        verifyNoInteractions(observation);
    }

    @Test
    void omittedAndBlankRunUseRegistryWithoutConsultingStorage() {
        register("active");
        Supplier<String> observation = observation();
        assertThat(selector.resolve("alpha", null, observation)).isEqualTo("active");
        assertThat(selector.resolve("alpha", " ", observation)).isEqualTo("active");
        verifyNoInteractions(observation);
    }

    @Test
    void missingAndBlankActiveRunConsultSelectedStorage() {
        Supplier<String> observation = observation();
        when(observation.get()).thenReturn("observed");
        assertThat(selector.resolve("alpha", null, observation)).isEqualTo("observed");
        register(" ");
        assertThat(selector.resolve("alpha", null, observation)).isEqualTo("observed");
        when(observation.get()).thenReturn(null);
        assertThat(selector.resolve("alpha", null, observation)).isNull();
    }

    private void register(String run) {
        store.register(new Swarm("alpha", "controller", "container", run, NetworkMode.DIRECT));
    }

    @SuppressWarnings("unchecked")
    private Supplier<String> observation() {
        return mock(Supplier.class);
    }
}
