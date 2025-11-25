Feature: Scenario defaults ensure generator rate limits are advertised

  Background:
    Given the scenario defaults harness is initialised

  @scenario-defaults
  Scenario: Local REST exposes generator rate limit
    When I fetch the "local-rest" scenario template
    Then the generator bee includes a rate limit of 50 messages per second

  @templating-defaults
  Scenario: Templated REST scenario enables the templating interceptor
    When I fetch the "templated-rest" scenario template
    Then the generator bee enables templating
