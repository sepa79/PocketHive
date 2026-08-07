package io.pockethive.functionalswarmdsl;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.functionalswarm.contracts.FunctionalSwarmInvocation;
import io.pockethive.functionalswarm.redis.FunctionalSwarmRedisEndpoint;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Exercises the public Functional Swarm DSL against an explicitly configured live swarm. */
@EnabledIfEnvironmentVariable(named = "POCKETHIVE_FUNCTIONAL_SWARM_LIVE", matches = "true")
class FunctionalSwarmLiveSmokeTest {
  @Test
  void invokesTheRunningFunctionalSwarm() {
    FunctionalSwarmInvoker invoker = FunctionalSwarmDsl.remote(new FunctionalSwarmRemoteConfig(
        new FunctionalSwarmRedisEndpoint(
            requiredEnvironment("POCKETHIVE_FUNCTIONAL_SWARM_REDIS_HOST"),
            requiredIntegerEnvironment("POCKETHIVE_FUNCTIONAL_SWARM_REDIS_PORT"),
            false,
            requiredIntegerEnvironment("POCKETHIVE_FUNCTIONAL_SWARM_REDIS_OPERATION_TIMEOUT_MS")),
        requiredEnvironment("POCKETHIVE_FUNCTIONAL_SWARM_REQUEST_LIST"),
        requiredEnvironment("POCKETHIVE_FUNCTIONAL_SWARM_REPLY_LIST_PREFIX"),
        Duration.ofMillis(requiredIntegerEnvironment("POCKETHIVE_FUNCTIONAL_SWARM_TIMEOUT_MS"))));

    var response = invoker.invoke(new FunctionalSwarmInvocation(
        "functional swarm live proof", Map.of("x-functional-swarm-proof", "live")));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("guarded wiremock response");
  }

  private static String requiredEnvironment(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing required environment variable: " + name);
    }
    return value.trim();
  }

  private static int requiredIntegerEnvironment(String name) {
    String value = requiredEnvironment(name);
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException ex) {
      throw new IllegalStateException("Environment variable must be an integer: " + name, ex);
    }
  }
}
