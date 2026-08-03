package io.pockethive.e2e.hooks;

import io.pockethive.e2e.clients.RabbitSubscriptions;
import io.pockethive.e2e.config.EnvironmentConfig;
import io.pockethive.e2e.contracts.ControlPlaneAuditScope;
import io.pockethive.e2e.contracts.ControlEventsContractAudit;
import io.pockethive.e2e.contracts.ControlPlaneCoverageExpectations;
import io.pockethive.e2e.contracts.ControlPlaneMessageCapture;
import io.cucumber.java.AfterAll;
import io.cucumber.java.BeforeAll;
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
    ControlPlaneCoverageExpectations.reset();
    ControlPlaneAuditScope auditScope = ControlPlaneAuditScope.fromSystemProperty();
    if (auditScope.requiresAllFamilies()) {
      ControlPlaneCoverageExpectations.requireAllFamilies();
    }
    LOGGER.info("Control-plane audit scope={}", auditScope);
    var endpoints = EnvironmentConfig.loadServiceEndpoints();
    RabbitSubscriptions rabbit = RabbitSubscriptions.from(endpoints.rabbitMq(), endpoints.controlPlane());
    capture = new ControlPlaneMessageCapture(rabbit.connectionFactory(), endpoints.controlPlane().exchange());
  }

  @AfterAll
  public static void afterAll() {
    ControlPlaneMessageCapture current = capture;
    capture = null;
    if (current == null) {
      throw new IllegalStateException("Control-plane capture was not initialised");
    }
    current.close();
    var messages = current.messages();
    LOGGER.info("Control-plane capture collected {} message(s)", messages.size());
    ControlEventsContractAudit.assertAllValid(
        messages, ControlPlaneCoverageExpectations.snapshot());
    LOGGER.info("Stopping PocketHive e2e harness (skeleton mode).");
  }
}
