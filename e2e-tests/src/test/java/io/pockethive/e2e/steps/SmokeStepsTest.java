package io.pockethive.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SmokeStepsTest {

  @Test
  void detectsLocalRabbitHostnames() {
    assertTrue(SmokeSteps.isLikelyLocalRabbitHost("localhost"));
    assertTrue(SmokeSteps.isLikelyLocalRabbitHost("127.0.0.1"));
    assertTrue(SmokeSteps.isLikelyLocalRabbitHost("[::1]"));
    assertTrue(SmokeSteps.isLikelyLocalRabbitHost("0.0.0.0"));
  }

  @Test
  void nonLocalRabbitUrisAreNotMarkedAsLocal() {
    assertFalse(SmokeSteps.isLikelyLocalRabbitHost("rabbitmq.internal"));
    assertFalse(SmokeSteps.isLikelyLocalRabbitHost("10.0.0.5"));
  }

  @Test
  void invalidUrisDoNotTriggerLocalClassification() {
    assertFalse(SmokeSteps.isLikelyLocalRabbitHost("   "));
    assertFalse(SmokeSteps.isLikelyLocalRabbitHost(""));
    assertFalse(SmokeSteps.isLikelyLocalRabbitHost(null));
  }
}
