package io.pockethive.e2e.support;

import java.time.Duration;
import java.util.Objects;

import org.awaitility.Awaitility;

/**
 * Utility methods for polling and asserting swarm state. Implementations will be extended in later phases.
 */
public final class SwarmAssertions {

  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);

  private SwarmAssertions() {
  }

  public static Duration defaultTimeout() {
    return DEFAULT_TIMEOUT;
  }

  public static Duration defaultPollInterval() {
    return DEFAULT_POLL_INTERVAL;
  }

  /**
   * Executes the supplied assertion repeatedly until it succeeds or the default timeout is reached.
   *
   * @param description human readable description used in Awaitility diagnostics
   * @param assertion   assertion logic to execute
   */
  public static void await(String description, Runnable assertion) {
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(assertion, "assertion");
    Awaitility.await(description)
        .pollInterval(DEFAULT_POLL_INTERVAL)
        .atMost(DEFAULT_TIMEOUT)
        .untilAsserted(assertion::run);
  }
}
