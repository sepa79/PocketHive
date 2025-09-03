package io.pockethive.util;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates whimsical names for service instances.
 */
public final class BeeNameGenerator {

  private static final List<String> ROLES =
      List.of("Worker", "Forager", "Builder", "Scout", "Guard", "Nurse", "Drone", "Queen");

  private static final List<String> FUNNY_NAMES =
      List.of(
          "Buzz",
          "Bumble",
          "Zippy",
          "Sting",
          "Waggle",
          "Pollenator",
          "Nectar",
          "Fuzzy");

  private BeeNameGenerator() {}

  public static String generate() {
    String role = randomFrom(ROLES);
    String funny = randomFrom(FUNNY_NAMES);
    String id = UUID.randomUUID().toString().substring(0, 4);
    return String.format("%s Bee %s-%s", role, funny, id);
  }

  private static String randomFrom(List<String> options) {
    return options.get(ThreadLocalRandom.current().nextInt(options.size()));
  }
}

