package io.pockethive.swarmcontroller;

import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.TEST_SWARM_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SwarmControllerStartupInitializerTest {

  @Mock
  private SwarmLifecycle lifecycle;

  @Mock
  private FilesystemSwarmStartupArtifactLoader loader;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void planFailureRetainsUnreadyControllerForExplicitRemoval(boolean scenarioFailure) {
    when(loader.expectedSha256()).thenReturn("a".repeat(64));
    when(loader.load(TEST_SWARM_ID)).thenReturn(SwarmStartupArtifact.v1(
        new SwarmPlan(TEST_SWARM_ID, List.of()), Map.of("name", "scenario-1")));
    var failure = new IllegalStateException("injected plan failure");
    if (scenarioFailure) {
      doThrow(failure).when(lifecycle).applyScenarioPlan(anyString());
    } else {
      doThrow(failure).when(lifecycle).prepare(anyString());
    }

    var initializer = new SwarmControllerStartupInitializer(
        lifecycle, new ObjectMapper().findAndRegisterModules(),
        SwarmControllerTestProperties.defaults(), loader);

    assertThat(initializer.isInitialized()).isFalse();
    assertThat(initializer.artifactSha256()).isEqualTo("a".repeat(64));
    verify(lifecycle).fail("Startup plan application failed");
    verify(lifecycle, never()).remove();
    if (!scenarioFailure) verify(lifecycle, never()).applyScenarioPlan(anyString());
  }

  @Test
  void artifactVerificationFailureStillAbortsStartupBeforeLifecycleEffects() {
    when(loader.expectedSha256()).thenReturn("a".repeat(64));
    when(loader.load(TEST_SWARM_ID)).thenThrow(new IllegalStateException("digest mismatch"));

    assertThatThrownBy(() -> new SwarmControllerStartupInitializer(
        lifecycle, new ObjectMapper().findAndRegisterModules(),
        SwarmControllerTestProperties.defaults(), loader))
        .hasMessage("digest mismatch");
    verifyNoInteractions(lifecycle);
  }

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
