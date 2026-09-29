package io.pockethive.processor.http;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

import io.pockethive.processor.ProcessorWorkerConfig;
import io.pockethive.processor.TcpTransportConfig;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.junit.jupiter.api.Test;

class ApacheProcessorHttpClientLifecycleTest {
  private static ProcessorWorkerConfig config(ProcessorWorkerConfig.ConnectionReuse reuse) {
    return new ProcessorWorkerConfig("http://example", ProcessorWorkerConfig.Mode.RATE_PER_SEC,
        2, 10.0, reuse, true, 1000, true, TcpTransportConfig.defaults(), Map.of());
  }

  @Test void closesEagerAndLazyPerThreadClientsExactlyOnce() throws Exception {
    List<CloseableHttpClient> clients = new CopyOnWriteArrayList<>();
    var owner = new ApacheProcessorHttpClient((tls, reuse) -> {
      var client = mock(CloseableHttpClient.class);
      clients.add(client);
      return client;
    });
    try (var a = Executors.newSingleThreadExecutor(); var b = Executors.newSingleThreadExecutor()) {
      var config = config(ProcessorWorkerConfig.ConnectionReuse.PER_THREAD);
      a.submit(() -> owner.execute(new HttpGet("http://example"), response -> "ok", config)).get(5, TimeUnit.SECONDS);
      b.submit(() -> owner.execute(new HttpGet("http://example"), response -> "ok", config)).get(5, TimeUnit.SECONDS);
    }
    assertThat(clients).hasSize(6);
    owner.close();
    owner.close();
    for (var client : clients) verify(client).close();
    assertThatThrownBy(() -> owner.execute(new HttpGet("http://example"), response -> "ok",
        config(ProcessorWorkerConfig.ConnectionReuse.GLOBAL))).isInstanceOf(IllegalStateException.class);
  }

  @Test void failedConstructionClosesPreviouslyCreatedClients() throws Exception {
    var first = mock(CloseableHttpClient.class);
    var count = new java.util.concurrent.atomic.AtomicInteger();
    assertThatThrownBy(() -> new ApacheProcessorHttpClient((tls, reuse) -> {
      if (count.incrementAndGet() == 2) throw new IllegalStateException("construction failed");
      return first;
    })).hasMessage("construction failed");
    verify(first).close();
  }

  @Test void shutdownDefersCleanupUntilRequestFinishes() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    List<CloseableHttpClient> clients = new CopyOnWriteArrayList<>();
    var owner = new ApacheProcessorHttpClient((tls, reuse) -> {
      var client = mock(CloseableHttpClient.class);
      clients.add(client);
      return client;
    });
    doAnswer(call -> {
      entered.countDown();
      if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("request not released");
      return "complete";
    }).when(clients.getFirst()).execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class));
    try (var executor = Executors.newSingleThreadExecutor()) {
      var result = executor.submit(() -> owner.execute(new HttpGet("http://example"), response -> "ok",
          config(ProcessorWorkerConfig.ConnectionReuse.GLOBAL)));
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        owner.close();
        for (var client : clients) verify(client, never()).close();
      } finally { release.countDown(); }
      assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("complete");
      for (var client : clients) verify(client).close();
    }
  }
}
