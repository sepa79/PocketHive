package io.pockethive.swarmcontroller;

import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.TEST_SWARM_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.filesystem.FilesystemSwarmStartupArtifactLoader;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.SwarmStartupArtifact;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SwarmControllerStartupInitializerTest {

  @Mock
  private SwarmLifecycle lifecycle;

  @Mock
  private FilesystemSwarmStartupArtifactLoader loader;

  @Test
  void exposesInitializedOnlyAfterApplyingBothVerifiedPlans() throws Exception {
    String sha256 = "a".repeat(64);
    Instant startedAt = Instant.parse("2026-09-01T12:00:00Z");
    Clock clock = mock(Clock.class);
    when(loader.expectedSha256()).thenReturn(sha256);
    when(clock.instant()).thenReturn(startedAt);
    when(loader.load(TEST_SWARM_ID)).thenReturn(
        SwarmStartupArtifact.v1(
            new SwarmPlan(TEST_SWARM_ID, List.of()),
            Map.of("name", "scenario-1")));

    SwarmControllerStartupInitializer initializer = new SwarmControllerStartupInitializer(
        lifecycle,
        new ObjectMapper().findAndRegisterModules(),
        SwarmControllerTestProperties.defaults(),
        loader,
        clock);

    ArgumentCaptor<String> swarmPlan = ArgumentCaptor.forClass(String.class);
    var initialization = inOrder(loader, clock, lifecycle);
    initialization.verify(loader).expectedSha256();
    initialization.verify(clock).instant();
    initialization.verify(loader).load(TEST_SWARM_ID);
    initialization.verify(lifecycle).prepare(swarmPlan.capture());
    initialization.verify(lifecycle).applyScenarioPlan("{\"name\":\"scenario-1\"}");
    assertThat(new ObjectMapper().readTree(swarmPlan.getValue()).path("id").asText())
        .isEqualTo(TEST_SWARM_ID);
    assertThat(initializer.isInitialized()).isTrue();
    assertThat(initializer.artifactSha256()).isEqualTo(sha256);
    assertThat(initializer.startedAt()).isEqualTo(startedAt);
  }
}
