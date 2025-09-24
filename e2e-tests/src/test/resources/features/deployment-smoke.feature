Feature: Deployment smoke checks
  Validates that a freshly deployed PocketHive stack is reachable before running deeper suites.

  Background:
    Given the deployed PocketHive endpoints are configured

  Scenario: Core platform services report healthy
    When I call the platform health endpoints
    Then the orchestrator and scenario manager report UP
    And RabbitMQ is reachable
    And the UI proxy reports ok

  Scenario: No default swarm is registered immediately after deployment
    When I query the default swarm state
    Then the default swarm is not registered
