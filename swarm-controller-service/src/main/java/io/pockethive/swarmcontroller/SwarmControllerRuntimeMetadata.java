package io.pockethive.swarmcontroller;

import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Responsibility: Resolve the Swarm Controller runtime metadata published on control-plane envelopes.
 * Must not: Build envelopes, publish messages, or infer missing required runtime identity.
 * Contract: Resolve one immutable metadata snapshot from the explicit process environment and journal run id.
 */
@Component
final class SwarmControllerRuntimeMetadata {

  private final Map<String, Object> values;

  SwarmControllerRuntimeMetadata(
      SwarmControllerProperties properties,
      @Value("${pockethive.journal.run-id:}") String journalRunId) {
    String swarmId = Objects.requireNonNull(properties, "properties").getSwarmId();
    Map<String, Object> resolved = new LinkedHashMap<>();
    resolved.put("containerId", envValue("HOSTNAME"));
    resolved.put("image", envValue("POCKETHIVE_RUNTIME_IMAGE"));
    resolved.put("stackName", "ph-" + requireText(swarmId, "swarmId").toLowerCase(Locale.ROOT));
    resolved.put("templateId", requireEnvValue("POCKETHIVE_TEMPLATE_ID"));
    resolved.put("runId", requireText(journalRunId, "pockethive.journal.run-id"));
    this.values = Collections.unmodifiableMap(resolved);
  }

  Map<String, Object> values() {
    return values;
  }

  private static String envValue(String key) {
    String value = System.getenv(key);
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isBlank() ? null : trimmed;
  }

  private static String requireEnvValue(String key) {
    String value = envValue(key);
    if (value == null) {
      throw new IllegalStateException("Missing required environment variable: " + key);
    }
    return value;
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(field + " must not be blank");
    }
    return value.trim();
  }
}
