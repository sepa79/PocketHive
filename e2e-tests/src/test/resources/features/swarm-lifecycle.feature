Feature: Swarm lifecycle golden path

  Background:
    Given the swarm lifecycle harness is initialised
    And a default scenario template is available

  Scenario: Operators can drive the swarm lifecycle via REST and confirmations
    When I create the swarm from that template
    Then the swarm is registered and queues are declared
    When I start the swarm
    Then the swarm reports running
    When I stop the swarm
    Then the swarm reports stopped
    When I remove the swarm
    Then the swarm is removed and lifecycle confirmations are recorded
