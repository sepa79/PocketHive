package io.pockethive.processor.mip;

import io.pockethive.iso8583.Iso8583Message;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Responsibility: own one accepted MIP peer and settle bounded, correlated request exchanges once.
 * Must not: frame/decode messages, build business fields, retry authorizations or block an IO listener.
 * Contract: RESP-PROCESSOR-MIP-SESSION — docs/architecture/runtime-responsibilities.md#resp-processor-mip-session.
 */
public final class MipSession implements AutoCloseable {
  private static final int STAN_LENGTH = 6;
  private static final int STAN_VALUES = 1_000_000;

  private final Object monitor = new Object();
  private final int maxPending;
  private final BitSet usedStans = new BitSet(STAN_VALUES);
  private final Map<Integer, Pending> pending = new HashMap<>();
  private MipSessionState state = MipSessionState.NEW;
  private MipPeer peer;

  public MipSession(int maxPending) {
    validateCapacity(maxPending);
    this.maxPending = maxPending;
  }

  public static void validateCapacity(int maxPending) {
    if (maxPending <= 0 || maxPending > STAN_VALUES) {
      throw new IllegalArgumentException("maxPending must be between 1 and " + STAN_VALUES);
    }
  }

  public boolean attach(MipPeer candidate) {
    Objects.requireNonNull(candidate, "peer");
    if (candidate.id() == null || candidate.id().isBlank()) {
      throw new IllegalArgumentException("MIP peer identity must not be blank");
    }
    synchronized (monitor) {
      if (state == MipSessionState.NEW) {
        peer = candidate;
        state = MipSessionState.CONNECTED;
        monitor.notifyAll();
        return true;
      }
    }
    candidate.close();
    return false;
  }

  public void detach(String peerId, Throwable cause) {
    Objects.requireNonNull(cause, "cause");
    MipPeer detached;
    synchronized (monitor) {
      if (state != MipSessionState.CONNECTED || !peer.id().equals(peerId)) {
        return;
      }
      detached = peer;
      disconnectLocked(cause);
    }
    detached.close();
  }

  /** Read-only connection snapshot for a same-peer, asynchronous network-management reply. */
  public MipPeer peerFor(String peerId) {
    synchronized (monitor) {
      return state == MipSessionState.CONNECTED && peer.id().equals(peerId) ? peer : null;
    }
  }

  public MipSessionState state() {
    synchronized (monitor) {
      return state;
    }
  }

  /** Called by an IO listener; never waits for a Work thread or a write future. */
  public boolean receive(String peerId, Iso8583Message response, byte[] payload) {
    Objects.requireNonNull(response, "response");
    Objects.requireNonNull(payload, "payload");
    synchronized (monitor) {
      if (state != MipSessionState.CONNECTED || !peer.id().equals(peerId)) {
        return false;
      }
      int stan = requireStan(response);
      Pending request = pending.get(stan);
      if (request == null || request.expectedResponse.value() != response.mti()) {
        return false;
      }
      if (request.response != null || request.deadline - System.nanoTime() <= 0) {
        return false;
      }
      String responseCode = response.field(MipFields.RESPONSE_CODE);
      if (responseCode == null || responseCode.isBlank()) {
        throw new IllegalArgumentException("MIP response requires DE39");
      }
      if (request.expectedResponse == MipMessageType.NETWORK_RESPONSE
          && !request.networkCode.equals(response.field(MipFields.NETWORK_CODE))) {
        throw new IllegalArgumentException("MIP network response DE70 must match its request");
      }
      request.response = new MipReply(response, payload);
      completeIfReadyLocked(request);
      return true;
    }
  }

  /** Blocking entrypoint for Work execution only; peer wait, write and reply share one deadline. */
  public MipReply exchange(byte[] payload, Iso8583Message request, long timeoutMs)
      throws InterruptedException, TimeoutException, ExecutionException {
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(request, "request");
    if (timeoutMs <= 0) {
      throw new IllegalArgumentException("MIP exchange timeout must be positive");
    }
    long timeoutNanos = Math.multiplyExact(timeoutMs, TimeUnit.MILLISECONDS.toNanos(1));
    long deadline = System.nanoTime() + timeoutNanos;
    MipMessageType expected = MipMessageType.fromValue(request.mti()).expectedResponse();
    int stan = requireStan(request);
    String networkCode = request.field(MipFields.NETWORK_CODE);
    if (expected == MipMessageType.NETWORK_RESPONSE && (networkCode == null || networkCode.isBlank())) {
      throw new IllegalArgumentException("MIP network request requires DE70");
    }
    Pending reserved;
    try {
      synchronized (monitor) {
        if (Thread.currentThread().isInterrupted()) {
          throw new InterruptedException("MIP exchange interrupted before admission");
        }
        while (state == MipSessionState.NEW) {
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0) {
            throw new TimeoutException("MIP exchange timed out waiting for a peer");
          }
          TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
        }
        if (state == MipSessionState.CLOSED) {
          throw new IllegalStateException("MIP session is closed");
        }
        if (pending.size() >= maxPending) {
          throw new IllegalStateException("MIP pending request capacity is exhausted");
        }
        if (usedStans.get(stan)) {
          throw new IllegalArgumentException("MIP STAN cannot be reused on the same connection");
        }
        if (deadline - System.nanoTime() <= 0) {
          throw new TimeoutException("MIP exchange timed out before writing");
        }
        reserved = new Pending(stan, expected, deadline, peer, networkCode);
        pending.put(stan, reserved);
        usedStans.set(stan);
        try {
          Objects.requireNonNull(peer.send(payload.clone()), "MIP write future")
              .whenComplete((ignored, failure) -> writeCompleted(reserved, failure));
        } catch (RuntimeException failure) {
          writeCompleted(reserved, failure);
        }
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw interrupted;
    }

    try {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        throw new TimeoutException("MIP exchange timed out");
      }
      return reserved.result.get(remaining, TimeUnit.NANOSECONDS);
    } catch (ExecutionException failure) {
      return settledResult(reserved);
    } catch (TimeoutException timeout) {
      synchronized (monitor) {
        failLocked(reserved, timeout);
      }
      return settledResult(reserved);
    } catch (InterruptedException interrupted) {
      synchronized (monitor) {
        failLocked(reserved, interrupted);
      }
      Thread.currentThread().interrupt();
      return settledResult(reserved);
    }
  }

  private void writeCompleted(Pending request, Throwable failure) {
    MipPeer failedPeer = null;
    synchronized (monitor) {
      if (state != MipSessionState.CONNECTED || peer != request.peer) {
        return;
      }
      if (failure != null) {
        failedPeer = peer;
        disconnectLocked(failure);
      } else if (pending.get(request.stan) == request) {
        request.writeSucceeded = true;
        if (request.deadline - System.nanoTime() <= 0) {
          failLocked(request, new TimeoutException("MIP exchange timed out writing"));
        } else {
          completeIfReadyLocked(request);
        }
      }
    }
    if (failedPeer != null) {
      failedPeer.close();
    }
  }

  private void completeIfReadyLocked(Pending request) {
    if (request.writeSucceeded && request.response != null) {
      pending.remove(request.stan, request);
      request.result.complete(request.response);
    }
  }

  private void failLocked(Pending request, Throwable failure) {
    if (pending.remove(request.stan, request)) {
      request.result.completeExceptionally(failure);
    }
  }

  private void disconnectLocked(Throwable failure) {
    state = MipSessionState.NEW;
    peer = null;
    usedStans.clear();
    for (Pending request : pending.values()) {
      request.result.completeExceptionally(failure);
    }
    pending.clear();
    monitor.notifyAll();
  }

  public static int requireStan(Iso8583Message message) {
    String stan = message.field(MipFields.STAN);
    if (stan == null || stan.length() != STAN_LENGTH) {
      throw new IllegalArgumentException("MIP message requires six numeric digits in DE11");
    }
    for (int index = 0; index < STAN_LENGTH; index++) {
      char digit = stan.charAt(index);
      if (digit < '0' || digit > '9') {
        throw new IllegalArgumentException("MIP message requires six numeric digits in DE11");
      }
    }
    return Integer.parseInt(stan);
  }

  private static MipReply settledResult(Pending request)
      throws InterruptedException, TimeoutException, ExecutionException {
    try {
      return request.result.join();
    } catch (CompletionException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof TimeoutException timeout) {
        throw timeout;
      }
      if (cause instanceof InterruptedException interrupted) {
        throw interrupted;
      }
      throw new ExecutionException(cause);
    }
  }

  @Override
  public void close() {
    MipPeer closedPeer;
    synchronized (monitor) {
      if (state == MipSessionState.CLOSED) {
        return;
      }
      closedPeer = peer;
      disconnectLocked(new IllegalStateException("MIP session is closed"));
      state = MipSessionState.CLOSED;
    }
    if (closedPeer != null) {
      closedPeer.close();
    }
  }

  private static final class Pending {
    private final int stan;
    private final MipMessageType expectedResponse;
    private final long deadline;
    private final MipPeer peer;
    private final String networkCode;
    private final CompletableFuture<MipReply> result = new CompletableFuture<>();
    private boolean writeSucceeded;
    private MipReply response;

    private Pending(int stan, MipMessageType expectedResponse, long deadline, MipPeer peer, String networkCode) {
      this.stan = stan;
      this.expectedResponse = expectedResponse;
      this.deadline = deadline;
      this.peer = peer;
      this.networkCode = networkCode;
    }
  }
}
