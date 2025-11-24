package io.pockethive.swarmcontroller.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SwarmRuntimeCoreVolumesTest {

  @Test
  void resolveVolumesExtractsDockerVolumeSpecs() throws Exception {
    Map<String, Object> config = Map.of(
        "docker", Map.of(
            "volumes", List.of(
                "/host/a:/container/a:ro",
                "  named-vol:/container/cache  ",
                "",
                42)));

    Method m = SwarmRuntimeCore.class.getDeclaredMethod("resolveVolumes", Map.class);
    m.setAccessible(true);
    @SuppressWarnings("unchecked")
    List<String> volumes = (List<String>) m.invoke(null, config);

    assertThat(volumes)
        .containsExactly(
            "/host/a:/container/a:ro",
            "named-vol:/container/cache");
  }
}
