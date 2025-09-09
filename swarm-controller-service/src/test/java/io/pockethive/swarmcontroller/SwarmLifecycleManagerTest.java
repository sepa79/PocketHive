package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.swarmcontroller.infra.docker.DockerContainerClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpAdmin;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SwarmLifecycleManagerTest {
  @Mock
  AmqpAdmin amqp;
  @Mock
  DockerContainerClient docker;

  ObjectMapper mapper = new ObjectMapper();

  @Test
  void startLaunchesContainers() throws Exception {
    SwarmLifecycleManager manager = new SwarmLifecycleManager(amqp, mapper, docker);
    SwarmPlan plan = new SwarmPlan(List.of(
        new SwarmPlan.Bee("gen", "img1", null),
        new SwarmPlan.Bee("mod", "img2", null)));
    when(docker.createAndStartContainer("img1")).thenReturn("c1");
    when(docker.createAndStartContainer("img2")).thenReturn("c2");

    manager.start(mapper.writeValueAsString(plan));

    verify(docker).createAndStartContainer("img1");
    verify(docker).createAndStartContainer("img2");
    assertEquals(SwarmStatus.RUNNING, manager.getStatus());
  }

  @Test
  void stopRemovesContainersAndUpdatesStatus() throws Exception {
    SwarmLifecycleManager manager = new SwarmLifecycleManager(amqp, mapper, docker);
    SwarmPlan plan = new SwarmPlan(List.of(new SwarmPlan.Bee("gen", "img1", null)));
    when(docker.createAndStartContainer("img1")).thenReturn("c1");
    manager.start(mapper.writeValueAsString(plan));

    manager.stop();

    verify(docker).stopAndRemoveContainer("c1");
    assertEquals(SwarmStatus.STOPPED, manager.getStatus());
  }
}
