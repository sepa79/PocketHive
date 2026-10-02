package io.pockethive.processor.mip;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.pockethive.processor.ProcessorWorkerConfig;
import io.pockethive.processor.handler.Iso8583Endpoint;

/**
 * Responsibility: resolve the explicit MIP URI and required private adapter settings once.
 * Must not: bind sockets, infer adapters or maintain a second worker configuration state.
 * Contract: RESP-PROCESSOR-MIP-CONFIG — docs/architecture/runtime-responsibilities.md#resp-processor-mip-config.
 */
public final class MipServerConfigResolver {
  public static final String SETTINGS_KEY = "mipServer";
  private final ObjectMapper mapper;
  private final ObjectReader settingsReader;

  public MipServerConfigResolver(ObjectMapper mapper) {
    this.mapper = mapper;
    this.settingsReader = mapper.readerFor(MipServerSettings.class)
        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .with(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);
  }

  public MipServerConfig resolve(ProcessorWorkerConfig worker) {
    Iso8583Endpoint endpoint = Iso8583Endpoint.parse(worker.baseUrl());
    if (!endpoint.mipServer()) throw new IllegalArgumentException("baseUrl must select mip:// for MIP server");
    Object raw = worker.privateConfig().get(SETTINGS_KEY);
    if (raw == null) throw new IllegalArgumentException("privateConfig.mipServer must be declared");
    try {
      com.fasterxml.jackson.databind.JsonNode settingsNode = mapper.valueToTree(raw);
      MipServerSettings settings = settingsReader.readValue(settingsNode);
      return new MipServerConfig(endpoint, settings);
    } catch (Exception ex) {
      // Do not retain Jackson exceptions containing field values or full private configuration.
      throw new IllegalArgumentException("Invalid privateConfig.mipServer settings");
    }
  }
}
