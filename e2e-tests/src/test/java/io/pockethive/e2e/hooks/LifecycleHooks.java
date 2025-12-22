package io.pockethive.e2e.hooks;

import io.pockethive.e2e.clients.RabbitSubscriptions;
import io.pockethive.e2e.config.EnvironmentConfig;
import io.pockethive.e2e.contracts.ControlEventsContractAudit;
import io.pockethive.e2e.contracts.ControlPlaneMessageCapture;
import io.cucumber.java.AfterAll;
import io.cucumber.java.BeforeAll;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides global hooks that will eventually bootstrap and tear down shared fixtures.
 */
public final class LifecycleHooks {

  private static final Logger LOGGER = LoggerFactory.getLogger(LifecycleHooks.class);
  private static ControlPlaneMessageCapture capture;

  private LifecycleHooks() {
  }

  @BeforeAll
  public static void beforeAll() {
    LOGGER.info("Starting PocketHive e2e harness (skeleton mode).");
    try {
      var endpoints = EnvironmentConfig.loadServiceEndpoints();
      RabbitSubscriptions rabbit = RabbitSubscriptions.from(endpoints.rabbitMq(), endpoints.controlPlane());
      capture = new ControlPlaneMessageCapture(rabbit.connectionFactory(), endpoints.controlPlane().exchange());
    } catch (IllegalStateException ex) {
      LOGGER.info("Skipping control-plane capture init: {}", ex.getMessage());
    }
  }

  @AfterAll
  public static void afterAll() {
    ControlPlaneMessageCapture current = capture;
    capture = null;
    if (current != null) {
      try {
        List<ControlPlaneMessageCapture.CapturedMessage> messages = current.messages();
        LOGGER.info("Control-plane capture collected {} message(s)", messages.size());
        ControlEventsContractAudit.assertAllValid(messages);
      } finally {
        current.close();
      }
    }
    LOGGER.info("Stopping PocketHive e2e harness (skeleton mode).");
  }
}
