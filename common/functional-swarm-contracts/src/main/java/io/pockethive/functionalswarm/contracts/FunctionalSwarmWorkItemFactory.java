package io.pockethive.functionalswarm.contracts;

import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerInfo;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Canonical projection of the public invocation into a templating WorkItem. */
public final class FunctionalSwarmWorkItemFactory {
  private FunctionalSwarmWorkItemFactory() {
  }

  public static WorkItem create(FunctionalSwarmInvocation invocation, WorkerInfo source) {
    Objects.requireNonNull(invocation, "invocation");
    Objects.requireNonNull(source, "source");
    return WorkItem.text(source, invocation.payload())
        .headers(new LinkedHashMap<String, Object>(invocation.headers()))
        .build();
  }
}
