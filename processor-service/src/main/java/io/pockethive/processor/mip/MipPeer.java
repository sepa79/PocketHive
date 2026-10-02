package io.pockethive.processor.mip;

import java.util.concurrent.CompletableFuture;

/**
 * Responsibility: expose one accepted MIP connection's asynchronous write and close effects.
 * Must not: decide request correlation, session admission or terminal exchange outcomes.
 * Contract: RESP-PROCESSOR-MIP-SESSION — docs/architecture/runtime-responsibilities.md#resp-processor-mip-session.
 */
public interface MipPeer {
  /** Immutable identity, unique for each connection throughout the runtime's lifetime. */
  String id();

  /** Submit an unframed ISO payload without blocking; complete only when its write succeeds or fails. */
  CompletableFuture<Void> send(byte[] payload);

  /** Close this connection without blocking. Repeated close calls must be safe. */
  void close();
}
