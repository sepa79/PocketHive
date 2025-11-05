Feature: Scenario defaults ensure generator rate limits are advertised

  Background:
    Given the scenario defaults harness is initialised

  @scenario-defaults
  Scenario: Local REST with named queues exposes generator rate limit
    When I fetch the "local-rest-with-named-queues" scenario template
    Then the generator bee includes a rate limit of 5 messages per second
