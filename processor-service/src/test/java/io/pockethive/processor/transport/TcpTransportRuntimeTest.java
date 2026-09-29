package io.pockethive.processor.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.processor.TcpTransportConfig;
import io.pockethive.processor.TcpTransportConfig.ConnectionReuse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TcpTransportRuntimeTest {
  private static final TcpRequest REQUEST = new TcpRequest("peer", 1234, new byte[]{1, 2}, Map.of("maxBytes", 17));

  @Test void replacementDoesNotCloseInFlightTransport() throws Exception {
    List<RecordingTransport> created = new ArrayList<>();
    try (var runtime = runtime(created)) {
      var firstConfig = config(ConnectionReuse.GLOBAL, 1234);
      var first = runtime.acquire(firstConfig);
      try (var second = runtime.acquire(config(ConnectionReuse.GLOBAL, 2345))) {
        assertThat(created.getFirst().closes).isZero();
        assertThat(first.config()).isEqualTo(firstConfig);
        assertThat(new String(first.execute(REQUEST, TcpBehavior.LENGTH_PREFIX_2B).body(), StandardCharsets.UTF_8))
            .isEqualTo("transport-1");
        assertThat(second.config().connectTimeoutMs()).isEqualTo(2345);
      }
      first.close();
      first.close();
      assertThat(created.getFirst().closes).isEqualTo(1);
      assertThat(created.getLast().closes).isZero();
    }
    assertThat(created).allSatisfy(t -> assertThat(t.closes).isEqualTo(1));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(ConnectionReuse.class)
  void failedReplacementDoesNotPublishConfigurationAndCanBeRetried(ConnectionReuse reuse) throws Exception {
    List<RecordingTransport> created = new ArrayList<>();
    var fail = new java.util.concurrent.atomic.AtomicBoolean();
    try (var runtime = new TcpTransportRuntime(c -> {
      if (fail.get()) throw new IllegalStateException("construction failed");
      var t = new RecordingTransport("transport-" + created.size(), c);
      created.add(t); return t;
    })) {
      var original = config(ConnectionReuse.GLOBAL, 1234);
      exchange(runtime, original);
      fail.set(true);
      var changed = config(reuse, 2345);
      assertThatThrownBy(() -> runtime.acquire(changed)).hasMessage("construction failed");
      assertThat(exchange(runtime, original)).isEqualTo("transport-0");
      assertThat(created.getFirst().closes).isZero();
      fail.set(false);
      assertThat(exchange(runtime, changed)).isEqualTo("transport-1");
      assertThat(created.getFirst().closes).isEqualTo(1);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(ConnectionReuse.class)
  void shutdownRejectsNewWorkButLetsExistingLeaseFinish(ConnectionReuse reuse) throws Exception {
    List<RecordingTransport> created = new ArrayList<>();
    var runtime = runtime(created);
    var config = config(reuse, 1234);
    var lease = runtime.acquire(config);
    runtime.close();
    runtime.close();
    assertThatThrownBy(() -> runtime.acquire(config)).isInstanceOf(IllegalStateException.class);
    lease.execute(REQUEST, TcpBehavior.LENGTH_PREFIX_2B);
    assertThat(created.getFirst().closes).isZero();
    lease.close();
    assertThat(created.getFirst().closes).isEqualTo(1);
    assertThatThrownBy(() -> lease.execute(REQUEST, TcpBehavior.LENGTH_PREFIX_2B))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test void noneClosesOnlyItsOwnTransportAndDoesNotMaskRequestError() throws Exception {
    List<RecordingTransport> created = new ArrayList<>();
    try (var runtime = runtime(created)) {
      var config = config(ConnectionReuse.NONE, 1234);
      var first = runtime.acquire(config);
      var second = runtime.acquire(config);
      created.getFirst().failure = new TcpException("request failed");
      created.getFirst().failClose = true;
      assertThatThrownBy(() -> { try (first) { first.execute(REQUEST, TcpBehavior.LENGTH_PREFIX_2B); } })
          .isSameAs(created.getFirst().failure);
      assertThat(created.getFirst().closes).isEqualTo(1);
      assertThat(created.getLast().closes).isZero();
      second.close();
      assertThat(created.getLast().closes).isEqualTo(1);
    }
  }

  @Test void perThreadResourcesAreReusedAndClosedAfterRetirement() throws Exception {
    List<RecordingTransport> created = new CopyOnWriteArrayList<>();
    try (var runtime = runtime(created);
         var first = Executors.newSingleThreadExecutor(); var second = Executors.newSingleThreadExecutor()) {
      var config = config(ConnectionReuse.PER_THREAD, 1234);
      String a = first.submit(() -> exchange(runtime, config)).get(5, TimeUnit.SECONDS);
      String b = second.submit(() -> exchange(runtime, config)).get(5, TimeUnit.SECONDS);
      assertThat(b).isNotEqualTo(a);
      assertThat(first.submit(() -> exchange(runtime, config)).get(5, TimeUnit.SECONDS)).isEqualTo(a);
      created.getFirst().failClose = true;
      exchange(runtime, config(ConnectionReuse.GLOBAL, 2345));
      assertThat(created.subList(0, 2)).allSatisfy(t -> assertThat(t.closes).isEqualTo(1));
    }
    assertThat(created).allSatisfy(t -> assertThat(t.closes).isEqualTo(1));
  }

  @Test void parallelReplacementKeepsAdmittedRequestAlive() throws Exception {
    List<RecordingTransport> created = new CopyOnWriteArrayList<>();
    try (var runtime = runtime(created); var executor = Executors.newSingleThreadExecutor()) {
      var first = runtime.acquire(config(ConnectionReuse.GLOBAL, 1234));
      executor.submit(() -> exchange(runtime, config(ConnectionReuse.PER_THREAD, 2345))).get(5, TimeUnit.SECONDS);
      assertThat(created.getFirst().closes).isZero();
      first.execute(REQUEST, TcpBehavior.LENGTH_PREFIX_2B);
      first.close();
      assertThat(created.getFirst().closes).isEqualTo(1);
    }
  }

  @Test void callerRetriesOnSameLeaseWithoutRuntimeRetry() throws Exception {
    List<RecordingTransport> created = new ArrayList<>();
    try (var runtime = runtime(created); var lease = runtime.acquire(config(ConnectionReuse.GLOBAL, 1234))) {
      var t = created.getFirst();
      t.failure = new TcpException("first attempt");
      assertThatThrownBy(() -> lease.execute(REQUEST, TcpBehavior.LENGTH_PREFIX_2B)).isSameAs(t.failure);
      assertThat(t.executions).isEqualTo(1);
      t.failure = null;
      lease.execute(REQUEST, TcpBehavior.LENGTH_PREFIX_2B);
      assertThat(t.executions).isEqualTo(2);
    }
  }

  private static TcpTransportRuntime runtime(List<RecordingTransport> created) {
    return new TcpTransportRuntime(config -> {
      RecordingTransport transport = new RecordingTransport("transport-" + (created.size() + 1), config);
      created.add(transport);
      return transport;
    });
  }

  private static String exchange(TcpTransportRuntime runtime, TcpTransportConfig config) throws TcpException {
    try (TcpTransportLease lease = runtime.acquire(config)) {
      return new String(lease.execute(REQUEST, TcpBehavior.LENGTH_PREFIX_2B).body(), StandardCharsets.UTF_8);
    }
  }

  private static TcpTransportConfig config(ConnectionReuse reuse, int timeout) {
    return new TcpTransportConfig("socket", timeout, 9876, 23456, false, 2, false, true, reuse, 2);
  }

  private static final class RecordingTransport implements TcpTransport {
    private final String id;
    private final TcpTransportConfig configuration;
    private int executions;
    private int closes;
    private boolean failClose;
    private TcpException failure;
    private TcpRequest request;
    private TcpBehavior behavior;

    private RecordingTransport(String id, TcpTransportConfig configuration) {
      this.id = id;
      this.configuration = configuration;
    }

    @Override
    public TcpResponse execute(TcpRequest request, TcpBehavior behavior) throws TcpException {
      executions++;
      this.request = request;
      this.behavior = behavior;
      if (failure != null) throw failure;
      return new TcpResponse(200, id.getBytes(StandardCharsets.UTF_8), 0);
    }

    @Override
    public void close() {
      closes++;
      if (failClose) throw new IllegalStateException("close failed");
    }
  }
}
