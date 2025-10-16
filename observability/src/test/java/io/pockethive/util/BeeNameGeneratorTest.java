package io.pockethive.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BeeNameGeneratorTest {

  private String originalBeeName;

  @BeforeEach
  void captureBeeName() {
    originalBeeName = System.getProperty("bee.name");
  }

  @AfterEach
  void restoreBeeName() {
    if (originalBeeName == null) {
      System.clearProperty("bee.name");
    } else {
      System.setProperty("bee.name", originalBeeName);
    }
  }
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
  void stripsUnsupportedCharactersFromSwarmId() {
    String name = BeeNameGenerator.generate("swarm-controller", "client/alpha:beta");
    assertTrue(name.startsWith("clientalphabeta-marshal-bee-"));
  }

  @Test
  void fallsBackToDefaultWhenSanitizedSwarmIdIsEmpty() {
    String name = BeeNameGenerator.generate("generator", "!!!");
    assertTrue(name.startsWith("default-seeder-bee-"));
  }

  @Test
  void returnsConfiguredBeeName() {
    System.setProperty("bee.name", "configured-bee");

    assertEquals("configured-bee", BeeNameGenerator.requireConfiguredName());
  }

  @Test
  void failsWhenBeeNameMissing() {
    System.clearProperty("bee.name");

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, BeeNameGenerator::requireConfiguredName);
    assertTrue(thrown.getMessage().contains("bee.name"));
  }

  @Test
  void failsWhenBeeNameBlank() {
    System.setProperty("bee.name", "   ");

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, BeeNameGenerator::requireConfiguredName);
    assertTrue(thrown.getMessage().contains("bee.name"));
  }
}
