package io.pockethive.trigger;

import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.SchedulerInputProperties;
import io.pockethive.worker.sdk.config.WorkerInputType;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The trigger service nudges the rest of PocketHive into motion. It runs generator-style workers
 * that either execute a shell command or call an HTTP endpoint according to
 * {@link TriggerWorkerConfig}. Many teams deploy it near their orchestrator so a single REST hook
 * or cron-style shell command can fan out to the rest of the swarm.
 *
 * <p>The worker is annotated with {@link PocketHiveWorker} so it advertises the {@code trigger}
 * role and consumes runtime overrides. Junior engineers often experiment by sending control-plane
 * patches such as:</p>
 *
 * <pre>{@code
 * {
 *   "actionType": "rest",
 *   "url": "https://example.internal/hooks/demo",
 *   "method": "POST",
 *   "body": "{\"launch\":true}",
 *   "headers": {"authorization": "Bearer demo-token"}
 * }
 * }
 * </pre>
 *
 * <p>The worker does not emit metrics, but it logs every shell line and HTTP request/response at
 * debug level. Pair those logs with the status updates captured below to troubleshoot unexpected
 * trigger behavior.</p>
 */
@Component("triggerWorker")
@PocketHiveWorker(
    role = "trigger",
    input = WorkerInputType.SCHEDULER,
    config = TriggerWorkerConfig.class,
    inputConfig = SchedulerInputProperties.class
)
class TriggerWorkerImpl implements PocketHiveWorkerFunction {

  private final TriggerWorkerProperties properties;
  private final HttpClient httpClient;

  @Autowired
  TriggerWorkerImpl(TriggerWorkerProperties properties) {
    this(properties, HttpClient.newHttpClient());
  }

  TriggerWorkerImpl(TriggerWorkerProperties properties, HttpClient httpClient) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
  }

  /**
   * Resolves trigger configuration, records it in the status stream, and performs either a shell or
   * REST action. Configuration keys include:
   *
   * <ul>
   *   <li>{@code intervalMs} – how often the orchestrator schedules this worker (for dashboards).</li>
   *   <li>{@code actionType} – {@code shell} or {@code rest}. Any other value is ignored with a
   *       debug log.</li>
   *   <li>{@code command} – the shell command to execute when {@code actionType} is {@code shell}.</li>
   *   <li>{@code url}, {@code method}, {@code headers}, {@code body} – REST request settings.</li>
   * </ul>
   *
   * <p>For shell triggers, the worker streams each output line to the PocketHive logger. For REST
   * triggers, it logs both the request and response (status code, headers, a trimmed body snippet).
   * Use these breadcrumbs to spot authentication issues or CLI exit codes during debugging.</p>
   *
   * <p>To extend the worker, copy this class or refactor the helper methods ({@link #runShell} and
   * {@link #callRest}) so they are overridable. Many teams add retry logic, metrics, or response
   * validation this way.</p>
   *
   * @param context PocketHive runtime context that provides configuration overrides, logging, and
   *     status publishing.
   * @return {@link WorkResult#none()} because the trigger merely kicks off side effects instead of
   *     enqueueing work.
   */
  @Override
  public WorkResult onMessage(WorkMessage seed, WorkerContext context) {
    TriggerWorkerConfig config = context.config(TriggerWorkerConfig.class)
        .orElseGet(properties::defaultConfig);

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
