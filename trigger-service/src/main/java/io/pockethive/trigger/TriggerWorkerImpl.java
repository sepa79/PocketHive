package io.pockethive.trigger;

import io.pockethive.worker.sdk.api.GeneratorWorker;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerType;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

@Component("triggerWorker")
@PocketHiveWorker(
    role = "trigger",
    type = WorkerType.GENERATOR,
    config = TriggerWorkerConfig.class
)
class TriggerWorkerImpl implements GeneratorWorker {

  private final TriggerDefaults defaults;
  private final HttpClient httpClient;

  TriggerWorkerImpl(TriggerDefaults defaults) {
    this(defaults, HttpClient.newHttpClient());
  }

  TriggerWorkerImpl(TriggerDefaults defaults, HttpClient httpClient) {
    this.defaults = Objects.requireNonNull(defaults, "defaults");
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
  }

  @Override
  public WorkResult generate(WorkerContext context) {
    TriggerWorkerConfig config = context.config(TriggerWorkerConfig.class)
        .orElseGet(defaults::asConfig);

    context.statusPublisher()
        .update(status -> status
            .data("intervalMs", config.intervalMs())
            .data("actionType", config.actionType())
            .data("command", config.command())
            .data("url", config.url())
            .data("method", config.method())
            .data("body", config.body())
            .data("headers", config.headers()));

    Logger logger = context.logger();
    try {
      switch (config.actionType()) {
        case "shell" -> runShell(config.command(), logger);
        case "rest" -> callRest(config, logger);
        default -> logger.debug("Unknown trigger action type: {}", config.actionType());
      }
    } catch (Exception ex) {
      logger.warn("Trigger action failed: {}", ex.toString(), ex);
    }
    return WorkResult.none();
  }

  private void runShell(String command, Logger logger) throws Exception {
    if (command == null || command.isBlank()) {
      logger.debug("No trigger command configured; skipping shell action");
      return;
    }
    Process process = new ProcessBuilder("bash", "-c", command).start();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        logger.debug("[SHELL] {}", line);
      }
    }
    int exit = process.waitFor();
    logger.debug("[SHELL] exit={} cmd={}", exit, command);
  }

  private void callRest(TriggerWorkerConfig config, Logger logger) throws Exception {
    if (config.url().isBlank()) {
      logger.warn("No URL configured for REST trigger action");
      return;
    }
    URI target = URI.create(config.url());
    HttpRequest.Builder builder = HttpRequest.newBuilder(target);
    Map<String, String> headers = config.headers();
    headers.forEach(builder::header);

    if (!config.body().isBlank()) {
      builder.method(config.method(), HttpRequest.BodyPublishers.ofString(config.body(), StandardCharsets.UTF_8));
    } else {
      builder.method(config.method(), HttpRequest.BodyPublishers.noBody());
    }

    logger.debug("[REST] REQ {} {} headers={} body={}", config.method(), config.url(), headers, snippet(config.body()));
    HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    logger.debug("[REST] RESP {} {} status={} headers={} body={}",
        config.method(),
        config.url(),
        response.statusCode(),
        response.headers().map(),
        snippet(response.body()));
  }

  private String snippet(String payload) {
    if (payload == null) {
      return "";
    }
    String trimmed = payload.strip();
    if (trimmed.length() > 256) {
      return trimmed.substring(0, 256) + "…";
    }
    return trimmed;
  }
}
