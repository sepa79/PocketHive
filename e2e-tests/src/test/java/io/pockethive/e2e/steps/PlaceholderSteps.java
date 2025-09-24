package io.pockethive.e2e.steps;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal step definitions used by the harness skeleton placeholder feature.
 */
public class PlaceholderSteps {

  private static final Logger LOGGER = LoggerFactory.getLogger(PlaceholderSteps.class);

  @Given("the harness skeleton is initialised")
  public void theHarnessSkeletonIsInitialised() {
    LOGGER.info("PocketHive e2e harness skeleton initialised. No operations executed yet.");
  }

  @Then("no operations are performed yet")
  public void noOperationsArePerformedYet() {
    LOGGER.info("Skipping real work: the harness skeleton is intentionally idle.");
  }
}
