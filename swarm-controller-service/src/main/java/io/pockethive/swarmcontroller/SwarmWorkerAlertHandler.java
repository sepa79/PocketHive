package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.AlertMessage;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.swarmcontroller.runtime.SwarmJournal;
import io.pockethive.swarmcontroller.runtime.SwarmJournalEntries;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Responsibility: Journal accepted worker alerts and apply config-error evidence to the lifecycle core.
 * Must not: Decode AMQP payloads, select routing targets, or terminalize pending lifecycle commands.
 * Contract: Alerts are diagnostic evidence and may affect only their matching config workflow.
 */
@Component
public class SwarmWorkerAlertHandler {

  private final SwarmLifecycle lifecycle;
  private final SwarmJournal journal;
  private final ObjectMapper mapper;
  private final String controllerInstance;

  public SwarmWorkerAlertHandler(
      SwarmLifecycle lifecycle,
      SwarmJournal journal,
      ObjectMapper mapper,
      @Qualifier("instanceId") String controllerInstance) {
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    this.journal = Objects.requireNonNull(journal, "journal");
    this.mapper = Objects.requireNonNull(mapper, "mapper").findAndRegisterModules();
    this.controllerInstance = requireText("controllerInstance", controllerInstance);
  }

  Optional<String> handle(String routingKey, AlertMessage alert) {
    Objects.requireNonNull(alert, "alert");
    if (controllerInstance.equals(alert.origin())) {
      return Optional.empty();
    }
    journal.append(SwarmJournalEntries.inAlert(mapper, routingKey, alert));
    String phase = alert.data().context() == null
        ? null
        : text(mapper.valueToTree(alert.data().context()).path("phase"));
    if (!ControlPlaneSignals.CONFIG_UPDATE.equalsIgnoreCase(phase)) {
      return Optional.empty();
    }
    return lifecycle.handleConfigUpdateError(
        alert.scope().role(), alert.scope().instance(), alert.data().message());
  }

  private static String text(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    String value = node.asText(null);
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String requireText(String field, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }
}
