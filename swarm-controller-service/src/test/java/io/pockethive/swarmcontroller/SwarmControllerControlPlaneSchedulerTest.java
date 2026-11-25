package io.pockethive.swarmcontroller;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
  void publishesInitialFullStatusOnlyWhenApplicationIsReady() {
    new ApplicationContextRunner()
        .withBean(SwarmControllerControlPlaneScheduler.class, () -> scheduler)
        .run(context -> {
          verify(statusPublisher, never()).publishFull();

          context.publishEvent(new ApplicationReadyEvent(
              new SpringApplication(Application.class), new String[0],
              context.getSourceApplicationContext(), Duration.ZERO));

          verify(statusPublisher).publishFull();
        });
  }

  @Test
  void startupPublicationFailureDoesNotPreventControllerStartup() {
    doThrow(new IllegalStateException("broker unavailable"))
        .when(statusPublisher).publishFull();

    assertThatCode(scheduler::publishInitialStatus).doesNotThrowAnyException();
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
