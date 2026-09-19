package io.pockethive.swarm.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SwarmStartupArtifactTest {

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void preservesJsonNullValuesInImmutableScenarioPlan() throws Exception {
    Map<String, Object> scenarioPlan = new LinkedHashMap<>();
    scenarioPlan.put("duration", null);
    scenarioPlan.put("bees", List.of());

    SwarmStartupArtifact artifact = SwarmStartupArtifact.v1(
        new SwarmPlan("swarm-1", List.of()),
        scenarioPlan);

    assertTrue(artifact.scenarioPlan().containsKey("duration"));
    assertEquals(null, artifact.scenarioPlan().get("duration"));
    assertTrue(mapper.readTree(mapper.writeValueAsBytes(artifact)).at("/scenarioPlan/duration").isNull());
    assertThrows(
        UnsupportedOperationException.class,
        () -> artifact.scenarioPlan().put("duration", "changed"));
  }
}
