package io.pockethive.swarmcontroller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SwarmControllerRuntimeMetadataTest {

  @Test
  void resolvesOneImmutableExplicitRuntimeSnapshot() {
    SwarmControllerRuntimeMetadata metadata = new SwarmControllerRuntimeMetadata(
        SwarmControllerTestProperties.defaults(), " run-1 ");

    assertThat(metadata.values())
        .containsEntry("stackName", "ph-default")
        .containsEntry("templateId", "test-template")
        .containsEntry("runId", "run-1")
        .containsKeys("containerId", "image");
    assertThatThrownBy(() -> metadata.values().put("runId", "other-run"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsMissingRunIdentity() {
    assertThatThrownBy(() -> new SwarmControllerRuntimeMetadata(
        SwarmControllerTestProperties.defaults(), " "))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("pockethive.journal.run-id");
  }
}
