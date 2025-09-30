Feature: Swarm lifecycle golden path

  Background:
    Given the swarm lifecycle harness is initialised
    And a default scenario template is available

  Scenario: Operators can drive the swarm lifecycle via REST and confirmations
    When I create the swarm from that template
    Then the swarm is registered and queues are declared
    When I start the swarm
    Then the swarm reports running
    And each template component reports running and enabled
    When I stop the swarm
    Then the swarm reports stopped
    And each template component reports disabled
    And the workload queues remain stable
    When I remove the swarm
    Then the swarm is removed and lifecycle confirmations are recorded
