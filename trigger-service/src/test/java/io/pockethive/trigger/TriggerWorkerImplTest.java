package io.pockethive.trigger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

  private TriggerDefaults defaults;
  private TriggerWorkerImpl worker;

  @BeforeEach
  void setUp() throws Exception {
    MockitoAnnotations.openMocks(this);
    defaults = new TriggerDefaults();
    defaults.setEnabled(true);
    defaults.setIntervalMs(500L);
    defaults.setActionType("rest");
    defaults.setUrl("https://example.com");
    defaults.setMethod("POST");
    defaults.setBody("{}\n");
    defaults.setHeaders(Map.of("X-Test", "1"));

    when(httpClient.send(any(HttpRequest.class), any()))
        .thenReturn(httpResponse);
    when(httpResponse.statusCode()).thenReturn(200);
    when(httpResponse.body()).thenReturn("ok");
    when(httpResponse.headers()).thenReturn(HttpHeaders.of(Map.of(), (key, value) -> true));

    worker = new TriggerWorkerImpl(defaults, httpClient);
  }

  @Test
  void generateUsesConfigFromContext() throws Exception {
    TriggerWorkerConfig config = new TriggerWorkerConfig(
        true,
        1000L,
        false,
        "rest",
        "",
        "https://service.test",
        "put",
        "{\"a\":1}",
        Map.of("X-Test", "yes")
    );
    WorkerContext context = new TestWorkerContext(config, logger);

    WorkResult result = worker.generate(context);

    assertThat(result).isEqualTo(WorkResult.none());
    ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), any());
    HttpRequest request = requestCaptor.getValue();
    assertThat(request.uri().toString()).isEqualTo("https://service.test");
    assertThat(request.method()).isEqualTo("PUT");
  }

  @Test
  void fallsBackToDefaultsWhenConfigMissing() {
    WorkerContext context = new TestWorkerContext(null, logger);
    WorkResult result = worker.generate(context);
    assertThat(result).isEqualTo(WorkResult.none());
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
    public <C> Optional<C> config(Class<C> type) {
      if (config != null && type.isAssignableFrom(TriggerWorkerConfig.class)) {
        return Optional.of(type.cast(config));
      }
      return Optional.empty();
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
