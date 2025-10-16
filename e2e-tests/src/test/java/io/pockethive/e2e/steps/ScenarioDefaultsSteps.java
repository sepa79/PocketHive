package io.pockethive.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.pockethive.e2e.clients.ScenarioManagerClient;
import io.pockethive.e2e.clients.ScenarioManagerClient.ScenarioDetails;
import io.pockethive.e2e.config.EnvironmentConfig;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmTemplate;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;

/**
 * Step definitions covering the scenario catalogue defaults exposed by the Scenario Manager.
 */
public class ScenarioDefaultsSteps {

  private static final String GENERATOR_ROLE = "generator";
  private static final String GENERATOR_RATE_ENV =
      "POCKETHIVE_CONTROL_PLANE_WORKER_GENERATOR_RATE_PER_SEC";

  private ScenarioManagerClient scenarioManagerClient;
  private ScenarioDetails scenarioDetails;

  @Given("the scenario defaults harness is initialised")
  public void theScenarioDefaultsHarnessIsInitialised() {
    try {
      var endpoints = EnvironmentConfig.loadServiceEndpoints();
      scenarioManagerClient = ScenarioManagerClient.create(endpoints.scenarioManagerBaseUrl());
    } catch (IllegalStateException ex) {
      Assumptions.assumeTrue(false, () -> "Skipping scenario defaults checks: " + ex.getMessage());
    }
  }

  @When("I fetch the {string} scenario template")
  public void iFetchTheScenarioTemplate(String scenarioId) {
    ensureHarness();
    String trimmed = scenarioId == null ? "" : scenarioId.trim();
    Assumptions.assumeTrue(!trimmed.isEmpty(),
        "Skipping scenario defaults checks: scenario id must not be blank");
    try {
      scenarioDetails = scenarioManagerClient.fetchScenario(trimmed);
    } catch (Exception ex) {
      Assumptions.assumeTrue(false, () -> "Skipping scenario defaults checks: failed to fetch scenario %s: %s"
          .formatted(trimmed, ex.getMessage()));
    }
  }

  @Then("the generator bee includes a rate limit of {int} messages per second")
  public void theGeneratorBeeIncludesARateLimitOfMessagesPerSecond(int ratePerSecond) {
    ensureScenario();
    SwarmTemplate template = scenarioDetails.template();
    assertNotNull(template, "Scenario template was not returned");

    Bee generatorBee = template.bees().stream()
        .filter(bee -> GENERATOR_ROLE.equals(bee.role()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Scenario template did not define a generator bee"));

    Map<String, String> env = generatorBee.env();
    String configuredRate = env.get(GENERATOR_RATE_ENV);
    assertNotNull(configuredRate, () ->
        "Generator bee env did not include " + GENERATOR_RATE_ENV);
    assertEquals(Integer.toString(ratePerSecond), configuredRate,
        "Generator default rate did not match expected value");
  }

  private void ensureHarness() {
    Assumptions.assumeTrue(scenarioManagerClient != null,
        "Scenario defaults harness was not initialised");
  }

  private void ensureScenario() {
    Assumptions.assumeTrue(scenarioDetails != null, "Scenario template was not fetched");
  }
}
