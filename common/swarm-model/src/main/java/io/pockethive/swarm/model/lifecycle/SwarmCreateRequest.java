package io.pockethive.swarm.model.lifecycle;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.pockethive.swarm.model.NetworkMode;

/** Canonical REST request for creating a swarm runtime. */
public final class SwarmCreateRequest {

  private final String templateId;
  private final String idempotencyKey;
  private final boolean autoPullImages;
  private final String sutId;
  private final String variablesProfileId;
  private final NetworkMode networkMode;
  private final String networkProfileId;

  private SwarmCreateRequest(
      String templateId,
      String idempotencyKey,
      boolean autoPullImages,
      String sutId,
      String variablesProfileId,
      NetworkMode networkMode,
      String networkProfileId) {
    this.templateId = templateId;
    this.idempotencyKey = idempotencyKey;
    this.autoPullImages = autoPullImages;
    this.sutId = sutId;
    this.variablesProfileId = variablesProfileId;
    this.networkMode = networkMode;
    this.networkProfileId = networkProfileId;
  }

  /** Creates a typed request through the same canonical JSON Schema boundary as REST. */
  public static SwarmCreateRequest of(
      String templateId,
      String idempotencyKey,
      boolean autoPullImages,
      String sutId,
      String variablesProfileId,
      NetworkMode networkMode,
      String networkProfileId) {
    return SwarmCreateRequestJsonCodec.fromArguments(
        templateId,
        idempotencyKey,
        autoPullImages,
        sutId,
        variablesProfileId,
        networkMode,
        networkProfileId);
  }

  static SwarmCreateRequest fromValidatedValues(
      String templateId,
      String idempotencyKey,
      boolean autoPullImages,
      String sutId,
      String variablesProfileId,
      NetworkMode networkMode,
      String networkProfileId) {
    return new SwarmCreateRequest(
        templateId,
        idempotencyKey,
        autoPullImages,
        sutId,
        variablesProfileId,
        networkMode,
        networkProfileId);
  }

  @JsonProperty("templateId")
  public String templateId() {
    return templateId;
  }

  @JsonProperty("idempotencyKey")
  public String idempotencyKey() {
    return idempotencyKey;
  }

  @JsonProperty("autoPullImages")
  public boolean autoPullImages() {
    return autoPullImages;
  }

  @JsonProperty("sutId")
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public String sutId() {
    return sutId;
  }

  @JsonProperty("variablesProfileId")
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public String variablesProfileId() {
    return variablesProfileId;
  }

  @JsonProperty("networkMode")
  public NetworkMode networkMode() {
    return networkMode;
  }

  @JsonProperty("networkProfileId")
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public String networkProfileId() {
    return networkProfileId;
  }
}
