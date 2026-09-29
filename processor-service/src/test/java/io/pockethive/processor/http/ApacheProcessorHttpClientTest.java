package io.pockethive.processor.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.pockethive.processor.ProcessorWorkerConfig;
import io.pockethive.processor.ProcessorWorkerConfig.ConnectionReuse;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class ApacheProcessorHttpClientTest {
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @ParameterizedTest
  @CsvSource({"GLOBAL,true,true", "PER_THREAD,true,true", "NONE,true,false",
      "GLOBAL,false,false", "PER_THREAD,false,false", "NONE,false,false"})
  void preservesConnectionReuseAndKeepAlivePrecedence(ConnectionReuse reuse, boolean keepAlive,
                                                       boolean expectReuse) throws Exception {
    startPortEcho();
    ProcessorHttpClient client = new ApacheProcessorHttpClient();
    var config = config(reuse, keepAlive, true);

    String first = get(client, target(), config);
    String second = get(client, target(), config);

    if (expectReuse) assertThat(second).isEqualTo(first);
    else assertThat(second).isNotEqualTo(first);
  }

  @ParameterizedTest
  @EnumSource(value = ConnectionReuse.class, names = {"GLOBAL", "PER_THREAD"})
  void perThreadPoolsStaySeparateWhileGlobalPoolSharesConnections(ConnectionReuse reuse) throws Exception {
    startPortEcho();
    ProcessorHttpClient client = new ApacheProcessorHttpClient();
    var config = config(reuse, true, true);
    try (var first = Executors.newSingleThreadExecutor(); var second = Executors.newSingleThreadExecutor()) {
      String a = first.submit(() -> get(client, target(), config)).get(5, TimeUnit.SECONDS);
      String b = second.submit(() -> get(client, target(), config)).get(5, TimeUnit.SECONDS);
      String again = first.submit(() -> get(client, target(), config)).get(5, TimeUnit.SECONDS);
      assertThat(again).isEqualTo(a);
      if (reuse == ConnectionReuse.GLOBAL) assertThat(b).isEqualTo(a);
      else assertThat(b).isNotEqualTo(a);
    }
  }

  @Test
  void forwardsRequestAndReturnsDecodedResponse() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicReference<String> received = new AtomicReference<>();
    server.createContext("/probe", exchange -> {
      received.set(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
          + exchange.getRequestHeaders().getFirst("X-Probe") + " "
          + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] body = "response-ż".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("X-Result", "accepted");
      exchange.sendResponseHeaders(201, body.length);
      try (var output = exchange.getResponseBody()) { output.write(body); }
    });
    server.start();
    ProcessorHttpClient client = new ApacheProcessorHttpClient();
    HttpUriRequestBase request = request("POST", target());
    request.addHeader("X-Probe", "present");
    request.setEntity(new StringEntity("body-ż", StandardCharsets.UTF_8));

    String body = client.execute(request, response -> {
      assertThat(response.getCode()).isEqualTo(201);
      assertThat(response.getFirstHeader("X-Result").getValue()).isEqualTo("accepted");
      return EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
    }, config(ConnectionReuse.GLOBAL, true, true));

    assertThat(body).isEqualTo("response-ż");
    assertThat(received.get()).isEqualTo("POST /probe present body-ż");
  }

  @Test
  void responseCallbackFailurePropagatesAndDoesNotPreventNextRequest() throws Exception {
    startPortEcho();
    ProcessorHttpClient client = new ApacheProcessorHttpClient();
    var config = config(ConnectionReuse.GLOBAL, true, true);
    IOException failure = new IOException("response decode failed");

    assertThatThrownBy(() -> client.execute(request("GET", target()), response -> { throw failure; }, config))
        .isSameAs(failure);
    assertThat(get(client, target(), config)).isNotBlank();
  }

  @ParameterizedTest
  @CsvSource({"GLOBAL,true,200", "PER_THREAD,true,8", "NONE,true,0",
      "GLOBAL,false,0", "PER_THREAD,false,0", "NONE,false,0"})
  void capacityProjectionKeepsExistingMeaning(ConnectionReuse reuse, boolean keepAlive, int expected) {
    ProcessorHttpClient client = new ApacheProcessorHttpClient();
    assertThat(client.maxConnections(config(reuse, keepAlive, true))).isEqualTo(expected);
  }

  @ParameterizedTest
  @EnumSource(ConnectionReuse.class)
  void sslVerifyRejectsUntrustedPeerWhileExplicitFalseAllowsIt(ConnectionReuse reuse) throws Exception {
    SelfSignedCertificate certificate = new SelfSignedCertificate("localhost");
    try {
      KeyStore store = KeyStore.getInstance("PKCS12");
      store.load(null, null);
      char[] password = "test-only".toCharArray();
      store.setKeyEntry("server", certificate.key(), password,
          new java.security.cert.Certificate[]{certificate.cert()});
      KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      keys.init(store, password);
      SSLContext tls = SSLContext.getInstance("TLS");
      tls.init(keys.getKeyManagers(), null, null);
      HttpsServer https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      https.setHttpsConfigurator(new HttpsConfigurator(tls));
      server = https;
      AtomicInteger received = new AtomicInteger();
      server.createContext("/probe", exchange -> {
        received.incrementAndGet();
        exchange.sendResponseHeaders(200, 2);
        try (var output = exchange.getResponseBody()) { output.write(new byte[]{'o', 'k'}); }
      });
      server.start();
      URI target = URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/probe");
      ProcessorHttpClient client = new ApacheProcessorHttpClient();

      assertThatThrownBy(() -> get(client, target, config(reuse, true, true))).isInstanceOf(IOException.class);
      assertThat(received.get()).isZero();
      assertThat(get(client, target, config(reuse, true, false))).isEqualTo("ok");
      assertThatThrownBy(() -> get(client, target, config(reuse, true, true))).isInstanceOf(IOException.class);
      assertThat(received.get()).isEqualTo(1);
    } finally {
      certificate.delete();
    }
  }

  @ParameterizedTest
  @CsvSource({"GLOBAL,true", "PER_THREAD,true", "NONE,true", "PER_THREAD,false"})
  void usesSystemProxyForActualRequests(ConnectionReuse reuse, boolean verify) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicReference<URI> received = new AtomicReference<>();
    server.createContext("/", exchange -> {
      received.set(exchange.getRequestURI());
      exchange.sendResponseHeaders(200, 2);
      try (var output = exchange.getResponseBody()) { output.write(new byte[]{'o', 'k'}); }
    });
    server.start();
    ProxySelector previous = ProxySelector.getDefault();
    ProxySelector.setDefault(new ProxySelector() {
      @Override public List<Proxy> select(URI uri) {
        return List.of(new Proxy(Proxy.Type.HTTP, server.getAddress()));
      }
      @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) {
        throw new AssertionError(failure);
      }
    });
    try {
      ProcessorHttpClient client = new ApacheProcessorHttpClient();
      URI target = URI.create("http://processor-proxy-test.invalid/probe?value=1");
      assertThat(get(client, target, config(reuse, true, verify))).isEqualTo("ok");
      assertThat(received.get()).isEqualTo(target);
    } finally {
      ProxySelector.setDefault(previous);
    }
  }

  private void startPortEcho() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/probe", exchange -> {
      byte[] body = Integer.toString(exchange.getRemoteAddress().getPort()).getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (var output = exchange.getResponseBody()) { output.write(body); }
    });
    server.start();
  }

  private URI target() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/probe");
  }

  private static String get(ProcessorHttpClient client, URI target, ProcessorWorkerConfig config) throws IOException {
    return client.execute(request("GET", target),
        response -> EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8), config);
  }

  private static HttpUriRequestBase request(String method, URI target) {
    HttpUriRequestBase request = new HttpUriRequestBase(method, target);
    request.setConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(2))
        .setResponseTimeout(Timeout.ofSeconds(2)).build());
    return request;
  }

  private static ProcessorWorkerConfig config(ConnectionReuse reuse, boolean keepAlive, boolean verify) {
    return new ProcessorWorkerConfig("http://processor-test.invalid", ProcessorWorkerConfig.Mode.THREAD_COUNT,
        8, null, reuse, keepAlive, 5000, verify, null);
  }
}
