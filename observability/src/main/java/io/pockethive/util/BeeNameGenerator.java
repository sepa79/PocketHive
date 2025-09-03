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
          "snappy");

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
          "zing");

  private BeeNameGenerator() {}

  private static final Map<String, String> ROLE_MAP =
      Map.of(
          "generator", "seeder",
          "moderator", "guardian",
          "processor", "worker",
          "postprocessor", "forager",
          "trigger", "herald",
          "log-aggregator", "scribe");

  public static String generate(String role) {
    String mappedRole = ROLE_MAP.getOrDefault(role.toLowerCase(), role);
    String first = randomFrom(FIRST_PARTS);
    String second = randomFrom(SECOND_PARTS);
    String id = UUID.randomUUID().toString().substring(0, 4);
    return String.format(
        "%s-bee-%s-%s-%s",
        sanitize(mappedRole), sanitize(first), sanitize(second), id);
  }

  private static String randomFrom(List<String> options) {
    return options.get(ThreadLocalRandom.current().nextInt(options.size()));
  }

  private static String sanitize(String value) {
    return value.replaceAll("[ .#*]", "");
  }
}

