package io.pockethive.worker.sdk.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class WorkItemTest {

    @Test
    void singleStepViewForLegacyItems() {
        WorkerInfo info = testInfo();
        WorkItem item = WorkItem.text(info, "payload")
            .header("h", "v")
            .build();

        List<WorkStep> steps = steps(item);
        assertThat(steps).hasSize(1);
        WorkStep step = steps.get(0);
        assertThat(step.index()).isEqualTo(0);
        assertThat(step.payload()).isEqualTo("payload");
        assertThat(step.headers())
            .containsEntry(WorkItem.STEP_SERVICE_HEADER, info.role())
            .containsEntry(WorkItem.STEP_INSTANCE_HEADER, info.instanceId());
        assertThat(step.headers()).doesNotContainKey("h");
        assertThat(item.headers()).containsEntry("h", "v");
        assertThat(item.previousPayload()).isEmpty();
    }

    @Test
    void addStepPayloadAppendsAndPreservesPrevious() {
        WorkerInfo info = testInfo();
        WorkItem base = WorkItem.text(info, "first")
            .header("h", "v")
            .build();

        WorkItem updated = base.addStepPayload(info, "second");

        assertThat(updated.asString()).isEqualTo("second");
        assertThat(updated.previousPayload()).contains("first");

        List<WorkStep> steps = steps(updated);
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).payload()).isEqualTo("first");
        assertThat(steps.get(0).index()).isEqualTo(0);
        assertThat(steps.get(0).headers()).containsEntry(WorkItem.STEP_SERVICE_HEADER, info.role());
        assertThat(steps.get(1).payload()).isEqualTo("second");
        assertThat(steps.get(1).index()).isEqualTo(1);
        assertThat(steps.get(1).headers()).containsEntry(WorkItem.STEP_SERVICE_HEADER, info.role());

        // original item remains unchanged
        assertThat(base.asString()).isEqualTo("first");
        assertThat(steps(base)).hasSize(1);
    }

    @Test
    void addStepMergesHeadersIntoNewStep() {
        WorkerInfo info = testInfo();
        WorkItem base = WorkItem.text(info, "one")
            .header("a", "1")
            .build();

        WorkItem updated = base.addStep(info, "two", Map.of("b", "2"));

        assertThat(updated.asString()).isEqualTo("two");
        assertThat(updated.headers())
            .containsEntry("a", "1")
            .doesNotContainKey("b");

        List<WorkStep> steps = steps(updated);
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).payload()).isEqualTo("one");
        assertThat(steps.get(0).headers()).containsEntry(WorkItem.STEP_SERVICE_HEADER, info.role());
        assertThat(steps.get(1).payload()).isEqualTo("two");
        assertThat(steps.get(1).headers())
            .containsEntry(WorkItem.STEP_SERVICE_HEADER, info.role())
            .containsEntry("b", "2");
    }

    @Test
    void addStepHeaderUpdatesCurrentStepOnly() {
        WorkerInfo info = testInfo();
        WorkItem base = WorkItem.text(info, "one").build();
        WorkItem withSecond = base.addStepPayload(info, "two");

        WorkItem updated = withSecond.addStepHeader("x", "1");

        List<WorkStep> steps = steps(updated);
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).headers()).doesNotContainKey("x");
        assertThat(steps.get(1).headers()).containsEntry("x", "1");
    }

    @Test
    void clearHistoryKeepsLatestStepOnly() {
        WorkerInfo info = testInfo();
        WorkItem item = WorkItem.text(info, "first")
            .build()
            .addStepPayload(info, "second")
            .addStepPayload(info, "third");

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
        WorkerInfo info = testInfo();
        WorkItem item = WorkItem.text(info, "first")
            .build()
            .addStepPayload(info, "second");

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

    private static WorkerInfo testInfo() {
        return new WorkerInfo("test", "swarm", "instance", null, null);
    }
}
