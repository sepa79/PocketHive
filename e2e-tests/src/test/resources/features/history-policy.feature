Feature: WorkItem history policy configuration

  Background:
    Given the scenario defaults harness is initialised

  @history-policy
  Scenario: Scenario templates can choose WorkItem history policies per worker
    When I fetch the "history-policy-demo" scenario template
    Then the "generator" bee has history policy "FULL"
    And the "processor" bee has history policy "DISABLED"

