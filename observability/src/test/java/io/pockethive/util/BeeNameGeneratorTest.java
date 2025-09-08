package io.pockethive.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BeeNameGeneratorTest {
  @Test
  void includesSwarmIdInName() {
    String name = BeeNameGenerator.generate("generator", "sw1");
    assertTrue(name.startsWith("sw1-seeder-bee-"));
    assertEquals(6, name.split("-").length);
  }
}
