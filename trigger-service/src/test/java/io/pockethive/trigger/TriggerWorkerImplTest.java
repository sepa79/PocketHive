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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;

import static org.assertj.core.api.Assertions.assertThat;
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
  void fallsBackToDefaultsWhenConfigMissing() {
    WorkerContext context = new TestWorkerContext(null, logger);
    WorkItem result = worker.onMessage(WorkItem.text(context.info(), "").build(), context);
    assertThat(result).isNull();
  }

  private static final class TestWorkerContext implements WorkerContext {

    private final TriggerWorkerConfig config;
    private final Logger logger;

    private TestWorkerContext(TriggerWorkerConfig config, Logger logger) {
      this.config = config;
      this.logger = logger;
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
      return StatusPublisher.NO_OP;
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
}
