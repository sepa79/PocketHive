package io.pockethive.httpsequence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.requesttemplates.HttpTemplateDefinition;
import io.pockethive.requesttemplates.TemplateDefinition;
import io.pockethive.requesttemplates.TemplateLoader;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.auth.AuthConfig;
import io.pockethive.worker.sdk.auth.AuthHeaderGenerator;
import io.pockethive.worker.sdk.config.RedisSequenceProperties;
import io.pockethive.worker.sdk.templating.TemplateRenderer;
import io.pockethive.worker.sdk.templating.TemplatingRenderException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;

final class HttpSequenceRunner {

  private final ObjectMapper mapper;
  private final Clock clock;
  private final TemplateRenderer templateRenderer;
  private final TemplateLoader templateLoader;
  private final HttpCallExecutor httpExecutor;
  private final RedisDebugCaptureStore debugCaptureStore;
  private final AuthHeaderGenerator authHeaderGenerator;

  private volatile Map<String, TemplateDefinition> templates;
  private volatile String lastTemplateConfigKey;

  private final LongAdder journeys = new LongAdder();
  private final LongAdder okJourneys = new LongAdder();
  private final LongAdder errorJourneys = new LongAdder();

  HttpSequenceRunner(
      ObjectMapper mapper,
      Clock clock,
      TemplateRenderer templateRenderer,
      TemplateLoader templateLoader,
      HttpCallExecutor httpExecutor,
      RedisSequenceProperties redisProperties,
      AuthHeaderGenerator authHeaderGenerator
  ) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.templateRenderer = Objects.requireNonNull(templateRenderer, "templateRenderer");
    this.templateLoader = Objects.requireNonNull(templateLoader, "templateLoader");
    this.httpExecutor = Objects.requireNonNull(httpExecutor, "httpExecutor");
    this.debugCaptureStore = new RedisDebugCaptureStore(mapper, redisProperties);
    this.authHeaderGenerator = authHeaderGenerator;
  }

  WorkItem run(WorkItem seed, WorkerContext context, HttpSequenceWorkerConfig config) {
    Objects.requireNonNull(seed, "seed");
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(config, "config");

    journeys.increment();
    Logger log = context.logger();
    WorkerInfo info = context.info();

    Map<String, Object> payload = parsePayloadAsMap(seed.payload());
    if (payload == null) {
      payload = new java.util.LinkedHashMap<>();
    }
    if (!config.vars().isEmpty()) {
      Map<String, Object> headers = new java.util.LinkedHashMap<>(seed.headers());
      headers.putIfAbsent("vars", config.vars());
      seed = seed.toBuilder().headers(headers).build();
    }

    reloadTemplatesIfNeeded(config);
    WorkItem current = seed;
    boolean failed = false;
    int totalCapturedBytes = 0;

    for (int i = 0; i < config.steps().size(); i++) {
      HttpSequenceWorkerConfig.Step step = config.steps().get(i);
      if (step.callId() == null) {
        current = appendErrorStep(current, context, i, payload, "Missing callId");
        failed = true;
        break;
      }

      String serviceId = step.serviceId() != null ? step.serviceId() : config.serviceId();
      String key = TemplateLoader.key(serviceId, step.callId());
      TemplateDefinition definition = templates.get(key);
      if (!(definition instanceof HttpTemplateDefinition httpDef)) {
        current = appendErrorStep(current, context, i, payload, "Missing HTTP template for " + key);
        failed = true;
        break;
      }

      HttpCallExecutor.RenderedCall rendered;
      try {
        rendered = renderCall(httpDef, serviceId, step.callId(), payload, current, context);
      } catch (Exception ex) {
        String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
        current = appendErrorStep(current, context, i, payload, msg);
        failed = true;
        break;
      }

      HttpCallAttempt attempt = executeWithRetry(config, step, rendered, context);
      HttpCallExecutor.HttpCallResult result = attempt.result();

      long durationMs = attempt.totalDurationMs();
      int attempts = attempt.attempts();

      boolean isError = result.statusCode() < 200 || result.statusCode() >= 300;
      String sha256 = sha256Hex(result.body());
      String debugRef = null;
      String bodyPreview = preview(result.body(), config.debugCapture().bodyPreviewBytes());

      if (shouldCapture(config.debugCapture(), isError)) {
        int maxBodyBytes = config.debugCapture().maxBodyBytes();
        int bodyBytes = result.body() == null ? 0 : result.body().getBytes(StandardCharsets.UTF_8).length;
        if (bodyBytes > maxBodyBytes) {
          bodyBytes = maxBodyBytes;
        }
        if ((totalCapturedBytes + bodyBytes) <= config.debugCapture().maxJourneyBytes()) {
          totalCapturedBytes += bodyBytes;
          debugRef = debugCaptureStore.store(info, config.baseUrl(), serviceId, step.callId(), rendered, result, config.debugCapture());
        }
      }

      boolean extractedOk = applyExtracts(step, payload, result, context);
      if (!extractedOk) {
        current = appendResultStep(current, context, i, step, payload, serviceId, step.callId(), result, durationMs, attempts, sha256, debugRef, bodyPreview, "required extract missing");
        failed = true;
        break;
      }
      applySetters(step, payload, current, context);

      current = appendResultStep(current, context, i, step, payload, serviceId, step.callId(), result, durationMs, attempts, sha256, debugRef, bodyPreview, null);

      if (isError && !step.continueOnNon2xx()) {
        failed = true;
        break;
      }
    }

    if (failed) {
      errorJourneys.increment();
    } else {
      okJourneys.increment();
    }
    publishStatus(context, config);
    return current;
  }

  private void publishStatus(WorkerContext context, HttpSequenceWorkerConfig config) {
    context.statusPublisher().update(status -> status
        .data("baseUrl", config.baseUrl())
        .data("templateRoot", config.templateRoot())
        .data("serviceId", config.serviceId())
        .data("enabled", context.enabled())
        .data("journeys", journeys.sum())
        .data("okJourneys", okJourneys.sum())
        .data("errorJourneys", errorJourneys.sum()));
  }

  private HttpCallExecutor.RenderedCall renderCall(HttpTemplateDefinition httpDef,
                                  String serviceId,
                                  String callId,
                                  Map<String, Object> payload,
                                  WorkItem workItem,
                                  WorkerContext context) {
    Map<String, Object> ctx = new java.util.HashMap<>();
    ctx.put("payload", payload);
    ctx.put("payloadAsJson", payload);
    ctx.put("ctx", payload);
    ctx.put("headers", workItem.headers());
    Object vars = workItem.headers().get("vars");
    if (vars != null) {
      ctx.put("vars", vars);
    }
    ctx.put("workItem", workItem);

    String path = render("pathTemplate", httpDef.pathTemplate(), ctx);
    String method = render("method", httpDef.method(), ctx);
    String body = render("bodyTemplate", httpDef.bodyTemplate(), ctx);

    Map<String, String> headers = new java.util.LinkedHashMap<>();
    if (httpDef.headersTemplate() != null) {
      httpDef.headersTemplate().forEach((name, value) -> headers.put(name, render("header:" + name, value, ctx)));
    }

    if (httpDef.auth() != null && authHeaderGenerator != null) {
      try {
        AuthConfig authConfig = AuthConfig.fromTemplate(httpDef.auth(), serviceId, callId);
        Map<String, String> authHeaders = authHeaderGenerator.generate(context, authConfig, workItem);
        headers.putAll(authHeaders);
      } catch (Exception ex) {
        context.logger().warn("Auth header generation failed for serviceId={} callId={}: {}", serviceId, callId, ex.getMessage());
      }
    }

    String upper = method == null || method.isBlank() ? "GET" : method.toUpperCase(Locale.ROOT);
    return new HttpCallExecutor.RenderedCall(upper, path, body, Map.copyOf(headers));
  }

  private String render(String label, String template, Map<String, Object> ctx) {
    if (template == null || template.isBlank()) {
      return "";
    }
    try {
      return templateRenderer.render(template, ctx);
    } catch (Exception ex) {
      throw new TemplatingRenderException("Failed to render " + label, ex);
    }
  }

  private HttpCallAttempt executeWithRetry(HttpSequenceWorkerConfig worker,
                                          HttpSequenceWorkerConfig.Step step,
                                          HttpCallExecutor.RenderedCall rendered,
                                          WorkerContext context) {
    HttpSequenceWorkerConfig.Retry retry = step.retry();
    int maxAttempts = retry == null ? 1 : retry.maxAttempts();
    if (maxAttempts <= 0) {
      maxAttempts = 1;
    }

    long start = clock.millis();
    HttpCallExecutor.HttpCallResult last = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        last = httpExecutor.execute(worker.baseUrl(), rendered);
      } catch (Exception ex) {
        last = new HttpCallExecutor.HttpCallResult(-1, Map.of(), "", ex.toString());
      }

      boolean shouldRetry = attempt < maxAttempts && shouldRetry(retry, last);
      if (!shouldRetry) {
        long totalMs = Math.max(0L, clock.millis() - start);
        return new HttpCallAttempt(attempt, totalMs, last);
      }

      long sleepMs = computeBackoffMs(retry, attempt);
      if (sleepMs > 0L) {
        try {
          Thread.sleep(sleepMs);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          long totalMs = Math.max(0L, clock.millis() - start);
          return new HttpCallAttempt(attempt, totalMs, last);
        }
      }
    }
    long totalMs = Math.max(0L, clock.millis() - start);
    return new HttpCallAttempt(maxAttempts, totalMs, last);
  }

  private boolean shouldRetry(HttpSequenceWorkerConfig.Retry retry, HttpCallExecutor.HttpCallResult result) {
    if (retry == null || retry.on().isEmpty() || result == null) {
      return false;
    }
    int status = result.statusCode();
    boolean exception = status < 0 || result.error() != null;

    for (String tokenRaw : retry.on()) {
      if (tokenRaw == null || tokenRaw.isBlank()) {
        continue;
      }
      String token = tokenRaw.trim().toLowerCase(Locale.ROOT);
      if ("exception".equals(token) && exception) {
        return true;
      }
      if ("non2xx".equals(token) && (status < 200 || status >= 300)) {
        return true;
      }
      if (token.length() == 3 && token.endsWith("xx") && Character.isDigit(token.charAt(0))) {
        int prefix = token.charAt(0) - '0';
        if (status / 100 == prefix) {
          return true;
        }
      }
      if (isDigits(token)) {
        try {
          int code = Integer.parseInt(token);
          if (status == code) {
            return true;
          }
        } catch (NumberFormatException ignored) {
        }
      }
    }
    return false;
  }

  private static long computeBackoffMs(HttpSequenceWorkerConfig.Retry retry, int attempt) {
    if (retry == null) {
      return 0L;
    }
    long initial = Math.max(0L, retry.initialBackoffMs());
    if (initial <= 0L) {
      return 0L;
    }
    double multiplier = retry.backoffMultiplier() <= 0.0 ? 1.0 : retry.backoffMultiplier();
    long max = retry.maxBackoffMs() <= 0L ? initial : retry.maxBackoffMs();

    double pow = Math.pow(multiplier, Math.max(0, attempt - 1));
    long computed;
    try {
      computed = (long) (initial * pow);
    } catch (Exception ignored) {
      computed = initial;
    }
    if (computed < 0L) {
      computed = initial;
    }
    return Math.min(max, computed);
  }

  private static boolean isDigits(String value) {
    for (int i = 0; i < value.length(); i++) {
      if (!Character.isDigit(value.charAt(i))) {
        return false;
      }
    }
    return !value.isEmpty();
  }

  private boolean applyExtracts(HttpSequenceWorkerConfig.Step step,
                                Map<String, Object> payload,
                                HttpCallExecutor.HttpCallResult result,
                                WorkerContext context) {
    if (step.extracts().isEmpty()) {
      return true;
    }

    JsonNode body = null;
    for (HttpSequenceWorkerConfig.Extract extract : step.extracts()) {
      Object value = null;
      if (extract.fromStatus()) {
        value = result.statusCode();
      } else if (extract.fromHeader() != null) {
        String key = extract.fromHeader().toLowerCase(Locale.ROOT);
        java.util.List<String> values = result.headers().get(key);
        if (values != null && !values.isEmpty()) {
          value = values.size() == 1 ? values.getFirst() : values;
        }
      } else if (extract.fromJsonPointer() != null) {
        if (body == null) {
          body = tryParseJson(result.body());
        }
        if (body != null) {
          JsonNode selected = body.at(extract.fromJsonPointer());
          if (selected != null && !selected.isMissingNode() && !selected.isNull()) {
            value = jsonValue(selected);
          }
        }
      }

      if (value == null) {
        if (extract.required()) {
          context.logger().warn("Required extract missing for callId={} to={}", step.callId(), extract.to());
          return false;
        }
        continue;
      }
      if (extract.to() != null) {
        putPath(payload, extract.to(), value);
      }
    }
    return true;
  }

  private void applySetters(HttpSequenceWorkerConfig.Step step,
                            Map<String, Object> payload,
                            WorkItem workItem,
                            WorkerContext context) {
    if (step.set().isEmpty()) {
      return;
    }
    Map<String, Object> ctx = new java.util.HashMap<>();
    ctx.put("payload", payload);
    ctx.put("payloadAsJson", payload);
    ctx.put("ctx", payload);
    ctx.put("headers", workItem.headers());
    Object vars = workItem.headers().get("vars");
    if (vars != null) {
      ctx.put("vars", vars);
    }
    ctx.put("workItem", workItem);

    for (HttpSequenceWorkerConfig.SetValue setValue : step.set()) {
      if (setValue.to() == null || setValue.template() == null) {
        continue;
      }
      String rendered = templateRenderer.render(setValue.template(), ctx);
      Object finalValue = rendered;
      if (setValue.parseJson()) {
        Object parsed = tryParseObject(rendered);
        if (parsed != null) {
          finalValue = parsed;
        }
      }
      putPath(payload, setValue.to(), finalValue);
    }
  }

  private static void putPath(Map<String, Object> payload, String dottedPath, Object value) {
    String[] parts = dottedPath.split("\\.");
    Map<String, Object> current = payload;
    for (int i = 0; i < parts.length - 1; i++) {
      String part = parts[i];
      Object next = current.get(part);
      if (!(next instanceof Map<?, ?> nextMap)) {
        Map<String, Object> created = new java.util.LinkedHashMap<>();
        current.put(part, created);
        current = created;
      } else {
        @SuppressWarnings("unchecked")
        Map<String, Object> cast = (Map<String, Object>) nextMap;
        current = cast;
      }
    }
    current.put(parts[parts.length - 1], value);
  }

  private WorkItem appendResultStep(WorkItem current,
                                   WorkerContext context,
                                   int stepIndex,
                                   HttpSequenceWorkerConfig.Step step,
                                   Map<String, Object> payload,
                                   String serviceId,
                                   String callId,
                                   HttpCallExecutor.HttpCallResult result,
                                   long durationMs,
                                   int attempts,
                                   String sha256,
                                   String debugRef,
                                   String bodyPreview,
                                   String error) {
    Map<String, Object> stepHeaders = new LinkedHashMap<>();
    stepHeaders.put("x-ph-http-seq-step-index", stepIndex);
    if (step != null && step.id() != null) {
      stepHeaders.put("x-ph-http-seq-step-id", step.id());
    }
    stepHeaders.put("x-ph-http-seq-service-id", serviceId);
    stepHeaders.put("x-ph-http-seq-call-id", callId);
    stepHeaders.put("x-ph-http-seq-status", result.statusCode());
    stepHeaders.put("x-ph-http-seq-duration-ms", durationMs);
    stepHeaders.put("x-ph-http-seq-attempts", attempts);
    stepHeaders.put("x-ph-http-seq-sha256", sha256);
    stepHeaders.put("x-ph-http-seq-response-bytes", result.body() == null ? 0 : result.body().getBytes(StandardCharsets.UTF_8).length);
    if (bodyPreview != null && !bodyPreview.isEmpty()) {
      stepHeaders.put("x-ph-http-seq-body-preview", bodyPreview);
    }
    if (debugRef != null) {
      stepHeaders.put("x-ph-http-seq-debug-ref", debugRef);
    }
    if (result.error() != null) {
      stepHeaders.put("x-ph-http-seq-error", result.error());
    }
    if (error != null && !error.isBlank()) {
      stepHeaders.put("x-ph-http-seq-failure", error);
    }
    String nextPayload = serializePayload(payload);
    return current.addStep(context.info(), nextPayload, stepHeaders).toBuilder().contentType("application/json").build();
  }

  private WorkItem appendErrorStep(WorkItem current, WorkerContext context, int stepIndex, Map<String, Object> payload, String message) {
    Map<String, Object> stepHeaders = new LinkedHashMap<>();
    stepHeaders.put("x-ph-http-seq-step-index", stepIndex);
    stepHeaders.put("x-ph-http-seq-failure", message == null ? "unknown error" : message);
    String nextPayload = serializePayload(payload);
    return current.addStep(context.info(), nextPayload, stepHeaders).toBuilder().contentType("application/json").build();
  }

  private String serializePayload(Map<String, Object> payload) {
    try {
      return mapper.writeValueAsString(payload == null ? Map.of() : payload);
    } catch (Exception ex) {
      return "{}";
    }
  }

  private void reloadTemplatesIfNeeded(HttpSequenceWorkerConfig config) {
    String key = config.templateRoot() + "::" + config.serviceId();
    Map<String, TemplateDefinition> current = templates;
    if (current == null || !key.equals(lastTemplateConfigKey)) {
      Map<String, TemplateDefinition> loaded = templateLoader.load(config.templateRoot(), config.serviceId());
      templates = loaded;
      lastTemplateConfigKey = key;
    }
  }

  private Map<String, Object> parsePayloadAsMap(String payload) {
    if (payload == null || payload.isBlank()) {
      return null;
    }
    try {
      Object obj = mapper.readValue(payload, Object.class);
      if (obj instanceof Map<?, ?> map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> cast = (Map<String, Object>) map;
        return new java.util.LinkedHashMap<>(cast);
      }
      return null;
    } catch (Exception ignored) {
      return null;
    }
  }

  private JsonNode tryParseJson(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      return mapper.readTree(body);
    } catch (Exception ignored) {
      return null;
    }
  }

  private Object tryParseObject(String rendered) {
    if (rendered == null || rendered.isBlank()) {
      return null;
    }
    try {
      return mapper.readValue(rendered, Object.class);
    } catch (Exception ignored) {
      return null;
    }
  }

  private Object jsonValue(JsonNode node) {
    if (node.isTextual()) return node.asText();
    if (node.isNumber()) return node.numberValue();
    if (node.isBoolean()) return node.asBoolean();
    if (node.isObject() || node.isArray()) {
      try {
        return mapper.treeToValue(node, Object.class);
      } catch (Exception ignored) {
      }
    }
    return node.asText();
  }

  private static boolean shouldCapture(HttpSequenceWorkerConfig.DebugCapture capture, boolean isError) {
    if (capture == null) {
      return false;
    }
    return switch (capture.mode()) {
      case NONE -> false;
      case ALWAYS -> true;
      case ERROR_ONLY -> isError;
      case SAMPLE -> ThreadLocalRandom.current().nextDouble() < capture.samplePct();
    };
  }

  private static String sha256Hex(String body) {
    if (body == null) {
      return "";
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(body.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (Exception ignored) {
      return "";
    }
  }

  private static String preview(String body, int maxBytes) {
    if (body == null || body.isEmpty() || maxBytes <= 0) {
      return "";
    }
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= maxBytes) {
      return body;
    }
    return new String(bytes, 0, maxBytes, StandardCharsets.UTF_8);
  }

  private static final class RedisDebugCaptureStore {
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final io.lettuce.core.RedisClient client;
    private final ThreadLocal<io.lettuce.core.api.sync.RedisCommands<String, String>> commands;

    RedisDebugCaptureStore(ObjectMapper mapper, RedisSequenceProperties properties) {
      this.mapper = Objects.requireNonNull(mapper, "mapper");
      boolean canEnable = properties != null
          && properties.isEnabled()
          && properties.getHost() != null
          && !properties.getHost().isBlank();
      this.enabled = canEnable;

      if (!canEnable) {
        this.client = null;
        this.commands = null;
        return;
      }

      io.lettuce.core.RedisURI.Builder builder = io.lettuce.core.RedisURI.builder()
          .withHost(properties.getHost())
          .withPort(properties.getPort())
          .withSsl(properties.isSsl());
      if (properties.getUsername() != null && properties.getPassword() != null) {
        builder.withAuthentication(properties.getUsername(), properties.getPassword().toCharArray());
      } else if (properties.getPassword() != null) {
        builder.withPassword(properties.getPassword().toCharArray());
      }
      this.client = io.lettuce.core.RedisClient.create(builder.build());
      this.commands = ThreadLocal.withInitial(() -> client.connect().sync());
    }

    String store(WorkerInfo info,
                 String baseUrl,
                 String serviceId,
                 String callId,
                 HttpCallExecutor.RenderedCall request,
                 HttpCallExecutor.HttpCallResult result,
                 HttpSequenceWorkerConfig.DebugCapture capture) {
      if (!enabled) {
        return null;
      }
      String key = "ph:debug:http-seq:%s:%s:%s:%s".formatted(
          info.swarmId(), info.role(), info.instanceId(), java.util.UUID.randomUUID());
      ObjectNode node = mapper.createObjectNode();
      node.put("serviceId", serviceId);
      node.put("callId", callId);
      node.put("status", result.statusCode());
      if (result.error() != null) {
        node.put("error", result.error());
      }
      if (capture.includeHeaders()) {
        node.set("headers", mapper.valueToTree(result.headers()));
      }

      if (capture.includeRequest() && request != null) {
        ObjectNode req = mapper.createObjectNode();
        req.put("method", request.method());
        req.put("url", (baseUrl == null ? "" : baseUrl) + (request.path() == null ? "" : request.path()));
        req.set("headers", mapper.valueToTree(request.headers()));
        String requestBody = request.body() == null ? "" : request.body();
        req.put("body", truncateUtf8(requestBody, capture.maxBodyBytes()));
        node.set("request", req);
      }

      String body = result.body() == null ? "" : result.body();
      node.put("body", truncateUtf8(body, capture.maxBodyBytes()));
      String value = node.toString();
      try {
        commands.get().setex(key, capture.redisTtlSeconds(), value);
        return key;
      } catch (Exception ex) {
        return null;
      }
    }

    private static String truncateUtf8(String value, int maxBytes) {
      if (value == null || value.isEmpty() || maxBytes <= 0) {
        return "";
      }
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      if (bytes.length <= maxBytes) {
        return value;
      }
      return new String(bytes, 0, maxBytes, StandardCharsets.UTF_8);
    }
  }

  private record HttpCallAttempt(int attempts, long totalDurationMs, HttpCallExecutor.HttpCallResult result) {
  }
}
