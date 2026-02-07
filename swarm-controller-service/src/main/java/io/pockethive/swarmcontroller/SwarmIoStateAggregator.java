package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Aggregates worker-reported IO state into a swarm-level view.
 *
 * <p>Input/output states follow {@code docs/spec} and are expected to be carried
 * in worker status metrics under {@code data.ioState.work}.</p>
 */
final class SwarmIoStateAggregator {

  private static final List<String> IO_INPUT_PRECEDENCE = List.of(
      "upstream-error",
      "out-of-data",
      "backpressure",
      "ok",
      "unknown"
  );

  private static final List<String> IO_OUTPUT_PRECEDENCE = List.of(
      "downstream-error",
      "blocked",
      "throttled",
      "ok",
      "unknown"
  );

  private final ConcurrentMap<String, IoState> byWorker = new ConcurrentHashMap<>();

  void updateFromWorkerStatus(String role, String instance, JsonNode dataNode) {
    if (role == null || role.isBlank() || instance == null || instance.isBlank()) {
      return;
    }
    if (dataNode == null || dataNode.isMissingNode() || dataNode.isNull()) {
      return;
    }
    JsonNode ioStateNode = dataNode.path("ioState").path("work");
    if (!ioStateNode.isObject()) {
      return;
    }
    String input = textOrNull(ioStateNode.path("input"));
    String output = textOrNull(ioStateNode.path("output"));
    if (input == null && output == null) {
      return;
    }
    String normalizedInput = normalize(input, IO_INPUT_PRECEDENCE);
    String normalizedOutput = normalize(output, IO_OUTPUT_PRECEDENCE);
    if (normalizedInput == null && normalizedOutput == null) {
      return;
    }
    byWorker.put(workerKey(role, instance), new IoState(normalizedInput, normalizedOutput));
  }

  IoState aggregateWork() {
    String bestInput = null;
    String bestOutput = null;
    for (IoState state : byWorker.values()) {
      if (state == null) continue;
      String input = state.input();
      if (input != null && isBetter(input, bestInput, IO_INPUT_PRECEDENCE)) {
        bestInput = input;
      }
      String output = state.output();
      if (output != null && isBetter(output, bestOutput, IO_OUTPUT_PRECEDENCE)) {
        bestOutput = output;
      }
    }
    if (bestInput == null) bestInput = "unknown";
    if (bestOutput == null) bestOutput = "unknown";
    return new IoState(bestInput, bestOutput);
  }

  private static String workerKey(String role, String instance) {
    return role + "/" + instance;
  }

  private static String textOrNull(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    String text = node.asText();
    if (text == null) {
      return null;
    }
    String trimmed = text.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String normalize(String value, List<String> allowed) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return allowed.contains(trimmed) ? trimmed : null;
  }

  private static boolean isBetter(String candidate, String current, List<String> precedence) {
    Objects.requireNonNull(candidate, "candidate");
    if (current == null) {
      return true;
    }
    int candidateIdx = precedence.indexOf(candidate);
    int currentIdx = precedence.indexOf(current);
    if (candidateIdx < 0) {
      return false;
    }
    if (currentIdx < 0) {
      return true;
    }
    return candidateIdx < currentIdx;
  }

  record IoState(String input, String output) {}
}

