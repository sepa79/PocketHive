package io.pockethive.swarmcontroller;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Responsibility: Drive initial and periodic Swarm Controller control-plane publication and convergence checks.
 * Must not: Consume AMQP messages, execute commands, build status projections, or own convergence state.
 * Contract: Publish one initial full status and preserve the five-second periodic trigger order.
 */
@Component
@EnableScheduling
final class SwarmControllerControlPlaneScheduler implements InitializingBean {

  private static final Logger log = LoggerFactory.getLogger(SwarmControllerControlPlaneScheduler.class);
  private static final long STATUS_INTERVAL_MS = 5_000L;

  private final SwarmControllerStatusPublisher statusPublisher;
  private final SwarmStatusFullCoordinator statusFullCoordinator;
  private final SwarmLifecycleCommandHandler lifecycleCommands;

  SwarmControllerControlPlaneScheduler(
      SwarmControllerStatusPublisher statusPublisher,
      SwarmStatusFullCoordinator statusFullCoordinator,
      SwarmLifecycleCommandHandler lifecycleCommands) {
    this.statusPublisher = Objects.requireNonNull(statusPublisher, "statusPublisher");
    this.statusFullCoordinator = Objects.requireNonNull(statusFullCoordinator, "statusFullCoordinator");
    this.lifecycleCommands = Objects.requireNonNull(lifecycleCommands, "lifecycleCommands");
  }

  @Override
  public void afterPropertiesSet() {
    try {
      statusPublisher.publishFull();
    } catch (Exception failure) {
      log.warn("initial status", failure);
    }
  }

  @Scheduled(fixedRate = STATUS_INTERVAL_MS)
  void tick() {
    statusPublisher.publishDelta();
    statusFullCoordinator.maybePublishStartupReady();
    lifecycleCommands.tryComplete();
    statusFullCoordinator.maybePublishPending();
  }
}
