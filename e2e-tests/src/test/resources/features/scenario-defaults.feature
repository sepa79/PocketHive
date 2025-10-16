Feature: Scenario defaults ensure generator rate limits are advertised

  Background:
    Given the scenario defaults harness is initialised

  @scenario-defaults
  Scenario: Mock-1-with-defaults exposes generator rate limit
    When I fetch the "mock-1-with-defaults" scenario template
    Then the generator bee includes a rate limit of 5 messages per second
