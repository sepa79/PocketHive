Feature: WorkItem header separation

  Background:
    Given the swarm lifecycle harness is initialised

  @workitem-headers
  Scenario: Processor headers remain in step history only
    And the "local-rest-defaults" scenario template is requested
    When I create the swarm from that template
    Then the swarm is registered and queues are declared
    When I start the swarm
    Then the swarm reports running
    And I start generator traffic
    Then the final queue keeps processor headers in step history only
    When I stop the swarm
    Then the swarm reports stopped
    When I remove the swarm
    Then the swarm is removed and lifecycle confirmations are recorded
