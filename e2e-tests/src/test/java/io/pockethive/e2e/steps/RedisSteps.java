package io.pockethive.e2e.steps;

import io.cucumber.java.en.Given;
import redis.clients.jedis.Jedis;

import java.util.ArrayList;
import java.util.List;

public class RedisSteps {

  private static final String REDIS_HOST_ENV = "POCKETHIVE_REDIS_HOST";
  private static final String REDIS_PORT_ENV = "POCKETHIVE_REDIS_PORT";
  private static final String DEFAULT_REDIS_HOST = "localhost";
  private static final int DEFAULT_REDIS_PORT = 6379;

  @Given("the Redis keys are cleared:")
  public void theRedisKeysAreCleared(String keysBlock) {
    List<String> keys = parseNonEmptyLines(keysBlock);
    if (keys.isEmpty()) {
      return;
    }
    try (Jedis jedis = new Jedis(redisHost(), redisPort())) {
      for (String key : keys) {
        jedis.del(key);
      }
    }
  }

  @Given("the Redis list {string} is seeded with payloads:")
  public void theRedisListIsSeededWithPayloads(String listName, String payloadsBlock) {
    List<String> payloads = parseNonEmptyLines(payloadsBlock);
    try (Jedis jedis = new Jedis(redisHost(), redisPort())) {
      jedis.del(listName);
      if (!payloads.isEmpty()) {
        jedis.rpush(listName, payloads.toArray(String[]::new));
      }
    }
  }

  private String redisHost() {
    String env = System.getenv(REDIS_HOST_ENV);
    if (env == null || env.isBlank()) {
      return DEFAULT_REDIS_HOST;
    }
    return env.trim();
  }

  private int redisPort() {
    String env = System.getenv(REDIS_PORT_ENV);
    if (env == null || env.isBlank()) {
      return DEFAULT_REDIS_PORT;
    }
    try {
      return Integer.parseInt(env.trim());
    } catch (NumberFormatException ex) {
      throw new IllegalStateException("Invalid " + REDIS_PORT_ENV + ": " + env, ex);
    }
  }

  private static List<String> parseNonEmptyLines(String block) {
    List<String> result = new ArrayList<>();
    if (block == null || block.isBlank()) {
      return result;
    }
    for (String line : block.split("\\R")) {
      String trimmed = line.trim();
      if (!trimmed.isEmpty()) {
        result.add(trimmed);
      }
    }
    return result;
  }
}
