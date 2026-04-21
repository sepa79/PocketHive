@auth @group-auth @group-smoke
Feature: Auth and scoped PocketHive access

  Background:
    Given the auth harness is initialised

  Scenario: PocketHive APIs reject unauthenticated access
    When I call protected PocketHive APIs without credentials
    Then Scenario Manager and Orchestrator reject unauthenticated access

  Scenario: Viewer has no runnable scenario access
    Given I authenticate as "local-viewer"
    When I list runnable templates for the active user
    Then the runnable template list is empty
    When I try to create swarm "auth-viewer-e2e" from template "local-rest"
    Then the create request is rejected with status 403

  Scenario: Scoped runner can run only e2e scenarios
    Given I authenticate as "local-e2e-runner"
    When I list runnable templates for the active user
    Then all runnable templates are under folder "e2e"
    When I try to create swarm "auth-runner-e2e" from template "local-rest"
    Then the create request is accepted
    When I try to create swarm "auth-runner-bundles" from template "local-rest-topology"
    Then the create request is rejected with status 403
