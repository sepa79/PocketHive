package io.pockethive.worker.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.work.api.HistoryPolicy;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerRuntimeConfigurationTest {
    @Test void keepsParsedPolicyAndRawFieldTogetherWhenCandidateSourceChanges() {
        var source = new HashMap<String, Object>();
        source.put("historyPolicy", "LATEST_ONLY");
        var configuration = WorkerRuntimeConfiguration.parse(source);
        source.put("historyPolicy", "FULL");
        assertThat(configuration.historyPolicy()).isEqualTo(HistoryPolicy.LATEST_ONLY);
        assertThat(configuration.rawConfig()).containsEntry("historyPolicy", "LATEST_ONLY");
        assertThatThrownBy(() -> configuration.rawConfig().put("historyPolicy", "FULL"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void rejectsExplicitNullInsteadOfTreatingItAsAnAbsentPolicy() {
        var raw = new HashMap<String, Object>();
        raw.put("historyPolicy", null);
        assertThatThrownBy(() -> WorkerRuntimeConfiguration.parse(raw))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("historyPolicy");
    }

    @Test void doesNotCoerceEnumsOrOtherObjectsAtTheRawConfigurationBoundary() {
        assertThatThrownBy(() -> WorkerRuntimeConfiguration.parse(Map.of("historyPolicy", HistoryPolicy.FULL)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("historyPolicy");
    }
}
