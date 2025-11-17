package io.pockethive.e2e.steps;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.pockethive.e2e.config.EnvironmentConfig;
import io.pockethive.e2e.config.EnvironmentConfig.ServiceEndpoints;
import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal step definitions used by the harness skeleton placeholder feature.
 */
public class PlaceholderSteps {

  private static final Logger LOGGER = LoggerFactory.getLogger(PlaceholderSteps.class);

  private ServiceEndpoints endpoints;

  @Given("the harness skeleton is initialised")
  public void theHarnessSkeletonIsInitialised() {
    try {
      endpoints = EnvironmentConfig.loadServiceEndpoints();
    } catch (IllegalStateException ex) {
      Assumptions.assumeTrue(false, () -> "Skipping harness skeleton: " + ex.getMessage());
    }
    LOGGER.info("PocketHive e2e harness skeleton initialised with endpoints: {}", endpoints.asMap());
  }

  @Then("no operations are performed yet")
  public void noOperationsArePerformedYet() {
    Assumptions.assumeTrue(endpoints != null, "Harness skeleton was not initialised");
    LOGGER.info("Harness skeleton check completed. No additional operations executed.");
  }
}
