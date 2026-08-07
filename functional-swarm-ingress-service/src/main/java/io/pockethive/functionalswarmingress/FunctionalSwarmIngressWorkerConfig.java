package io.pockethive.functionalswarmingress;

import io.pockethive.requesttemplates.HttpTemplateReference;
import java.util.Objects;

/** Explicit template and reply namespace for one Functional Swarm ingress worker. */
public record FunctionalSwarmIngressWorkerConfig(HttpTemplateReference template, String replyListPrefix) {
  public FunctionalSwarmIngressWorkerConfig {
    template = Objects.requireNonNull(template, "template");
    if (replyListPrefix == null || replyListPrefix.isBlank()) {
      throw new IllegalArgumentException("replyListPrefix must not be blank");
    }
    replyListPrefix = replyListPrefix.trim();
  }
}
