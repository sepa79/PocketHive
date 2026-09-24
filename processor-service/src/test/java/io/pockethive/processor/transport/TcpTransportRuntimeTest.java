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

  @Test
  void globalLeaseReusesTransportAndEqualConfigurationDoesNotCloseIt() throws Exception {
    List<RecordingTransport> created = new ArrayList<>();
    TcpTransportRuntime runtime = runtime(created);
    TcpTransportConfig config = config(ConnectionReuse.GLOBAL, 1234);
    runtime.configure(config);

    assertThat(exchange(runtime, config)).isEqualTo("transport-1");
    runtime.configure(config(ConnectionReuse.GLOBAL, 1234));
    assertThat(exchange(runtime, config)).isEqualTo("transport-1");
    assertThat(runtime.currentConfig()).isEqualTo(config);
    assertThat(created).hasSize(1);
    assertThat(created.getFirst().executions).isEqualTo(2);
    assertThat(created.getFirst().closes).isZero();
    assertThat(created.getFirst().configuration).isEqualTo(config);
    assertThat(created.getFirst().request).isSameAs(REQUEST);
    assertThat(created.getFirst().behavior).isEqualTo(TcpBehavior.LENGTH_PREFIX_2B);
  }

  @Test
  void noneClosesEachLeaseAfterUseAndReconfigurationClosesTheEagerTransport() throws Exception {
    List<RecordingTransport> created = new ArrayList<>();
    TcpTransportRuntime runtime = runtime(created);
    TcpTransportConfig config = config(ConnectionReuse.NONE, 1234);
    runtime.configure(config);

    assertThat(exchange(runtime, config)).isEqualTo("transport-2");
    assertThat(exchange(runtime, config)).isEqualTo("transport-3");
    assertThat(created).extracting(t -> t.closes).containsExactly(0, 1, 1);
    runtime.configure(config(ConnectionReuse.NONE, 2345));
    assertThat(created).extracting(t -> t.closes).containsExactly(1, 1, 1, 0);
  }

  @Test
  void perThreadTransportsRemainSeparateAndReplacementReleasesEveryOldTransport() throws Exception {
    List<RecordingTransport> created = new CopyOnWriteArrayList<>();
    TcpTransportRuntime runtime = runtime(created);
    TcpTransportConfig config = config(ConnectionReuse.PER_THREAD, 1234);
    runtime.configure(config);
    try (var first = Executors.newSingleThreadExecutor(); var second = Executors.newSingleThreadExecutor()) {
      String a = first.submit(() -> exchange(runtime, config)).get(5, TimeUnit.SECONDS);
      String b = second.submit(() -> exchange(runtime, config)).get(5, TimeUnit.SECONDS);
      assertThat(b).isNotEqualTo(a);
      assertThat(first.submit(() -> exchange(runtime, config)).get(5, TimeUnit.SECONDS)).isEqualTo(a);
      assertThat(created).extracting(t -> t.closes).containsExactly(0, 0, 0);

      TcpTransportConfig changed = config(ConnectionReuse.PER_THREAD, 2345);
      runtime.configure(changed);
      assertThat(created).extracting(t -> t.closes).containsExactly(1, 1, 1, 0);
      assertThat(first.submit(() -> exchange(runtime, changed)).get(5, TimeUnit.SECONDS)).isNotEqualTo(a);
      assertThat(second.submit(() -> exchange(runtime, changed)).get(5, TimeUnit.SECONDS)).isNotEqualTo(b);
      assertThat(created.subList(3, created.size())).allSatisfy(t -> assertThat(t.configuration).isEqualTo(changed));
    }
  }

  @Test
  void failedCloseDoesNotPreventOtherReleasesOrUseOfReplacement() throws Exception {
    List<RecordingTransport> created = new ArrayList<>();
    TcpTransportRuntime runtime = runtime(created);
    TcpTransportConfig config = config(ConnectionReuse.PER_THREAD, 1234);
    runtime.configure(config);
    exchange(runtime, config);
    created.forEach(t -> t.failClose = true);

    TcpTransportConfig changed = config(ConnectionReuse.GLOBAL, 2345);
    runtime.configure(changed);

    assertThat(created).extracting(t -> t.closes).containsExactly(1, 1, 0);
    assertThat(exchange(runtime, changed)).isEqualTo("transport-3");
  }

  @Test
  void leaseDoesNotRetryAndItsReleaseDoesNotMaskExchangeFailure() throws Exception {
    List<RecordingTransport> created = new ArrayList<>();
    TcpTransportRuntime runtime = runtime(created);
    TcpTransportConfig config = config(ConnectionReuse.NONE, 1234);
    runtime.configure(config);
    TcpTransportLease lease = runtime.acquire(config);
    RecordingTransport transport = created.getLast();
    TcpException failure = new TcpException("exchange failed");
    transport.failure = failure;
    transport.failClose = true;

    assertThatThrownBy(() -> {
      try (lease) { lease.execute(REQUEST, TcpBehavior.LENGTH_PREFIX_2B); }
    }).isSameAs(failure);
    assertThat(transport.executions).isEqualTo(1);
    assertThat(transport.closes).isEqualTo(1);
  }

  @Test
  void callerCanRetryThroughTheSameLease() throws Exception {
    List<RecordingTransport> created = new ArrayList<>();
    TcpTransportRuntime runtime = runtime(created);
    TcpTransportConfig config = config(ConnectionReuse.NONE, 1234);
    runtime.configure(config);
    try (TcpTransportLease lease = runtime.acquire(config)) {
      RecordingTransport transport = created.getLast();
      transport.failure = new TcpException("first attempt");
      assertThatThrownBy(() -> lease.execute(REQUEST, TcpBehavior.LENGTH_PREFIX_2B)).isSameAs(transport.failure);
      transport.failure = null;
      assertThat(new String(lease.execute(REQUEST, TcpBehavior.LENGTH_PREFIX_2B).body(), StandardCharsets.UTF_8))
          .isEqualTo("transport-2");
      assertThat(transport.executions).isEqualTo(2);
      assertThat(transport.closes).isZero();
    }
    assertThat(created.getLast().closes).isEqualTo(1);
  }

  @Test
  void missingConfigurationDoesNotReplaceAcceptedTransports() throws Exception {
    List<RecordingTransport> created = new ArrayList<>();
    TcpTransportRuntime runtime = runtime(created);
    TcpTransportConfig config = config(ConnectionReuse.GLOBAL, 1234);
    runtime.configure(config);

    assertThatThrownBy(() -> runtime.configure(null)).isInstanceOf(NullPointerException.class);
    assertThat(runtime.currentConfig()).isEqualTo(config);
    assertThat(exchange(runtime, config)).isEqualTo("transport-1");
    assertThat(created.getFirst().closes).isZero();
  }

  @Test
  void protocolRuntimeInstancesDoNotShareTransportsOrReconfiguration() throws Exception {
    List<RecordingTransport> created = new ArrayList<>();
    TcpTransportRuntime tcp = runtime(created);
    TcpTransportRuntime iso = runtime(created);
    TcpTransportConfig config = config(ConnectionReuse.GLOBAL, 1234);
    tcp.configure(config);
    iso.configure(config);

    assertThat(exchange(tcp, config)).isEqualTo("transport-1");
    assertThat(exchange(iso, config)).isEqualTo("transport-2");
    tcp.configure(config(ConnectionReuse.GLOBAL, 2345));
    assertThat(created.get(1).closes).isZero();
    assertThat(exchange(iso, config)).isEqualTo("transport-2");
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
