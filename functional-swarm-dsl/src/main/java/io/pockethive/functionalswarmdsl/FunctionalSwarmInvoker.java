package io.pockethive.functionalswarmdsl;

import io.pockethive.functionalswarm.contracts.FunctionalSwarmInvocation;
import io.pockethive.functionalswarm.contracts.FunctionalSwarmResponse;

/** Common local/remote Functional Swarm boundary. */
@FunctionalInterface
public interface FunctionalSwarmInvoker {
  FunctionalSwarmResponse invoke(FunctionalSwarmInvocation invocation);
}
