package io.pockethive.swarmcontroller;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class SwarmControllerControlPlaneSchedulerTest {

  private final SwarmControllerStatusPublisher statusPublisher =
      mock(SwarmControllerStatusPublisher.class);
  private final SwarmStatusFullCoordinator statusFullCoordinator =
      mock(SwarmStatusFullCoordinator.class);
  private final SwarmLifecycleCommandHandler lifecycleCommands =
      mock(SwarmLifecycleCommandHandler.class);
  private final SwarmControllerControlPlaneScheduler scheduler =
      new SwarmControllerControlPlaneScheduler(
          statusPublisher, statusFullCoordinator, lifecycleCommands);

  @Test
  void publishesOneInitialFullStatus() {
    scheduler.afterPropertiesSet();

    verify(statusPublisher).publishFull();
  }

  @Test
  void startupPublicationFailureDoesNotPreventControllerStartup() {
    doThrow(new IllegalStateException("broker unavailable"))
        .when(statusPublisher).publishFull();

    assertThatCode(scheduler::afterPropertiesSet).doesNotThrowAnyException();
  }

  @Test
  void preservesPeriodicTriggerOrder() {
    scheduler.tick();

    InOrder order = inOrder(statusPublisher, statusFullCoordinator, lifecycleCommands);
    order.verify(statusPublisher).publishDelta();
    order.verify(statusFullCoordinator).maybePublishStartupReady();
    order.verify(lifecycleCommands).tryComplete();
    order.verify(statusFullCoordinator).maybePublishPending();
  }
}
