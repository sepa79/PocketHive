package io.pockethive.sink.clickhouse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ClickHouseJsonEachRowTransportTest {
  @Test
  void sendsExactRequestAndRetainsPreparedDestinationWithCurrentCredentialsAndTimeout() throws Exception {
    var settings = settings();
    var client = client(204, "");
    var transport = new ClickHouseJsonEachRowTransport(settings, client);
    var insert = transport.prepareInsert();
    settings.setEndpoint("http://other:8123");
    settings.setTable("other");
    settings.setUsername(" user ");
    settings.setPassword(" pass ");
    settings.setReadTimeoutMs(1234);
    insert.write(List.of("{\"text\":\"żółć\"}", "{\"n\":2}"));
    var captor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(client).send(captor.capture(), any());
    var request = captor.getValue();
    assertThat(request.uri().toString()).isEqualTo(
        "http://clickhouse:8123/?query=INSERT+INTO+db.events+FORMAT+JSONEachRow");
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.timeout()).contains(Duration.ofMillis(1234));
    assertThat(request.headers().firstValue("Content-Type")).contains("application/json");
    assertThat(request.headers().firstValue("Authorization")).contains("Basic dXNlcjpwYXNz");
    assertThat(body(request)).isEqualTo("{\"text\":\"żółć\"}\n{\"n\":2}\n");
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 201, 204, 299})
  void acceptsOnlySuccessfulResponsesWithoutAddingEmptyUsernameAuth(int status) throws Exception {
    var settings = settings();
    settings.setUsername("  ");
    settings.setPassword("ignored");
    var client = client(status, "ok");
    new ClickHouseJsonEachRowTransport(settings, client).prepareInsert().write(List.of("{}"));
    var request = ArgumentCaptor.forClass(HttpRequest.class);
    verify(client).send(request.capture(), any());
    assertThat(request.getValue().headers().firstValue("Authorization")).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(ints = {199, 300, 400, 500})
  void rejectsNon2xxAndBoundsFailureTextWithoutRetry(int status) throws Exception {
    var client = client(status, " " + "x".repeat(501) + " ");
    var insert = new ClickHouseJsonEachRowTransport(settings(), client).prepareInsert();
    assertThatThrownBy(() -> insert.write(List.of("{}")))
        .isInstanceOf(ClickHouseInsertException.class)
        .hasMessage("ClickHouse insert failed status=" + status + " body=" + "x".repeat(500) + "…");
    verify(client, times(1)).send(any(), any());
  }

  @Test
  void propagatesIoAndInterruptionWithoutRetry() throws Exception {
    var client = mock(HttpClient.class);
    var io = new IOException("connection lost");
    var interruption = new InterruptedException("stopping");
    when(client.send(any(), any())).thenThrow(io).thenThrow(interruption);
    var insert = new ClickHouseJsonEachRowTransport(settings(), client).prepareInsert();
    assertThatThrownBy(() -> insert.write(List.of("{}"))).isSameAs(io);
    assertThatThrownBy(() -> insert.write(List.of("{}"))).isSameAs(interruption);
    verify(client, times(2)).send(any(), any());
  }

  @Test
  void rejectsMalformedUriDuringPreparationBeforeSending() {
    var settings = settings();
    settings.setEndpoint("http://bad host:8123");
    var client = mock(HttpClient.class);
    var transport = new ClickHouseJsonEachRowTransport(settings, client);
    assertThatThrownBy(transport::prepareInsert).isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(client);
  }

  @Test
  void preservesNullResponseBodyAsEmptyDiagnostic() throws Exception {
    var transport = new ClickHouseJsonEachRowTransport(settings(), client(500, null));
    assertThatThrownBy(() -> transport.prepareInsert().write(List.of("{}")))
        .hasMessage("ClickHouse insert failed status=500 body=");
  }

  private static ClickHouseSinkProperties settings() {
    var properties = new ClickHouseSinkProperties();
    properties.setEndpoint(" http://clickhouse:8123/ ");
    properties.setTable(" db.events ");
    return properties;
  }

  @SuppressWarnings("unchecked")
  private static HttpClient client(int status, String body) throws Exception {
    var client = mock(HttpClient.class);
    HttpResponse<Object> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.body()).thenReturn(body);
    when(client.send(any(), any())).thenReturn(response);
    return client;
  }

  private static String body(HttpRequest request) throws Exception {
    var result = new CompletableFuture<String>();
    var bytes = new ByteArrayOutputStream();
    request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
      public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
      public void onNext(ByteBuffer buffer) {
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        bytes.writeBytes(chunk);
      }
      public void onError(Throwable error) { result.completeExceptionally(error); }
      public void onComplete() { result.complete(bytes.toString(StandardCharsets.UTF_8)); }
    });
    return result.get(5, java.util.concurrent.TimeUnit.SECONDS);
  }
}
