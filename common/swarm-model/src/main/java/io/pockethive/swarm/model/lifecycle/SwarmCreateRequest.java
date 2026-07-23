package io.pockethive.swarm.model.lifecycle;

import io.pockethive.swarm.model.NetworkMode;

/** Canonical REST request for creating a swarm runtime. */
public record SwarmCreateRequest(
    String templateId,
    String idempotencyKey,
    String notes,
    Boolean autoPullImages,
    String sutId,
    String variablesProfileId,
    NetworkMode networkMode,
    String networkProfileId
) {

  public SwarmCreateRequest {
    templateId = ContractValues.requireText("templateId", templateId);
    idempotencyKey = ContractValues.requireText("idempotencyKey", idempotencyKey);
    notes = ContractValues.optionalText(notes);
    sutId = ContractValues.optionalText(sutId);
    variablesProfileId = ContractValues.optionalText(variablesProfileId);
    if (networkMode == null) {
      throw new IllegalArgumentException("networkMode must be provided");
    }
    networkProfileId = ContractValues.optionalText(networkProfileId);
    if (networkMode == NetworkMode.DIRECT && networkProfileId != null) {
      throw new IllegalArgumentException("networkProfileId requires networkMode=PROXIED");
    }
    if (networkMode == NetworkMode.PROXIED && networkProfileId == null) {
      throw new IllegalArgumentException("networkProfileId must be provided when networkMode=PROXIED");
    }
    if (networkMode == NetworkMode.PROXIED && sutId == null) {
      throw new IllegalArgumentException("sutId must be provided when networkMode=PROXIED");
    }
  }

  public SwarmCreateRequest(String templateId, String idempotencyKey, String notes) {
    this(templateId, idempotencyKey, notes, null, null, null, NetworkMode.DIRECT, null);
  }

  public SwarmCreateRequest(
      String templateId,
      String idempotencyKey,
      String notes,
      Boolean autoPullImages,
      String sutId,
      String variablesProfileId) {
    this(templateId, idempotencyKey, notes, autoPullImages, sutId, variablesProfileId, NetworkMode.DIRECT, null);
  }
}
