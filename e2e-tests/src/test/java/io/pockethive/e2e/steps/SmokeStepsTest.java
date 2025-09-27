package io.pockethive.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SmokeStepsTest {

  @Test
  void detectsLocalRabbitHostnames() {
    assertTrue(SmokeSteps.isLikelyLocalRabbitUri("amqp://guest:guest@localhost:5672/"));
    assertTrue(SmokeSteps.isLikelyLocalRabbitUri("amqp://guest:guest@127.0.0.1:5672/"));
    assertTrue(SmokeSteps.isLikelyLocalRabbitUri("amqp://guest:guest@::1:5672/"));
    assertTrue(SmokeSteps.isLikelyLocalRabbitUri("amqp://guest:guest@0.0.0.0:5672/"));
  }

  @Test
  void nonLocalRabbitUrisAreNotMarkedAsLocal() {
    assertFalse(SmokeSteps.isLikelyLocalRabbitUri("amqp://guest:guest@rabbitmq.internal:5672/"));
    assertFalse(SmokeSteps.isLikelyLocalRabbitUri("amqp://guest:guest@10.0.0.5:5672/"));
  }

  @Test
  void invalidUrisDoNotTriggerLocalClassification() {
    assertFalse(SmokeSteps.isLikelyLocalRabbitUri("not-a-uri"));
    assertFalse(SmokeSteps.isLikelyLocalRabbitUri(""));
    assertFalse(SmokeSteps.isLikelyLocalRabbitUri(null));
  }
}
