package io.pockethive.functionalswarm.redis;

import java.time.Duration;
import java.util.Optional;

/** Redis transport port for the Functional Swarm request/reply protocol. */
public interface FunctionalSwarmRedisTransport {
  void publishRequest(FunctionalSwarmRedisEndpoint endpoint, String requestList, String requestPayload);

  Optional<String> awaitReply(FunctionalSwarmRedisEndpoint endpoint, String replyList, Duration timeout);

  boolean publishReplyOnce(
      FunctionalSwarmRedisEndpoint endpoint,
      String replyList,
      String requestId,
      String responsePayload,
      long replyTtlSeconds
  );
}
