package io.pockethive.trigger;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.testing.ControlPlaneTestFixtures;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TriggerWorkerImplTest {

  @Mock
  private HttpClient httpClient;

  @Mock
  private Logger logger;

  @Mock
  private HttpResponse<String> httpResponse;

  private TriggerWorkerProperties properties;
  private TriggerWorkerImpl worker;
  private static final WorkerControlPlaneProperties WORKER_PROPERTIES =
      ControlPlaneTestFixtures.workerProperties("swarm", "trigger", "instance");

  @BeforeEach
  void setUp() throws Exception {
    MockitoAnnotations.openMocks(this);
    properties = new TriggerWorkerProperties(new ObjectMapper(), WORKER_PROPERTIES);
    properties.setConfig(Map.of(
        "intervalMs", 500,
        "actionType", "rest",
        "command", "noop",
        "url", "https://example.com",
        "method", "POST",
        "body", "{}\n",
        "headers", Map.of("X-Test", "1")
    ));

    when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
        .thenReturn(httpResponse);
    when(httpResponse.statusCode()).thenReturn(200);
    when(httpResponse.body()).thenReturn("ok");
    when(httpResponse.headers()).thenReturn(HttpHeaders.of(Map.of(), (key, value) -> true));

    worker = new TriggerWorkerImpl(properties, httpClient);
  }

  @Test
  void generateUsesConfigFromContext() throws Exception {
    TriggerWorkerConfig config = new TriggerWorkerConfig(
        1000L,
        false,
        "rest",
        "noop",
        "https://service.test",
        "put",
        "{\"a\":1}",
        Map.of("X-Test", "yes")
    );
    WorkerContext context = new TestWorkerContext(config, logger);

    WorkItem result = worker.onMessage(WorkItem.text(context.info(), "").build(), context);

    assertThat(result).isNull();
    ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), any());
    HttpRequest request = requestCaptor.getValue();
    assertThat(request.uri().toString()).isEqualTo("https://service.test");
    assertThat(request.method()).isEqualTo("PUT");
  }

  @Test
  void failsWhenRuntimeConfigMissing() {
    WorkerContext context = new TestWorkerContext(null, logger);
    assertThatThrownBy(() -> worker.onMessage(WorkItem.text(context.info(), "").build(), context))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Missing runtime config for " + TriggerWorkerConfig.class.getName());
  }

  @Test
  void throwsWhenTriggerActionFails() throws Exception {
    TriggerWorkerConfig config = new TriggerWorkerConfig(
        1000L,
        false,
        "rest",
        "noop",
        "https://service.test",
        "post",
        "{\"a\":1}",
        Map.of("X-Test", "yes")
    );
    WorkerContext context = new TestWorkerContext(config, logger);
    when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
        .thenThrow(new RuntimeException("network down"));

    assertThatThrownBy(() -> worker.onMessage(WorkItem.text(context.info(), "").build(), context))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Trigger action failed");
  }

  @Test
  void restConfigDoesNotRequireShellCommand() throws Exception {
    TriggerWorkerConfig config = new TriggerWorkerConfig(
        1000L,
        false,
        "rest",
        null,
        "https://service.test",
        "post",
        "",
        Map.of()
    );
    WorkerContext context = new TestWorkerContext(config, logger);

    WorkItem result = worker.onMessage(WorkItem.text(context.info(), "").build(), context);

    assertThat(result).isNull();
    ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), any());
    assertThat(requestCaptor.getValue().method()).isEqualTo("POST");
  }

  @Test
  void shellConfigDoesNotRequireRestFieldsAndPublishesOnlyShellStatus() throws Exception {
    TriggerWorkerConfig config = new TriggerWorkerConfig(
        1000L,
        false,
        "shell",
        "printf ok",
        null,
        null,
        null,
        null
    );
    CapturingStatusPublisher statusPublisher = new CapturingStatusPublisher();
    WorkerContext context = new TestWorkerContext(config, logger, statusPublisher);

    WorkItem result = worker.onMessage(WorkItem.text(context.info(), "").build(), context);

    assertThat(result).isNull();
    assertThat(statusPublisher.data())
        .containsEntry("actionType", "shell")
        .containsEntry("command", "printf ok")
        .doesNotContainKeys("url", "method", "body", "headers");
  }

  @Test
  void shellConfigRequiresCommand() {
    assertThatThrownBy(() -> new TriggerWorkerConfig(
        1000L,
        false,
        "shell",
        null,
        null,
        null,
        null,
        null
    ))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("command must be provided");
  }

  private static final class TestWorkerContext implements WorkerContext {

    private final TriggerWorkerConfig config;
    private final Logger logger;
    private final StatusPublisher statusPublisher;

    private TestWorkerContext(TriggerWorkerConfig config, Logger logger) {
      this(config, logger, StatusPublisher.NO_OP);
    }

    private TestWorkerContext(TriggerWorkerConfig config, Logger logger, StatusPublisher statusPublisher) {
      this.config = config;
      this.logger = logger;
      this.statusPublisher = statusPublisher;
    }

    @Override
    public WorkerInfo info() {
      return new WorkerInfo("trigger", "swarm", "instance", null, null);
    }

    @Override
    public boolean enabled() {
      return true;
    }

    @Override
    public <C> C config(Class<C> type) {
      if (config != null && type.isAssignableFrom(TriggerWorkerConfig.class)) {
        return type.cast(config);
      }
      return null;
    }

    @Override
    public StatusPublisher statusPublisher() {
      return statusPublisher;
    }

    @Override
    public Logger logger() {
      return logger;
    }

    @Override
    public io.micrometer.core.instrument.MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }

    @Override
    public io.micrometer.observation.ObservationRegistry observationRegistry() {
      return io.micrometer.observation.ObservationRegistry.create();
    }

    @Override
    public io.pockethive.observability.ObservabilityContext observabilityContext() {
      return new io.pockethive.observability.ObservabilityContext();
    }
  }

  private static final class CapturingStatusPublisher implements StatusPublisher {

    private final Map<String, Object> data = new LinkedHashMap<>();

    @Override
    public void update(java.util.function.Consumer<MutableStatus> consumer) {
      consumer.accept(new MutableStatus() {
        @Override
        public MutableStatus data(String key, Object value) {
          if (value == null) {
            throw new IllegalArgumentException("status value must not be null: " + key);
          }
          data.put(key, value);
          return this;
        }
      });
    }

    private Map<String, Object> data() {
      return data;
    }
  }
}
