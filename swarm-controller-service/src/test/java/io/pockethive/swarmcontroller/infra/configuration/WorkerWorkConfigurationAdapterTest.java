package io.pockethive.swarmcontroller.infra.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.Work;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.swarmcontroller.config.WorkerWorkConfigurationComposition;
import io.pockethive.swarmcontroller.runtime.WorkerWorkConfigurationPort;
import io.pockethive.work.config.WorkConfigurationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerWorkConfigurationAdapterTest {
  private final WorkerWorkConfigurationPort adapter = new WorkerWorkConfigurationComposition()
      .workerWorkConfiguration(mock(SwarmControllerProperties.class), new io.pockethive.topology.work.PrefixedWorkResourceNames());

  @Test
  void repeatedCompositionDoesNotLeakOverridesOrChangeSources() {
    var base = new LinkedHashMap<>(baseEnvironment());
    Map<String, Object> config = Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler",
        Map.of("ratePerSec", 2.0, "maxMessages", 0)), "outputs", Map.of("type", "NONE"));
    var overrides = new LinkedHashMap<>(Map.of("POCKETHIVE_INPUTS_SCHEDULER_RATEPERSEC", "7"));
    Bee first = new Bee("generator", "image", Work.ofDefaults(null, null), overrides, config);
    var firstResult = adapter.compose(first, first.config(), base);
    Bee second = new Bee("generator", "image", Work.ofDefaults(null, null), Map.of(), config);
    var secondResult = adapter.compose(second, second.config(), base);

    assertThat(firstResult.environment()).containsEntry("POCKETHIVE_INPUTS_SCHEDULER_RATEPERSEC", "7");
    assertThat(secondResult.environment()).containsEntry("POCKETHIVE_INPUTS_SCHEDULER_RATEPERSEC", "2");
    assertThat(base).isEqualTo(baseEnvironment());
    assertThat(first.config()).isEqualTo(config);
    assertThat(overrides).containsExactlyEntriesOf(Map.of("POCKETHIVE_INPUTS_SCHEDULER_RATEPERSEC", "7"));
    assertThat(firstResult.toString()).doesNotContain("test-secret");
    assertThatThrownBy(() -> firstResult.environment().put("unexpected", "change"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectedCandidateLeavesInputsUntouchedAndDoesNotPoisonNextComposition() {
    var base = new LinkedHashMap<>(baseEnvironment());
    Map<String, Object> invalid = Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler",
        Map.of("ratePerSec", -1.0, "maxMessages", 0)));
    Bee rejected = new Bee("generator", "image", Work.ofDefaults(null, null), Map.of(), invalid);
    assertThatThrownBy(() -> adapter.compose(rejected, rejected.config(), base))
        .isInstanceOf(WorkConfigurationException.class);
    assertThat(base).isEqualTo(baseEnvironment());
    assertThat(rejected.config()).isEqualTo(invalid);
    Bee valid = new Bee("generator", "image", Work.ofDefaults(null, null), Map.of(),
        Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler", Map.of("ratePerSec", 3.0, "maxMessages", 0))));
    assertThat(adapter.compose(valid, valid.config(), base).environment())
        .containsEntry("POCKETHIVE_INPUTS_SCHEDULER_RATEPERSEC", "3");
  }

  @Test
  void standaloneCompositionCannotBypassDeclarationPreflight() {
    Bee invalid = new Bee("generator", "image", Work.ofDefaults(null, null), Map.of(),
        Map.of("inputs", Map.of("type", "SCHEDULER", "scheduler",
            Map.of("ratePerSec", 1.0, "enabled", true))));
    assertThatThrownBy(() -> adapter.compose(invalid, invalid.config(), baseEnvironment()))
        .isInstanceOf(WorkConfigurationException.class).hasMessageContaining("enabled");
  }

  private static Map<String, String> baseEnvironment() {
    return Map.of("SPRING_RABBITMQ_HOST", "rabbit", "SPRING_RABBITMQ_PORT", "5672",
        "SPRING_RABBITMQ_USERNAME", "user", "SPRING_RABBITMQ_PASSWORD", "test-secret",
        "SPRING_RABBITMQ_VIRTUAL_HOST", "/");
  }
}
