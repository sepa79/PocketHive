package io.pockethive.e2e.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.pockethive.e2e.support.api.ApiPlaceholderResolver;

class ApiPlaceholderResolverTest {

  @Test
  void resolvesSwarmAndRememberedValuePlaceholders() {
    ApiPlaceholderResolver resolver = new ApiPlaceholderResolver();
    String swarmId = resolver.resolveSwarmId("demo");
    resolver.rememberValue("runId", "run-123");

    String resolved = resolver.resolveTemplate("/api/swarms/{{swarm:demo}}/runs/{{value:runId}}");

    assertEquals("/api/swarms/%s/runs/run-123".formatted(swarmId), resolved);
  }

  @Test
  void rejectsUnresolvedPlaceholders() {
    ApiPlaceholderResolver resolver = new ApiPlaceholderResolver();

    assertThrows(IllegalStateException.class, () -> resolver.resolveTemplate("/api/swarms/{{swarm:missing}}"));
  }
}
