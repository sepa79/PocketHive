package io.pockethive.util;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates whimsical names for service instances.
 */
public final class BeeNameGenerator {

  private static final List<String> FIRST_PARTS =
      List.of(
          "fuzzy",
          "buzzy",
          "zippy",
          "stingy",
          "waggle",
          "pollen",
          "nectar",
          "honey",
          "twirly",
          "glowy",
          "jolly",
          "snappy",
          "sparkly",
          "sunny",
          "peppery",
          "breezy",
          "amber",
          "sprightly");

  private static final List<String> SECOND_PARTS =
      List.of(
          "buzz",
          "wing",
          "stripe",
          "whirl",
          "puff",
          "wag",
          "drone",
          "comb",
          "wiggle",
          "dance",
          "flutter",
          "zing",
          "hum",
          "swirl",
          "gleam",
          "glimmer",
          "shine",
          "twist");

  private BeeNameGenerator() {}

  private static final Map<String, String> ROLE_MAP =
      Map.of(
          "generator", "seeder",
          "moderator", "guardian",
          "processor", "worker",
          "postprocessor", "forager",
          "trigger", "buzzer",
          "log-aggregator", "scribe",
          "swarm-controller", "marshal",
          "orchestrator", "queen");

  public static String generate(String role) {
    return generate(role, null);
  }

  public static String generate(String role, String swarmId) {
    String mappedRole = ROLE_MAP.getOrDefault(role.toLowerCase(), role);
    String first = randomFrom(FIRST_PARTS);
    String second = randomFrom(SECOND_PARTS);
    String id = UUID.randomUUID().toString().substring(0, 4);
    String swarm =
        sanitize(swarmId == null || swarmId.isBlank() ? "default" : swarmId, "default");
    return String.format(
        "%s-%s-bee-%s-%s-%s",
        swarm,
        sanitize(mappedRole, "bee"),
        sanitize(first, "bee"),
        sanitize(second, "bee"),
        id);
  }

  private static String randomFrom(List<String> options) {
    return options.get(ThreadLocalRandom.current().nextInt(options.size()));
  }

  private static String sanitize(String value, String fallback) {
    String sanitized = value.replaceAll("[^a-zA-Z0-9_.-]", "");
    return sanitized.isBlank() ? fallback : sanitized;
  }
}
