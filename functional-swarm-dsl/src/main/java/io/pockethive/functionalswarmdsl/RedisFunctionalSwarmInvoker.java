package io.pockethive.functionalswarmdsl;

import io.pockethive.functionalswarm.contracts.FunctionalSwarmInvocation;
import io.pockethive.functionalswarm.contracts.FunctionalSwarmJsonCodec;
import io.pockethive.functionalswarm.contracts.FunctionalSwarmProtocol;
import io.pockethive.functionalswarm.contracts.FunctionalSwarmResponse;
import io.pockethive.functionalswarm.contracts.FunctionalSwarmResponseMapper;
import io.pockethive.functionalswarm.contracts.FunctionalSwarmRpcRequest;
import io.pockethive.functionalswarm.redis.FunctionalSwarmRedisTransport;
import io.pockethive.functionalswarm.redis.JedisFunctionalSwarmRedisTransport;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** Redis list transport adapter for the versioned Functional Swarm RPC contract. */
final class RedisFunctionalSwarmInvoker implements FunctionalSwarmInvoker {
  private final FunctionalSwarmRemoteConfig config;
  private final FunctionalSwarmJsonCodec codec;
  private final FunctionalSwarmRedisTransport transport;

  RedisFunctionalSwarmInvoker(FunctionalSwarmRemoteConfig config) {
    this(config, new FunctionalSwarmJsonCodec(), new JedisFunctionalSwarmRedisTransport());
  }

  RedisFunctionalSwarmInvoker(
      FunctionalSwarmRemoteConfig config,
      FunctionalSwarmJsonCodec codec,
      FunctionalSwarmRedisTransport transport
  ) {
    this.config = Objects.requireNonNull(config, "config");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.transport = Objects.requireNonNull(transport, "transport");
  }

  @Override
  public FunctionalSwarmResponse invoke(FunctionalSwarmInvocation invocation) {
    Objects.requireNonNull(invocation, "invocation");
    String requestId = UUID.randomUUID().toString();
    String replyList = config.replyListPrefix() + requestId;
    FunctionalSwarmRpcRequest request = new FunctionalSwarmRpcRequest(
        FunctionalSwarmProtocol.VERSION, requestId, replyList, invocation);
    Duration timeout = config.timeout();
    try {
      transport.publishRequest(config.redis(), config.requestList(), codec.writeRequest(request));
      String reply = transport.awaitReply(config.redis(), replyList, timeout).orElse(null);
      if (reply == null) {
        throw new IllegalStateException("Timed out waiting for Functional Swarm response after " + timeout);
      }
      return FunctionalSwarmResponseMapper.fromHttpResult(codec.readHttpResult(reply));
    } catch (IllegalStateException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalStateException("Remote Functional Swarm invocation failed", ex);
    }
  }

}
