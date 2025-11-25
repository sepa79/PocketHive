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
    And I start generator traffic
    Then the final queue receives the default generator response
    And the swarm worker statuses reflect the swarm topology
    When I stop the swarm
    Then the swarm reports stopped
    When I remove the swarm
    Then the swarm is removed and lifecycle confirmations are recorded

  @templated-generator
  Scenario: Templated generator works end to end
    And the "templated-rest" scenario template is requested
    When I create the swarm from that template
    Then the swarm is registered and queues are declared
    When I start the swarm
    Then the swarm reports running
    And I start generator traffic
    Then the final queue receives the default generator response

  Scenario: Worker runtime config matches service defaults when scenario provides none
    And the "local-rest-defaults" scenario template is requested
    When I create the swarm from that template
    Then the swarm is registered and queues are declared
    When I start the swarm
    Then the swarm reports running
    And the generator runtime config matches the service defaults
    And the moderator runtime config matches the service defaults
    And the processor runtime config matches the service defaults
    And the postprocessor runtime config matches the service defaults

  Scenario: Worker runtime config matches overrides from local-rest
    And the "local-rest" scenario template is requested
    When I create the swarm from that template
    Then the swarm is registered and queues are declared
    When I start the swarm
    Then the swarm reports running
    And the generator runtime config matches the local-rest scenario
    And the generator IO config matches the local-rest scenario
    And the moderator runtime config matches the local-rest scenario
    And the processor runtime config matches the local-rest scenario
    And the postprocessor runtime config matches the local-rest scenario

  @redis-dataset-demo
  Scenario: Redis dataset demo flows through HTTP Builder and Processor
    And the "redis-dataset-demo" scenario template is requested
    When I create the swarm from that template
    Then the swarm is registered and queues are declared
    When I start the swarm
    Then the swarm reports running
    And I start generator traffic
    Then the redis dataset demo pipeline processes traffic end to end
    When I stop the swarm
    Then the swarm reports stopped
    When I remove the swarm
    Then the swarm is removed and lifecycle confirmations are recorded
