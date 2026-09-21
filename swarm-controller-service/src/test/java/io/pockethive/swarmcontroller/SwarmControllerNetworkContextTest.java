package io.pockethive.swarmcontroller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.swarm.model.NetworkMode;
import org.junit.jupiter.api.Test;

class SwarmControllerNetworkContextTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final SwarmLifecycle lifecycle = mock(SwarmLifecycle.class);

  @Test
  void recognizesOnlyControllerNetworkContextFields() throws Exception {
    SwarmControllerNetworkContext context = context(NetworkMode.DIRECT, null);

    assertThat(context.isOnlyNetworkContext(
        "swarm-controller",
        mapper.readTree("{\"networkMode\":\"DIRECT\",\"sutId\":\"sut-1\"}"))).isTrue();
    assertThat(context.isOnlyNetworkContext(
        "generator",
        mapper.readTree("{\"networkMode\":\"DIRECT\"}"))).isFalse();
    assertThat(context.isOnlyNetworkContext(
        "swarm-controller",
        mapper.readTree("{\"networkMode\":\"DIRECT\",\"enabled\":true}"))).isFalse();
  }

  @Test
  void directOverrideClearsTheProfile() throws Exception {
    SwarmControllerNetworkContext context = context(NetworkMode.PROXIED, "profile-1");

    boolean changed = context.applyOverride(mapper.readTree("{\"networkMode\":\"DIRECT\"}"));

    assertThat(changed).isTrue();
    assertThat(context.networkMode()).isEqualTo(NetworkMode.DIRECT);
    assertThat(context.networkProfileId()).isNull();
  }

  @Test
  void rejectsAnOverrideForAnotherSut() throws Exception {
    when(lifecycle.sutId()).thenReturn("sut-1");
    SwarmControllerNetworkContext context = context(NetworkMode.PROXIED, "profile-1");

    assertThatThrownBy(() -> context.applyOverride(mapper.readTree("{\"sutId\":\"sut-2\"}")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("sutId override does not match configured swarm SUT");
  }

  private SwarmControllerNetworkContext context(NetworkMode mode, String profileId) {
    return new SwarmControllerNetworkContext(
        lifecycle, "swarm-controller", "sut-1", mode, profileId);
  }
}
