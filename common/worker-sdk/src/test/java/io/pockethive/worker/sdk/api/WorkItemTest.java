package io.pockethive.worker.sdk.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class WorkItemTest {

    @Test
    void singleStepViewForLegacyItems() {
        WorkItem item = WorkItem.text("payload")
            .header("h", "v")
            .build();

        List<WorkStep> steps = steps(item);
        assertThat(steps).hasSize(1);
        WorkStep step = steps.get(0);
        assertThat(step.index()).isEqualTo(0);
        assertThat(step.payload()).isEqualTo("payload");
        assertThat(step.headers()).containsEntry("h", "v");
        assertThat(item.previousPayload()).isEmpty();
    }

    @Test
    void addStepPayloadAppendsAndPreservesPrevious() {
        WorkItem base = WorkItem.text("first")
            .header("h", "v")
            .build();

        WorkItem updated = base.addStepPayload("second");

        assertThat(updated.asString()).isEqualTo("second");
        assertThat(updated.previousPayload()).contains("first");

        List<WorkStep> steps = steps(updated);
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).payload()).isEqualTo("first");
        assertThat(steps.get(0).index()).isEqualTo(0);
        assertThat(steps.get(0).headers()).containsEntry("h", "v");
        assertThat(steps.get(1).payload()).isEqualTo("second");
        assertThat(steps.get(1).index()).isEqualTo(1);
        assertThat(steps.get(1).headers()).containsEntry("h", "v");

        // original item remains unchanged
        assertThat(base.asString()).isEqualTo("first");
        assertThat(steps(base)).hasSize(1);
    }

    @Test
    void addStepMergesHeadersIntoNewStep() {
        WorkItem base = WorkItem.text("one")
            .header("a", "1")
            .build();

        WorkItem updated = base.addStep("two", Map.of("b", "2"));

        assertThat(updated.asString()).isEqualTo("two");
        assertThat(updated.headers())
            .containsEntry("a", "1")
            .containsEntry("b", "2");

        List<WorkStep> steps = steps(updated);
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).payload()).isEqualTo("one");
        assertThat(steps.get(0).headers()).containsEntry("a", "1");
        assertThat(steps.get(1).payload()).isEqualTo("two");
        assertThat(steps.get(1).headers())
            .containsEntry("a", "1")
            .containsEntry("b", "2");
    }

    @Test
    void addStepHeaderUpdatesCurrentStepOnly() {
        WorkItem base = WorkItem.text("one").build();
        WorkItem withSecond = base.addStepPayload("two");

        WorkItem updated = withSecond.addStepHeader("x", "1");

        List<WorkStep> steps = steps(updated);
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).headers()).doesNotContainKey("x");
        assertThat(steps.get(1).headers()).containsEntry("x", "1");
    }

    @Test
    void clearHistoryKeepsLatestStepOnly() {
        WorkItem item = WorkItem.text("first")
            .build()
            .addStepPayload("second")
            .addStepPayload("third");

        WorkItem cleared = item.clearHistory();

        assertThat(cleared.asString()).isEqualTo("third");
        assertThat(cleared.previousPayload()).isEmpty();

        List<WorkStep> steps = steps(cleared);
        assertThat(steps).hasSize(1);
        WorkStep step = steps.get(0);
        assertThat(step.index()).isEqualTo(0);
        assertThat(step.payload()).isEqualTo("third");
    }

    @Test
    void applyHistoryPolicyControlsRecordedSteps() {
        WorkItem item = WorkItem.text("first")
            .build()
            .addStepPayload("second");

        WorkItem full = item.applyHistoryPolicy(HistoryPolicy.FULL);
        assertThat(steps(full)).hasSize(2);

        WorkItem latestOnly = item.applyHistoryPolicy(HistoryPolicy.LATEST_ONLY);
        assertThat(steps(latestOnly)).hasSize(1);
        assertThat(latestOnly.asString()).isEqualTo("second");

        WorkItem disabled = item.applyHistoryPolicy(HistoryPolicy.DISABLED);
        // history disabled: a single baseline step is retained so callers still
        // see the latest payload without previous snapshots.
        assertThat(steps(disabled)).hasSize(1);
        assertThat(disabled.asString()).isEqualTo("second");
    }

    private static List<WorkStep> steps(WorkItem item) {
        return StreamSupport.stream(item.steps().spliterator(), false).toList();
    }
}
