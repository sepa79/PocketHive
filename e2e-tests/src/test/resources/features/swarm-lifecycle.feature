Feature: Swarm lifecycle golden path

  Background:
    Given the swarm lifecycle harness is initialised

  @golden-path
  Scenario: Operators can drive the swarm lifecycle via REST and confirmations
    And the "local-rest-defaults" scenario template is requested
    When I create the swarm from that template
    Then the swarm is registered and queues are declared
    When I start the swarm
    Then the swarm reports running
    And I request a single generator run
    Then the final queue receives the default generator response
    And the swarm worker statuses reflect the swarm topology
    When I stop the swarm
    Then the swarm reports stopped
    When I remove the swarm
    Then the swarm is removed and lifecycle confirmations are recorded

  @named-queues
  Scenario: Templates with named queues are honoured end to end
    And the "local-rest-with-named-queues" scenario template is requested
    When I create the swarm from that template
    Then the swarm is registered and queues are declared
    When I start the swarm
    Then the swarm reports running
    And I request a single generator run
    Then the final queue receives the default generator response
