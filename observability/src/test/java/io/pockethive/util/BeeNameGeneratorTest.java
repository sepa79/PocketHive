package io.pockethive.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeeNameGeneratorTest {
  @Test
  void includesSwarmIdInName() {
    String name = BeeNameGenerator.generate("generator", "sw1");
    assertTrue(name.startsWith("sw1-seeder-bee-"));
    assertEquals(6, name.split("-").length);
  }

  @Test
  void mapsTriggerToBuzzer() {
    String name = BeeNameGenerator.generate("trigger", "sw1");
    assertTrue(name.startsWith("sw1-buzzer-bee-"));
  }

  @Test
  void mapsSwarmControllerToMarshal() {
    String name = BeeNameGenerator.generate("swarm-controller", "sw1");
    assertTrue(name.startsWith("sw1-marshal-bee-"));
  }

  @Test
  void mapsOrchestratorToQueen() {
    String name = BeeNameGenerator.generate("orchestrator", "sw1");
    assertTrue(name.startsWith("sw1-queen-bee-"));
  }

  @Test
  void mapsRequestBuilderToComposer() {
    String name = BeeNameGenerator.generate("request-builder", "sw1");
    assertTrue(name.startsWith("sw1-composer-bee-"));
  }

  @Test
  void mapsClearingExportToPacker() {
    String name = BeeNameGenerator.generate("clearing-export", "sw1");
    assertTrue(name.startsWith("sw1-packer-bee-"));
  }

  @Test
  void stripsUnsupportedCharactersFromSwarmId() {
    String name = BeeNameGenerator.generate("swarm-controller", "client/alpha:beta");
    assertTrue(name.startsWith("clientalphabeta-marshal-bee-"));
  }

  @Test
  void fallsBackToDefaultWhenSanitizedSwarmIdIsEmpty() {
    String name = BeeNameGenerator.generate("generator", "!!!");
    assertTrue(name.startsWith("default-seeder-bee-"));
  }
}
