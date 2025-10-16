package io.pockethive.processor;

import io.pockethive.util.BeeNameGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationBeeNameTest {

  private String originalBeeName;

  @BeforeEach
  void captureOriginalBeeName() {
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
  void resolvesExternallyProvidedBeeName() {
    System.setProperty("bee.name", "external-processor-bee");

    assertThat(BeeNameGenerator.requireConfiguredName()).isEqualTo("external-processor-bee");
  }

  @Test
  void failsFastWhenBeeNameMissing() {
    System.clearProperty("bee.name");

    assertThatThrownBy(BeeNameGenerator::requireConfiguredName)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bee.name");
  }

  @Test
  void failsFastWhenBeeNameBlank() {
    System.setProperty("bee.name", "   ");

    assertThatThrownBy(BeeNameGenerator::requireConfiguredName)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bee.name");
  }
}
