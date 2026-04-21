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

  Scenario: Admin users API can provision bundle-scoped runner
    Given the admin provisions a bundle runner user
    And I authenticate as "local-bundle-runner"
    When I load the current auth profile
    Then the current auth profile contains grant "RUN" on "PH_BUNDLE"="e2e/local-rest"
    When I list runnable templates for the active user
    Then the runnable template ids are exactly
      | local-rest |
    When I try to create swarm "auth-bundle-runner-allowed" from template "local-rest"
    Then the create request is accepted
    When I try to create swarm "auth-bundle-runner-blocked" from template "local-rest-defaults"
    Then the create request is rejected with status 403

  Scenario: Folder-scoped ALL can manage e2e swarm while runner cannot stop it
    Given the admin provisions an e2e folder admin user
    And I authenticate as "local-bundle-runner"
    When I try to create swarm "auth-bundle-managed" from template "local-rest"
    Then the create request is accepted
    And I try to stop swarm "auth-bundle-managed"
    Then the create request is rejected with status 403
    When I authenticate as "local-e2e-folder-admin"
    And I load the current auth profile
    Then the current auth profile contains grant "ALL" on "PH_FOLDER"="e2e"
    When I try to stop swarm "auth-bundle-managed"
    Then the create request is accepted
    When I try to remove swarm "auth-bundle-managed"
    Then the create request is accepted
