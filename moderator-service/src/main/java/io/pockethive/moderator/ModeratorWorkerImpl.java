package io.pockethive.moderator;

import io.pockethive.Topology;
import io.pockethive.TopologyDefaults;
import io.pockethive.worker.sdk.api.MessageWorker;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerType;
import org.springframework.stereotype.Component;

@Component("moderatorWorker")
@PocketHiveWorker(
    role = "moderator",
    type = WorkerType.MESSAGE,
  inQueue = TopologyDefaults.GEN_QUEUE,
  outQueue = TopologyDefaults.MOD_QUEUE,
    config = ModeratorWorkerConfig.class
)
class ModeratorWorkerImpl implements MessageWorker {

  private final ModeratorDefaults defaults;

  ModeratorWorkerImpl(ModeratorDefaults defaults) {
    this.defaults = defaults;
  }

  @Override
  public WorkResult onMessage(WorkMessage in, WorkerContext context) {
    ModeratorWorkerConfig config = context.config(ModeratorWorkerConfig.class)
        .orElseGet(defaults::asConfig);
    context.statusPublisher()
        .workIn(Topology.GEN_QUEUE)
        .workOut(Topology.MOD_QUEUE)
        .update(status -> status
            .data("enabled", config.enabled()));
    WorkMessage out = in.toBuilder()
        .header("x-ph-service", context.info().role())
        .build();
    return WorkResult.message(out);
  }
}
