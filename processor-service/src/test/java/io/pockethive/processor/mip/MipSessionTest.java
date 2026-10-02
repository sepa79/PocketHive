package io.pockethive.processor.mip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.iso8583.Iso8583Message;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(10)
class MipSessionTest {
  private static final byte[] REQUEST_PAYLOAD = {1, 2, 3};
  private static final byte[] RESPONSE_PAYLOAD = {4, 5, 6};

  @ParameterizedTest
  @EnumSource(value = MipMessageType.class, names = {"AUTHORIZATION_REQUEST", "NETWORK_REQUEST"})
  void onlyMatchingResponseFamilyAndStanCompleteTheExchange(MipMessageType type) throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var session = new MipSession(2)) {
      var peer = attach(session, "peer-1");
      var exchange = exchange(executor, session, type, "123456", 2_000);
      assertThat(peer.takeWrite()).containsExactly(REQUEST_PAYLOAD);
      var wrongType = type == MipMessageType.AUTHORIZATION_REQUEST
          ? MipMessageType.NETWORK_RESPONSE : MipMessageType.AUTHORIZATION_RESPONSE;
      assertThat(session.receive(peer.id(), response(wrongType, "123456"), RESPONSE_PAYLOAD)).isFalse();
      assertThat(session.receive(peer.id(), response(type.expectedResponse(), "654321"), RESPONSE_PAYLOAD)).isFalse();
      assertThat(exchange.isDone()).isFalse();
      var decoded = response(type.expectedResponse(), "123456");
      assertThat(session.receive(peer.id(), decoded, RESPONSE_PAYLOAD)).isTrue();
      assertThat(exchange.get(2, TimeUnit.SECONDS).decoded()).isEqualTo(decoded);
      assertThat(exchange.get().payload()).containsExactly(RESPONSE_PAYLOAD);
      assertThat(session.receive(peer.id(), decoded, RESPONSE_PAYLOAD)).isFalse();
    }
  }

  @Test
  void responseDuringSendFindsTheReservationButWaitsForWriteSuccess() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var session = new MipSession(1)) {
      var peer = attach(session, "peer-1");
      peer.writeResult = new CompletableFuture<>();
      peer.onSend = ignored -> assertThat(session.receive(peer.id(),
          response(MipMessageType.AUTHORIZATION_RESPONSE, "000001"), RESPONSE_PAYLOAD)).isTrue();
      var exchange = exchange(executor, session, MipMessageType.AUTHORIZATION_REQUEST, "000001", 2_000);
      peer.takeWrite();
      assertThat(exchange.isDone()).isFalse();
      peer.writeResult.complete(null);
      assertThat(exchange.get(2, TimeUnit.SECONDS).payload()).containsExactly(RESPONSE_PAYLOAD);
    }
  }

  @Test
  void writeFailureWinsEvenWhenTheCorrelatedResponseWasAlreadyReceived() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var session = new MipSession(1)) {
      var peer = attach(session, "peer-1");
      peer.writeResult = new CompletableFuture<>();
      var exchange = exchange(executor, session, MipMessageType.AUTHORIZATION_REQUEST, "000001", 2_000);
      peer.takeWrite();
      assertThat(session.receive(peer.id(), response(MipMessageType.AUTHORIZATION_RESPONSE, "000001"),
          RESPONSE_PAYLOAD)).isTrue();
      IOException failedWrite = new IOException("write failed");
      peer.writeResult.completeExceptionally(failedWrite);
      assertThatThrownBy(() -> exchange.get(2, TimeUnit.SECONDS)).hasRootCause(failedWrite);
      assertThat(session.state()).isEqualTo(MipSessionState.NEW);
      assertThat(peer.closes).hasValue(1);
    }
  }

  @Test
  void synchronousWriteFailureDetachesAndFailsTheReservedCall() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var session = new MipSession(1)) {
      var peer = attach(session, "peer-1");
      var failure = new IllegalStateException("submission failed");
      peer.onSend = ignored -> { throw failure; };
      var exchange = exchange(executor, session, MipMessageType.NETWORK_REQUEST, "000001", 2_000);
      peer.takeWrite();
      assertThatThrownBy(() -> exchange.get(2, TimeUnit.SECONDS)).hasRootCause(failure);
      assertThat(session.peerFor(peer.id())).isNull();
      assertThat(peer.closes).hasValue(1);
    }
  }

  @Test
  void waitingWorkUsesThePeerWhenItConnects() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var session = new MipSession(1)) {
      var started = new CountDownLatch(1);
      var exchange = executor.submit(() -> {
        started.countDown();
        return session.exchange(REQUEST_PAYLOAD, request(MipMessageType.NETWORK_REQUEST, "000001"), 2_000);
      });
      assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(session.state()).isEqualTo(MipSessionState.NEW);
      var peer = attach(session, "peer-1");
      peer.takeWrite();
      session.receive(peer.id(), response(MipMessageType.NETWORK_RESPONSE, "000001"), RESPONSE_PAYLOAD);
      assertThat(exchange.get(2, TimeUnit.SECONDS).decoded().mti()).isEqualTo(MipMessageType.NETWORK_RESPONSE.value());
    }
  }

  @Test
  void absentPeerTimesOutWithoutAnyTransportEffects() {
    try (var session = new MipSession(1)) {
      assertThatThrownBy(() -> session.exchange(REQUEST_PAYLOAD,
          request(MipMessageType.AUTHORIZATION_REQUEST, "000001"), 20))
          .isInstanceOf(TimeoutException.class).hasMessageContaining("waiting for a peer");
      assertThat(session.state()).isEqualTo(MipSessionState.NEW);
    }
  }

  @Test
  void responseCannotCompleteAnExchangeWhoseWriteTimesOut() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var session = new MipSession(1)) {
      var peer = attach(session, "peer-1");
      peer.writeResult = new CompletableFuture<>();
      var exchange = exchange(executor, session, MipMessageType.AUTHORIZATION_REQUEST, "000001", 250);
      peer.takeWrite();
      session.receive(peer.id(), response(MipMessageType.AUTHORIZATION_RESPONSE, "000001"), RESPONSE_PAYLOAD);
      assertThatThrownBy(() -> exchange.get(2, TimeUnit.SECONDS)).hasRootCauseInstanceOf(TimeoutException.class);
      peer.writeResult.complete(null);
      assertThat(session.receive(peer.id(), response(MipMessageType.AUTHORIZATION_RESPONSE, "000001"),
          RESPONSE_PAYLOAD)).isFalse();
      assertThatThrownBy(() -> session.exchange(REQUEST_PAYLOAD,
          request(MipMessageType.AUTHORIZATION_REQUEST, "000001"), 1_000))
          .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be reused");
    }
  }

  @Test
  void timedOutStanCannotBeReusedAndLateReplyDoesNotCompleteAnotherCall() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var session = new MipSession(1)) {
      var peer = attach(session, "peer-1");
      var timedOut = exchange(executor, session, MipMessageType.AUTHORIZATION_REQUEST, "000001", 250);
      peer.takeWrite();
      assertThatThrownBy(() -> timedOut.get(2, TimeUnit.SECONDS)).hasRootCauseInstanceOf(TimeoutException.class);
      assertThatThrownBy(() -> session.exchange(REQUEST_PAYLOAD,
          request(MipMessageType.NETWORK_REQUEST, "000001"), 1_000))
          .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be reused");
      var next = exchange(executor, session, MipMessageType.AUTHORIZATION_REQUEST, "000002", 2_000);
      peer.takeWrite();
      assertThat(session.receive(peer.id(), response(MipMessageType.AUTHORIZATION_RESPONSE, "000001"),
          RESPONSE_PAYLOAD)).isFalse();
      assertThat(next.isDone()).isFalse();
      session.receive(peer.id(), response(MipMessageType.AUTHORIZATION_RESPONSE, "000002"), RESPONSE_PAYLOAD);
      assertThat(next.get(2, TimeUnit.SECONDS).decoded().field(11)).isEqualTo("000002");
    }
  }

  @Test
  void successfulStanCannotBeReusedForEitherRequestFamily() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var session = new MipSession(1)) {
      var peer = attach(session, "peer-1");
      var first = exchange(executor, session, MipMessageType.NETWORK_REQUEST, "999999", 2_000);
      peer.takeWrite();
      session.receive(peer.id(), response(MipMessageType.NETWORK_RESPONSE, "999999"), RESPONSE_PAYLOAD);
      first.get(2, TimeUnit.SECONDS);
      assertThatThrownBy(() -> session.exchange(REQUEST_PAYLOAD,
          request(MipMessageType.AUTHORIZATION_REQUEST, "999999"), 1_000))
          .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be reused");
    }
  }

  @Test
  void boundedCapacityIsFreedByCompletionAndRejectedAdmissionDoesNotConsumeStan() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var session = new MipSession(1)) {
      var peer = attach(session, "peer-1");
      var first = exchange(executor, session, MipMessageType.AUTHORIZATION_REQUEST, "000001", 2_000);
      peer.takeWrite();
      assertThatThrownBy(() -> session.exchange(REQUEST_PAYLOAD,
          request(MipMessageType.AUTHORIZATION_REQUEST, "000002"), 1_000))
          .isInstanceOf(IllegalStateException.class).hasMessageContaining("capacity");
      session.receive(peer.id(), response(MipMessageType.AUTHORIZATION_RESPONSE, "000001"), RESPONSE_PAYLOAD);
      first.get(2, TimeUnit.SECONDS);
      var second = exchange(executor, session, MipMessageType.AUTHORIZATION_REQUEST, "000002", 2_000);
      peer.takeWrite();
      session.receive(peer.id(), response(MipMessageType.AUTHORIZATION_RESPONSE, "000002"), RESPONSE_PAYLOAD);
      assertThat(second.get(2, TimeUnit.SECONDS).decoded().field(11)).isEqualTo("000002");
    }
  }

  @Test
  void secondPeerIsClosedWithoutReplacingTheAcceptedConnection() throws Exception {
    try (var session = new MipSession(1)) {
      var first = attach(session, "peer-1");
      var second = new RecordingPeer("peer-2");
      assertThat(session.attach(second)).isFalse();
      assertThat(second.closes).hasValue(1);
      assertThat(first.closes).hasValue(0);
      assertThat(session.state()).isEqualTo(MipSessionState.CONNECTED);
      assertThat(session.peerFor(first.id())).isSameAs(first);
      assertThat(session.peerFor(second.id())).isNull();
    }
  }

  @Test
  void disconnectFailsEveryPendingCallAndOldCallbacksCannotAffectAReplacement() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var session = new MipSession(2)) {
      var oldPeer = attach(session, "peer-1");
      oldPeer.writeResult = new CompletableFuture<>();
      var authorization = exchange(executor, session, MipMessageType.AUTHORIZATION_REQUEST, "000001", 2_000);
      oldPeer.takeWrite();
      var network = exchange(executor, session, MipMessageType.NETWORK_REQUEST, "000002", 2_000);
      oldPeer.takeWrite();
      IOException disconnect = new IOException("connection lost");
      session.detach(oldPeer.id(), disconnect);
      assertThatThrownBy(() -> authorization.get(2, TimeUnit.SECONDS)).hasRootCause(disconnect);
      assertThatThrownBy(() -> network.get(2, TimeUnit.SECONDS)).hasRootCause(disconnect);
      assertThat(oldPeer.closes).hasValue(1);
      var replacement = attach(session, "peer-2");
      var next = exchange(executor, session, MipMessageType.AUTHORIZATION_REQUEST, "000001", 2_000);
      replacement.takeWrite();
      session.detach(oldPeer.id(), new IOException("stale detach"));
      oldPeer.writeResult.completeExceptionally(new IOException("stale write failure"));
      assertThat(session.receive(oldPeer.id(), response(MipMessageType.AUTHORIZATION_RESPONSE, "000001"),
          RESPONSE_PAYLOAD)).isFalse();
      assertThat(next.isDone()).isFalse();
      assertThat(session.peerFor(replacement.id())).isSameAs(replacement);
      assertThat(replacement.closes).hasValue(0);
      session.receive(replacement.id(), response(MipMessageType.AUTHORIZATION_RESPONSE, "000001"), RESPONSE_PAYLOAD);
      assertThat(next.get(2, TimeUnit.SECONDS).decoded().field(11)).isEqualTo("000001");
    }
  }

  @Test
  void interruptionSettlesTheCallFreesCapacityAndRetainsItsStan() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var session = new MipSession(1)) {
      var peer = attach(session, "peer-1");
      var failure = new AtomicReference<Throwable>();
      var interrupted = new AtomicBoolean();
      var done = new CountDownLatch(1);
      var thread = Thread.ofVirtual().start(() -> {
        try {
          session.exchange(REQUEST_PAYLOAD, request(MipMessageType.AUTHORIZATION_REQUEST, "000001"), 2_000);
        } catch (Throwable error) {
          failure.set(error);
          interrupted.set(Thread.currentThread().isInterrupted());
        } finally {
          done.countDown();
        }
      });
      peer.takeWrite();
      thread.interrupt();
      assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(failure.get()).isInstanceOf(InterruptedException.class);
      assertThat(interrupted).isTrue();
      assertThat(session.receive(peer.id(), response(MipMessageType.AUTHORIZATION_RESPONSE, "000001"),
          RESPONSE_PAYLOAD)).isFalse();
      assertThatThrownBy(() -> session.exchange(REQUEST_PAYLOAD,
          request(MipMessageType.AUTHORIZATION_REQUEST, "000001"), 1_000))
          .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be reused");
      var next = exchange(executor, session, MipMessageType.NETWORK_REQUEST, "000002", 2_000);
      peer.takeWrite();
      session.receive(peer.id(), response(MipMessageType.NETWORK_RESPONSE, "000002"), RESPONSE_PAYLOAD);
      next.get(2, TimeUnit.SECONDS);
    }
  }

  @Test
  void interruptionDuringPeerWaitHasNoReservationToLeak() throws Exception {
    try (var session = new MipSession(1)) {
      var started = new CountDownLatch(1);
      var done = new CountDownLatch(1);
      var failure = new AtomicReference<Throwable>();
      var interrupted = new AtomicBoolean();
      var thread = Thread.ofVirtual().start(() -> {
        started.countDown();
        try {
          session.exchange(REQUEST_PAYLOAD, request(MipMessageType.AUTHORIZATION_REQUEST, "000001"), 2_000);
        } catch (Throwable error) {
          failure.set(error);
          interrupted.set(Thread.currentThread().isInterrupted());
        } finally {
          done.countDown();
        }
      });
      assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
      thread.interrupt();
      assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(failure.get()).isInstanceOf(InterruptedException.class);
      assertThat(interrupted).isTrue();
      assertThat(session.state()).isEqualTo(MipSessionState.NEW);
    }
  }

  @Test
  void shutdownFailsPendingCallsRejectsNewWorkAndClosesThePeerOnce() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var session = new MipSession(1)) {
      var peer = attach(session, "peer-1");
      peer.writeResult = new CompletableFuture<>();
      var exchange = exchange(executor, session, MipMessageType.AUTHORIZATION_REQUEST, "000001", 2_000);
      peer.takeWrite();
      session.close();
      session.close();
      assertThatThrownBy(() -> exchange.get(2, TimeUnit.SECONDS)).hasRootCauseMessage("MIP session is closed");
      peer.writeResult.complete(null);
      assertThat(session.state()).isEqualTo(MipSessionState.CLOSED);
      assertThat(peer.closes).hasValue(1);
      assertThat(session.receive(peer.id(), response(MipMessageType.AUTHORIZATION_RESPONSE, "000001"),
          RESPONSE_PAYLOAD)).isFalse();
      assertThatThrownBy(() -> session.exchange(REQUEST_PAYLOAD,
          request(MipMessageType.AUTHORIZATION_REQUEST, "000002"), 1_000))
          .isInstanceOf(IllegalStateException.class).hasMessage("MIP session is closed");
      var replacement = new RecordingPeer("peer-2");
      assertThat(session.attach(replacement)).isFalse();
      assertThat(replacement.closes).hasValue(1);
    }
  }

  @Test
  void shutdownWakesWorkThatWasWaitingForTheFirstConnection() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var session = new MipSession(1)) {
      var started = new CountDownLatch(1);
      var exchange = executor.submit(() -> {
        started.countDown();
        return session.exchange(REQUEST_PAYLOAD, request(MipMessageType.NETWORK_REQUEST, "000001"), 2_000);
      });
      assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
      session.close();
      assertThatThrownBy(() -> exchange.get(2, TimeUnit.SECONDS)).hasRootCauseMessage("MIP session is closed");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "00001", "0000001", "00 001", "00a001", "０００００１"})
  void outgoingStanRequiresExactlySixAsciiDigits(String stan) {
    try (var session = new MipSession(1)) {
      var peer = attach(session, "peer-1");
      assertThatThrownBy(() -> session.exchange(REQUEST_PAYLOAD,
          request(MipMessageType.AUTHORIZATION_REQUEST, stan), 1_000))
          .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DE11");
      assertThat(peer.sent).isEmpty();
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0x0110, 0x0810, 0x0200})
  void responseAndUnsupportedMtiCannotBeSentAsRequests(int mti) {
    try (var session = new MipSession(1)) {
      var peer = attach(session, "peer-1");
      assertThatThrownBy(() -> session.exchange(REQUEST_PAYLOAD,
          new Iso8583Message(mti, Map.of(11, "000001")), 1_000))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(peer.sent).isEmpty();
    }
  }

  @Test
  void missingRequiredResponseFieldsFailExplicitlyAtTheSessionBoundary() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var session = new MipSession(1)) {
      var peer = attach(session, "peer-1");
      var exchange = exchange(executor, session, MipMessageType.AUTHORIZATION_REQUEST, "000001", 2_000);
      peer.takeWrite();
      assertThatThrownBy(() -> session.receive(peer.id(),
          new Iso8583Message(MipMessageType.AUTHORIZATION_RESPONSE.value(), Map.of(39, "00")), RESPONSE_PAYLOAD))
          .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DE11");
      assertThatThrownBy(() -> session.receive(peer.id(),
          new Iso8583Message(MipMessageType.AUTHORIZATION_RESPONSE.value(), Map.of(11, "000001")), RESPONSE_PAYLOAD))
          .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DE39");
      var malformed = new IllegalArgumentException("malformed response");
      session.detach(peer.id(), malformed);
      assertThatThrownBy(() -> exchange.get(2, TimeUnit.SECONDS)).hasRootCause(malformed);
    }
  }

  @Test
  void networkResponseRequiresItsRequestCodeAndAResponseCode() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var session = new MipSession(1)) {
      var peer = attach(session, "peer-1");
      var exchange = exchange(executor, session, MipMessageType.NETWORK_REQUEST, "000001", 2_000);
      peer.takeWrite();
      assertThatThrownBy(() -> session.receive(peer.id(),
          new Iso8583Message(MipMessageType.NETWORK_RESPONSE.value(), Map.of(11, "000001", 70, "270")),
          RESPONSE_PAYLOAD)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DE39");
      assertThatThrownBy(() -> session.receive(peer.id(),
          new Iso8583Message(MipMessageType.NETWORK_RESPONSE.value(), Map.of(11, "000001", 39, "00", 70, "081")),
          RESPONSE_PAYLOAD)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DE70");
      assertThatThrownBy(() -> session.receive(peer.id(),
          new Iso8583Message(MipMessageType.NETWORK_RESPONSE.value(), Map.of(11, "000001", 39, "00")),
          RESPONSE_PAYLOAD)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DE70");
      assertThat(exchange.isDone()).isFalse();
      session.receive(peer.id(), response(MipMessageType.NETWORK_RESPONSE, "000001"), RESPONSE_PAYLOAD);
      assertThat(exchange.get(2, TimeUnit.SECONDS).decoded().field(70)).isEqualTo("270");
    }
  }

  @Test
  void networkRequestCannotReserveWithoutItsExplicitCode() {
    try (var session = new MipSession(1)) {
      var peer = attach(session, "peer-1");
      assertThatThrownBy(() -> session.exchange(REQUEST_PAYLOAD,
          new Iso8583Message(MipMessageType.NETWORK_REQUEST.value(), Map.of(11, "000001")), 1_000))
          .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DE70");
      assertThat(peer.sent).isEmpty();
    }
  }

  @Test
  void alreadyInterruptedWorkCannotWriteOrConsumeAStan() throws Exception {
    try (var session = new MipSession(1)) {
      var peer = attach(session, "peer-1");
      var failure = new AtomicReference<Throwable>();
      var interrupted = new AtomicBoolean();
      var done = new CountDownLatch(1);
      Thread.ofVirtual().start(() -> {
        Thread.currentThread().interrupt();
        try {
          session.exchange(REQUEST_PAYLOAD, request(MipMessageType.AUTHORIZATION_REQUEST, "000001"), 2_000);
        } catch (Throwable error) {
          failure.set(error);
          interrupted.set(Thread.currentThread().isInterrupted());
        } finally {
          done.countDown();
        }
      });
      assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(failure.get()).isInstanceOf(InterruptedException.class);
      assertThat(interrupted).isTrue();
      assertThat(peer.sent).isEmpty();
      peer.onSend = ignored -> session.receive(peer.id(),
          response(MipMessageType.AUTHORIZATION_RESPONSE, "000001"), RESPONSE_PAYLOAD);
      assertThat(session.exchange(REQUEST_PAYLOAD, request(MipMessageType.AUTHORIZATION_REQUEST, "000001"), 1_000)
          .decoded().field(11)).isEqualTo("000001");
    }
  }

  @Test
  void rawReplyBytesCannotBeMutatedByProducerOrConsumer() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var session = new MipSession(1)) {
      var peer = attach(session, "peer-1");
      byte[] payload = RESPONSE_PAYLOAD.clone();
      var exchange = exchange(executor, session, MipMessageType.NETWORK_REQUEST, "000001", 2_000);
      peer.takeWrite();
      session.receive(peer.id(), response(MipMessageType.NETWORK_RESPONSE, "000001"), payload);
      payload[0] = 99;
      MipReply reply = exchange.get(2, TimeUnit.SECONDS);
      reply.payload()[1] = 99;
      assertThat(reply.payload()).containsExactly(RESPONSE_PAYLOAD);
    }
  }

  @Test
  void zeroStanIsDistinctFromMissingStan() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var session = new MipSession(1)) {
      var peer = attach(session, "peer-1");
      var exchange = exchange(executor, session, MipMessageType.NETWORK_REQUEST, "000000", 2_000);
      peer.takeWrite();
      session.receive(peer.id(), response(MipMessageType.NETWORK_RESPONSE, "000000"), RESPONSE_PAYLOAD);
      assertThat(exchange.get(2, TimeUnit.SECONDS).decoded().field(11)).isEqualTo("000000");
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, 1_000_001})
  void invalidCapacityCannotCreateASession(int maxPending) {
    assertThatThrownBy(() -> new MipSession(maxPending)).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(longs = {0L, -1L, Long.MAX_VALUE})
  void invalidDeadlineCannotAdmitWork(long timeoutMs) {
    try (var session = new MipSession(1)) {
      assertThatThrownBy(() -> session.exchange(REQUEST_PAYLOAD,
          request(MipMessageType.NETWORK_REQUEST, "000001"), timeoutMs))
          .isInstanceOfAny(IllegalArgumentException.class, ArithmeticException.class);
    }
  }

  private static RecordingPeer attach(MipSession session, String id) {
    var peer = new RecordingPeer(id);
    assertThat(session.attach(peer)).isTrue();
    return peer;
  }

  private static Future<MipReply> exchange(ExecutorService executor, MipSession session,
                                          MipMessageType type, String stan, long timeoutMs) {
    return executor.submit(() -> session.exchange(REQUEST_PAYLOAD, request(type, stan), timeoutMs));
  }

  private static Iso8583Message request(MipMessageType type, String stan) {
    return new Iso8583Message(type.value(), type == MipMessageType.NETWORK_REQUEST
        ? Map.of(11, stan, 70, "270") : Map.of(11, stan));
  }

  private static Iso8583Message response(MipMessageType type, String stan) {
    return new Iso8583Message(type.value(), type == MipMessageType.NETWORK_RESPONSE
        ? Map.of(11, stan, 39, "00", 70, "270") : Map.of(11, stan, 39, "00"));
  }

  private static final class RecordingPeer implements MipPeer {
    private final String id;
    private final BlockingQueue<byte[]> sent = new LinkedBlockingQueue<>();
    private final AtomicInteger closes = new AtomicInteger();
    private volatile CompletableFuture<Void> writeResult = CompletableFuture.completedFuture(null);
    private volatile Consumer<byte[]> onSend = ignored -> {};

    private RecordingPeer(String id) {
      this.id = id;
    }

    @Override public String id() {
      return id;
    }

    @Override public CompletableFuture<Void> send(byte[] payload) {
      sent.add(payload.clone());
      onSend.accept(payload);
      return writeResult;
    }

    @Override public void close() {
      closes.incrementAndGet();
    }

    private byte[] takeWrite() throws InterruptedException {
      byte[] payload = sent.poll(2, TimeUnit.SECONDS);
      assertThat(payload).as("submitted ISO payload").isNotNull();
      return payload;
    }
  }
}
