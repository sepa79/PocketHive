package io.pockethive.functionalswarmreply;

import io.pockethive.functionalswarm.contracts.FunctionalSwarmJsonCodec;
import io.pockethive.functionalswarm.contracts.FunctionalSwarmProtocol;
import io.pockethive.functionalswarm.contracts.FunctionalSwarmResponseMapper;
import io.pockethive.functionalswarm.redis.FunctionalSwarmRedisTransport;
import io.pockethive.functionalswarm.redis.JedisFunctionalSwarmRedisTransport;
import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerCapability;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Verifies and publishes one expiring, idempotent Functional Swarm RPC response. */
@Component("functionalSwarmReplyWorker")
@PocketHiveWorker(capabilities = {WorkerCapability.MESSAGE_DRIVEN}, config = FunctionalSwarmReplyWorkerConfig.class)
class FunctionalSwarmReplyWorker implements PocketHiveWorkerFunction {
  private final FunctionalSwarmJsonCodec codec = new FunctionalSwarmJsonCodec();
  private final FunctionalSwarmRedisTransport transport;

  @Autowired
  FunctionalSwarmReplyWorker(FunctionalSwarmReplyWorkerProperties properties) {
    this(new JedisFunctionalSwarmRedisTransport());
  }

  FunctionalSwarmReplyWorker(FunctionalSwarmRedisTransport transport) {
    this.transport = transport;
  }

  @Override
  public WorkItem onMessage(WorkItem input, WorkerContext context) {
    FunctionalSwarmReplyWorkerConfig config = context.requireConfig(FunctionalSwarmReplyWorkerConfig.class);
    String replyList = requiredHeader(input, FunctionalSwarmProtocol.REPLY_LIST_HEADER);
    String requestId = requiredHeader(input, FunctionalSwarmProtocol.REQUEST_ID_HEADER);
    String expectedReplyList = config.replyListPrefix() + requestId;
    if (!expectedReplyList.equals(replyList)) {
      throw new IllegalArgumentException("Functional Swarm replyList does not match the configured reply namespace");
    }
    FunctionalSwarmResponseMapper.fromHttpResult(codec.readHttpResult(input.payload()));
    transport.publishReplyOnce(config.redis(), replyList, requestId, input.payload(), config.replyTtlSeconds());
    return null;
  }

  private static String requiredHeader(WorkItem input, String name) {
    Object value = input.headers().get(name);
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("Missing Functional Swarm transport header: " + name);
    }
    return text;
  }

}
