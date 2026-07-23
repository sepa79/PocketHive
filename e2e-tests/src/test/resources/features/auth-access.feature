@auth @group-auth @group-smoke
Feature: Auth and scoped PocketHive access

  Background:
    Given the auth harness is initialised

  Scenario: PocketHive APIs reject unauthenticated access
    When I call protected PocketHive APIs without credentials
    Then Scenario Manager and Orchestrator reject unauthenticated access

  Scenario: Additional protected rollout APIs reject unauthenticated access
    When I call "Scenario Manager" "GET" "/api/capabilities?all=true" without credentials
    Then the API response status is 401
    When I call "Scenario Manager" "GET" "/scenarios/bundles/workspaces" without credentials
    Then the API response status is 401
    When I call "Scenario Manager" "GET" "/scenarios/local-rest/raw" without credentials
    Then the API response status is 401
    When I call "Scenario Manager" "GET" "/network-profiles/raw" without credentials
    Then the API response status is 401
    When I call "Scenario Manager" "GET" "/sut-environments/raw" without credentials
    Then the API response status is 401
    When I call "Orchestrator" "GET" "/api/control-plane/schema/control-events" without credentials
    Then the API response status is 401
    When I call "Orchestrator" "GET" "/api/journal/hive/page?limit=1" without credentials
    Then the API response status is 401
    When I call "Network Proxy Manager" "GET" "/api/network/bindings" without credentials
    Then the API response status is 401
    When I call "Network Proxy Manager" "GET" "/api/network/proxies" without credentials
    Then the API response status is 401

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
    And I wait for the accepted lifecycle operation to succeed
    When I try to create swarm "auth-runner-bundles" from template "local-rest-topology"
    Then the create request is rejected with status 403

  Scenario: Scoped runner can read deployment-view APIs exposed by the rollout pack
    Given I authenticate as "local-e2e-runner"
    When I call "Scenario Manager" "GET" "/api/capabilities?all=true" for the active user
    Then the API response status is 200
    When I call "Scenario Manager" "GET" "/scenarios/bundles/workspaces" for the active user
    Then the API response status is 200
    When I call "Orchestrator" "GET" "/api/control-plane/schema/control-events" for the active user
    Then the API response status is 200
    When I call "Orchestrator" "GET" "/api/journal/hive/page?limit=1" for the active user
    Then the API response status is 200
    When I call "Network Proxy Manager" "GET" "/api/network/bindings" for the active user
    Then the API response status is 200
    When I call "Network Proxy Manager" "GET" "/api/network/proxies" for the active user
    Then the API response status is 200

  Scenario: Scenario Manager read surfaces stay ingress-compatible for deployment viewers
    Given I authenticate as "local-viewer"
    When I call "Scenario Manager" "GET" "/scenarios?includeDefunct=true" for the active user
    Then the API response status is 200
    When I call "Scenario Manager" "GET" "/scenarios/local-rest" for the active user
    Then the API response status is 200
    When I call "Scenario Manager" "GET" "/scenarios/local-rest/raw" for the active user
    Then the API response status is 200

  Scenario: Scenario Manager runtime, workspace writes, and deployment-wide create stay explicit
    Given the admin provisions a bundles folder admin user
    And I remember a unique value with prefix "auth-folder" as "folderPath"
    And I remember a unique value with prefix "auth-created-scenario" as "scenarioId"
    Given I authenticate as "local-viewer"
    When I call "Scenario Manager" "POST" "/scenarios/local-rest/runtime" for the active user with body
      """
      {"swarmId":"auth-runtime-denied"}
      """
    Then the API response status is 403
    When I authenticate as "local-e2e-runner"
    And I call "Scenario Manager" "POST" "/scenarios/local-rest/runtime" for the active user with body
      """
      {"swarmId":"auth-runtime-allowed"}
      """
    Then the API response status is 200
    When I authenticate as "local-bundles-folder-admin"
    And I call "Scenario Manager" "POST" "/scenarios/folders" for the active user with body
      """
      {"path":"bundles/{{value:folderPath}}"}
      """
    Then the API response status is 204
    When I authenticate as "local-viewer"
    And I call "Scenario Manager" "DELETE" "/scenarios/folders?path=bundles/{{value:folderPath}}" for the active user
    Then the API response status is 403
    When I authenticate as "local-bundles-folder-admin"
    And I call "Scenario Manager" "DELETE" "/scenarios/folders?path=bundles/{{value:folderPath}}" for the active user
    Then the API response status is 204
    When I call "Scenario Manager" "POST" "/scenarios" for the active user with body
      """
      {
        "protocolVersion": "2.0.0",
        "id": "{{value:scenarioId}}-blocked",
        "name": "Blocked Auth Scenario",
        "template": {
          "image": "swarm-controller:latest",
          "bees": []
        }
      }
      """
    Then the API response status is 403
    When I authenticate as "local-admin"
    And I call "Scenario Manager" "POST" "/scenarios" for the active user with body
      """
      {
        "protocolVersion": "2.0.0",
        "id": "{{value:scenarioId}}",
        "name": "Created Auth Scenario",
        "template": {
          "image": "swarm-controller:latest",
          "bees": []
        }
      }
      """
    Then the API response status is 201
    When I call "Scenario Manager" "DELETE" "/scenarios/{{value:scenarioId}}" for the active user
    Then the API response status is 204

  Scenario: Shared deployment config endpoints remain read-only for viewers
    Given I authenticate as "local-viewer"
    When I call "Scenario Manager" "GET" "/network-profiles/raw" for the active user
    Then the API response status is 200
    When I call "Scenario Manager" "PUT" "/network-profiles/raw" for the active user with "text/plain" body
      """
      []
      """
    Then the API response status is 403
    When I call "Scenario Manager" "GET" "/sut-environments/raw" for the active user
    Then the API response status is 200
    When I call "Scenario Manager" "PUT" "/sut-environments/raw" for the active user with "text/plain" body
      """
      []
      """
    Then the API response status is 403

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
    And I wait for the accepted lifecycle operation to succeed
    When I try to create swarm "auth-bundle-runner-blocked" from template "local-rest-defaults"
    Then the create request is rejected with status 403

  Scenario: Folder-scoped ALL can manage e2e swarm while runner cannot stop it
    Given the admin provisions an e2e folder admin user
    And I authenticate as "local-bundle-runner"
    When I try to create swarm "auth-bundle-managed" from template "local-rest"
    Then the create request is accepted
    And I wait for the accepted lifecycle operation to succeed
    And I try to stop swarm "auth-bundle-managed"
    Then the create request is rejected with status 403
    When I authenticate as "local-e2e-folder-admin"
    And I load the current auth profile
    Then the current auth profile contains grant "ALL" on "PH_FOLDER"="e2e"
    When I try to stop swarm "auth-bundle-managed"
    Then the create request is accepted
    And I wait for the accepted lifecycle operation to succeed
    When I try to remove swarm "auth-bundle-managed"
    Then the create request is accepted

  Scenario: Deployment-wide admin APIs reject folder admins and allow deployment admins
    Given the admin provisions an e2e folder admin user
    And I authenticate as "local-e2e-folder-admin"
    When I call "Orchestrator" "POST" "/api/control-plane/refresh" for the active user
    Then the API response status is 403
    When I call "Orchestrator" "POST" "/api/control-plane/reset" for the active user
    Then the API response status is 403
    When I authenticate as "local-admin"
    And I call "Orchestrator" "POST" "/api/control-plane/refresh" for the active user
    Then the API response status is 202

  # Known flaky on large-swarm: registration can complete after the current visibility timeout.
  @wip @known-flaky
  Scenario: Swarm-scoped admin APIs stay compatible with the auth rollout pack
    Given the admin provisions an e2e folder admin user
    And I authenticate as "local-bundle-runner"
    When I try to create swarm "auth-rollout-swarm" from template "local-rest"
    Then the create request is accepted
    When I wait until swarm "auth-rollout-swarm" becomes visible
    And I try to start swarm "auth-rollout-swarm"
    Then the API response status is 202
    When I fetch the swarm snapshot for "auth-rollout-swarm" via the API
    Then the API response status is 200
    And I remember the last response value at JSON pointer "/envelope/scope/instance" as "controllerInstance"
    When I authenticate as "local-e2e-folder-admin"
    And I call "Orchestrator" "POST" "/api/swarm-managers/{{swarm:auth-rollout-swarm}}/enabled" for the active user with body
      """
      {"idempotencyKey":"auth-rollout-swarm-managers","enabled":true}
      """
    Then the API response status is 202
    When I call "Orchestrator" "POST" "/api/components/swarm-controller/{{value:controllerInstance}}/config" for the active user with body
      """
      {"idempotencyKey":"auth-rollout-component-config","patch":{"enabled":true},"notes":"auth rollout e2e","swarmId":"{{swarm:auth-rollout-swarm}}"}
      """
    Then the API response status is 202
    When I call "Orchestrator" "GET" "/api/swarms/{{swarm:auth-rollout-swarm}}/journal" for the active user
    Then the API response status is 200
    When I call "Orchestrator" "POST" "/api/swarms/{{swarm:auth-rollout-swarm}}/journal/pin" for the active user with body
      """
      {"mode":"FULL","name":"auth-rollout-pin"}
      """
    Then the API response status is 200
    When I call "Orchestrator" "POST" "/api/swarms/{{swarm:auth-rollout-swarm}}/network" for the active user with body
      """
      {"networkMode":"DIRECT","idempotencyKey":"auth-rollout-network","notes":"auth rollout e2e"}
      """
    Then the API response status is 409
    When I wait until swarm journal runs are available for "auth-rollout-swarm"
    And I remember the last response value at JSON pointer "/0/runId" as "runId"
    When I call "Orchestrator" "POST" "/api/journal/swarm/runs/{{value:runId}}/meta" for the active user with body
      """
      {"testPlan":"auth-rollout","description":"folder admin should be denied","tags":["auth","rollout"]}
      """
    Then the API response status is 403
    When I authenticate as "local-admin"
    And I call "Orchestrator" "POST" "/api/journal/swarm/runs/{{value:runId}}/meta" for the active user with body
      """
      {"testPlan":"auth-rollout","description":"deployment admin update","tags":["auth","rollout"]}
      """
    Then the API response status is 200
    When I authenticate as "local-e2e-folder-admin"
    And I call "Orchestrator" "POST" "/api/debug/taps" for the active user with body
      """
      {"swarmId":"{{swarm:auth-rollout-swarm}}","role":"generator","direction":"OUT","maxItems":1,"ttlSeconds":30}
      """
    Then the API response status is 200
    And I remember the last response value at JSON pointer "/tapId" as "tapId"
    When I call "Orchestrator" "GET" "/api/debug/taps/{{value:tapId}}?drain=0" for the active user
    Then the API response status is 200
    When I authenticate as "local-bundle-runner"
    And I call "Orchestrator" "DELETE" "/api/debug/taps/{{value:tapId}}" for the active user
    Then the API response status is 403
    When I authenticate as "local-e2e-folder-admin"
    And I call "Orchestrator" "DELETE" "/api/debug/taps/{{value:tapId}}" for the active user
    Then the API response status is 200

  Scenario: Network manual override mutation remains admin-only
    Given I authenticate as "local-e2e-runner"
    When I call "Network Proxy Manager" "PUT" "/api/network/manual-override" for the active user with body
      """
      {"enabled":true,"requestedBy":"auth-rollout","reason":"runner should be denied"}
      """
    Then the API response status is 403
    When I authenticate as "local-viewer"
    And I call "Network Proxy Manager" "GET" "/api/network/manual-override" for the active user
    Then the API response status is 200
