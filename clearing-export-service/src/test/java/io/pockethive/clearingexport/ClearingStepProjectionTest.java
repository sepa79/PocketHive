package io.pockethive.clearingexport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkStep;
import io.pockethive.work.api.WorkerInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClearingStepProjectionTest {
  private static final WorkerInfo INFO = new WorkerInfo("clearing", "swarm", "instance", null, null);
  private final ClearingStepProjection projection = new ClearingStepProjection();

  @Test
  void keepsEveryExistingStepAliasAndLeadingZeroString() {
    WorkItem item = WorkItem.text(INFO, "{\"id\":\"000123\"}").stepHeader("source", "first").build()
        .addStepPayload(INFO, "<card id=\"000456\"/>")
        .addStepPayload(INFO, "{\"id\":\"000789\"}");
    List<WorkStep> steps = steps(item);

    Map<String, Object> context = projection.project(steps, steps.get(1));

    List<?> all = (List<?>) context.get("all");
    Map<?, ?> byIndex = (Map<?, ?>) context.get("byIndex");
    assertThat(context.get("first")).isSameAs(all.get(0)).isSameAs(byIndex.get("0"));
    assertThat(context.get("previous")).isSameAs(all.get(1));
    assertThat(context.get("selected")).isSameAs(all.get(1)).isSameAs(byIndex.get("1"));
    assertThat(context.get("latest")).isSameAs(all.get(2)).isSameAs(byIndex.get("2"));
    assertThat(context).containsEntry("count", 3).containsEntry("selectedIndex", 1);
    assertThat(((Map<?, ?>) all.get(0)).get("json")).isEqualTo(Map.of("id", "000123"));
    assertThat(((Map<?, ?>) all.get(0)).get("headers")).isSameAs(steps.getFirst().headers());
    assertThat(((Map<?, ?>) all.get(1)).get("payload")).isEqualTo("<card id=\"000456\"/>");
    assertThat(((Map<?, ?>) all.get(1)).containsKey("json")).isFalse();
    assertThat(((Map<?, ?>) all.get(2)).get("json")).isEqualTo(Map.of("id", "000789"));
    assertThat(context).doesNotContainKeys("record", "payloadAsJson", "payloadAsXML");
  }

  @Test
  void omitsPreviousForASingleStepAndRejectsASelectionOutsideTheHistory() {
    List<WorkStep> steps = steps(WorkItem.text(INFO, "{\"id\":\"000123\"}").build());

    assertThat(projection.project(steps, steps.getFirst())).doesNotContainKey("previous");
    WorkStep foreign = WorkItem.text(INFO, "other").build().steps().iterator().next();
    assertThatThrownBy(() -> projection.project(steps, foreign))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("Selected WorkStep is missing");
  }

  private List<WorkStep> steps(WorkItem item) {
    List<WorkStep> steps = new ArrayList<>();
    item.steps().forEach(steps::add);
    return steps;
  }
}
