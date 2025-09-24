package io.pockethive.e2e.hooks;

import io.cucumber.java.AfterAll;
import io.cucumber.java.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides global hooks that will eventually bootstrap and tear down shared fixtures.
 */
public final class LifecycleHooks {

  private static final Logger LOGGER = LoggerFactory.getLogger(LifecycleHooks.class);

  private LifecycleHooks() {
  }

  @BeforeAll
  public static void beforeAll() {
    LOGGER.info("Starting PocketHive e2e harness (skeleton mode).");
  }

  @AfterAll
  public static void afterAll() {
    LOGGER.info("Stopping PocketHive e2e harness (skeleton mode).");
  }
}
