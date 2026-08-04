package io.pockethive.swarm.model.lifecycle;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable runtime identity assigned when an operation is reserved.
 *
 * <p>For CREATE this identifies the planned runtime before the controller is
 * registered, so every terminal outcome has the same runtime metadata.
 */
public record RuntimeMetadata(
    String templateId,
    String runId,
    String containerId,
    String image,
    String stackName
) {

  public RuntimeMetadata {
    templateId = ContractValues.requireText("templateId", templateId);
    runId = ContractValues.requireText("runId", runId);
    containerId = ContractValues.optionalText(containerId);
    image = ContractValues.optionalText(image);
    stackName = ContractValues.optionalText(stackName);
  }

  public RuntimeMetadata(String templateId, String runId) {
    this(templateId, runId, null, null, null);
  }

  /** Canonical control-plane wire representation. */
  public Map<String, Object> asControlPlaneRuntime() {
    Map<String, Object> runtime = new LinkedHashMap<>();
    runtime.put("templateId", templateId);
    runtime.put("runId", runId);
    if (containerId != null) {
      runtime.put("containerId", containerId);
    }
    if (image != null) {
      runtime.put("image", image);
    }
    if (stackName != null) {
      runtime.put("stackName", stackName);
    }
    return Map.copyOf(runtime);
  }
}
