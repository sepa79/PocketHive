@wip
Feature: WorkItem history policy configuration

  Background:
    Given the scenario defaults harness is initialised

  @history-policy
  Scenario: Scenario templates can choose WorkItem history policies per worker
    When I fetch the "history-policy-demo" scenario template
    Then the "generator" bee has history policy "FULL"
    And the "moderator" bee has history policy "LATEST_ONLY"
    And the "processor" bee has history policy "DISABLED"
    And the "postprocessor" bee has history policy "DISABLED"

  @history-policy-runtime
  Scenario: Worker statuses advertise configured history policies
    Given the swarm lifecycle harness is initialised
    And the "history-policy-demo" scenario template is requested
    When I create the swarm from that template
    Then the swarm is registered and queues are declared
    When I start the swarm
    Then the swarm reports running
    And I start generator traffic
    Then the final queue receives the default generator response
    And the postprocessor status reflects applied history policy
    And the worker statuses advertise history policies
    When I stop the swarm
    Then the swarm reports stopped
    When I remove the swarm
    Then the swarm is removed and lifecycle confirmations are recorded
