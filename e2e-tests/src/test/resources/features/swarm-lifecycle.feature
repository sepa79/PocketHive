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

  @gating
  Scenario: Swarm stop is rejected before running
    And the "local-rest-defaults" scenario template is requested
    When I create the swarm from that template
    Then the swarm is registered and queues are declared
    When I request swarm stop without start
    Then the swarm-stop is rejected as NotReady
    When I start the swarm
    Then the swarm reports running
    When I stop the swarm
    Then the swarm reports stopped
    When I remove the swarm
    Then the swarm is removed after the early stop

  @templated-generator
  Scenario: Templated generator works end to end
    And the "templated-rest" scenario template is requested
    When I create the swarm from that template
    Then the swarm is registered and queues are declared
    When I start the swarm
    Then the swarm reports running
    And I start generator traffic
    Then the final queue receives the default generator response

  @scenario-variables
  Scenario: Scenario variables are resolved and visible in template rendering
    And the "variables-demo" scenario template is requested
    When I create the swarm from that template
    Then the swarm is registered and queues are declared
    When I start the swarm
    Then the swarm reports running
    And I start generator traffic
    Then the final queue receives the default generator response

  @tcp-timeout
  Scenario: TCP processor reports timeout when tcp-mock delays response
    And the TCP mock server has the following mappings:
      """
      {
        "id": "tcp-timeout-delay",
        "requestPattern": "^STX.*ETX$",
        "responseTemplate": "STXTIMEOUT|{{timestamp}}ETX",
        "responseDelimiter": "",
        "fixedDelayMs": 6000,
        "description": "Delay response beyond processor timeout for e2e",
        "priority": 40,
        "enabled": true
      }
      """
    And the "tcp-socket-demo" scenario template is requested
    When I create the swarm from that template
    Then the swarm is registered and queues are declared
    When I start the swarm
    Then the swarm reports running
    And I start generator traffic
    Then the final queue reports a processor error
    And the TCP mock mapping "tcp-timeout-delay" is removed
    When I stop the swarm
    Then the swarm reports stopped
    When I remove the swarm
    Then the swarm is removed and lifecycle confirmations are recorded

  Scenario: Worker runtime config matches service defaults when scenario provides none
    And the "local-rest-defaults" scenario template is requested
    When I create the swarm from that template
    Then the swarm is registered and queues are declared
    When I start the swarm
    Then the swarm reports running
    And the worker status snapshots include config only in status-full
    And the status-full snapshots include runtime metadata
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
  Scenario: Redis dataset demo flows through Request Builder and Processor
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

  @redis-dataset-payloads
  Scenario: Redis dataset demo renders payloads and templates end to end
    And the "redis-dataset-demo" scenario template is requested
    When I create the swarm from that template
    Then the swarm is registered and queues are declared
    When I start the swarm
    Then the swarm reports running
    And I start generator traffic
    Then the redis dataset demo payloads are fully rendered
    When I stop the swarm
    Then the swarm reports stopped
    When I remove the swarm
    Then the swarm is removed and lifecycle confirmations are recorded

  @webauth-loop
  Scenario: WebAuth 5-customers loop processes Redis traffic through processor
    And the TCP mock request journal is cleared
    And the Redis keys are cleared:
      """
      webauth.RED.custA
      webauth.RED.custB
      webauth.RED.custC
      webauth.RED.custD
      webauth.RED.custE
      webauth.BAL.shared
      webauth.TOP.shared
      """
    And the Redis list "webauth.RED.custA" is seeded with payloads:
      """
      {"Customer":"custA","AccountNumber":"86010100418512","Amount":"10"}
      """
    And the Redis list "webauth.RED.custB" is seeded with payloads:
      """
      {"Customer":"custB","AccountNumber":"86010100418520","Amount":"20"}
      """
    And the Redis list "webauth.RED.custC" is seeded with payloads:
      """
      {"Customer":"custC","AccountNumber":"86010100418538","Amount":"30"}
      """
    And the Redis list "webauth.RED.custD" is seeded with payloads:
      """
      {"Customer":"custD","AccountNumber":"86010100418546","Amount":"40"}
      """
    And the Redis list "webauth.RED.custE" is seeded with payloads:
      """
      {"Customer":"custE","AccountNumber":"86010100418554","Amount":"50"}
      """
    And the "webauth-loop-redis-5-customers" scenario template is requested
    When I create the swarm from that template
    Then the swarm is registered and queues are declared
    When I start the swarm
    Then the swarm reports running
    And I start generator traffic
    And the TCP mock eventually has at least 1 requests
    When I stop the swarm
    Then the swarm reports stopped
    When I remove the swarm
    Then the swarm is removed and lifecycle confirmations are recorded

  @plan-demo
  Scenario: Scenario plan drives swarm lifecycle
    And the "local-rest-plan-demo" scenario template is requested
    When I create the swarm from that template
    Then the swarm is registered and queues are declared
    When I start the swarm
    Then the swarm reports running
    And the plan demo scenario plan drives the swarm lifecycle
