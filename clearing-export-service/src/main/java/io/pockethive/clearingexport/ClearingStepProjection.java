package io.pockethive.clearingexport;

import io.pockethive.templatedata.JsonViews;
import io.pockethive.work.api.WorkStep;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Responsibility: project clearing steps and their optional JSON-object views for templates.
 * Must not: parse documents, select the source step, evaluate templates or persist records.
 * Contract: RESP-CLEARING-TEMPLATE-DATA — docs/architecture/runtime-responsibilities.md#resp-clearing-template-data.
 * Consumes RESP-TEMPLATE-DATA — docs/architecture/runtime-responsibilities.md#resp-template-data.
 */
final class ClearingStepProjection {

  Map<String, Object> project(List<WorkStep> steps, WorkStep selectedStep) {
    List<Map<String, Object>> all = new ArrayList<>(steps.size());
    Map<String, Object> byIndex = new LinkedHashMap<>();
    Map<String, Object> first = null;
    Map<String, Object> latest = null;
    Map<String, Object> previous = null;
    Map<String, Object> selected = null;

    for (int position = 0; position < steps.size(); position++) {
      WorkStep step = steps.get(position);
      Map<String, Object> projected = projectStep(step);
      all.add(projected);
      byIndex.put(Integer.toString(step.index()), projected);
      if (position == 0) {
        first = projected;
      }
      if (position == steps.size() - 1) {
        latest = projected;
      }
      if (position == steps.size() - 2) {
        previous = projected;
      }
      if (step == selectedStep) {
        selected = projected;
      }
    }
    if (selected == null) {
      throw new IllegalStateException("Selected WorkStep is missing from WorkItem steps");
    }

    Map<String, Object> context = new LinkedHashMap<>();
    context.put("all", List.copyOf(all));
    context.put("byIndex", Map.copyOf(byIndex));
    context.put("first", first);
    context.put("latest", latest);
    context.put("selected", selected);
    context.put("selectedIndex", selectedStep.index());
    context.put("count", steps.size());
    if (previous != null) {
      context.put("previous", previous);
    }
    return Map.copyOf(context);
  }

  private Map<String, Object> projectStep(WorkStep step) {
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("index", step.index());
    record.put("payload", step.payload());
    record.put("headers", step.headers());
    Map<String, Object> json = JsonViews.optionalObject(step.payload());
    if (json != null) {
      record.put("json", json);
    }
    return Map.copyOf(record);
  }
}
