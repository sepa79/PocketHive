# Delivery status and follow-ups

## Current follow-up owners

- [Functional module boundaries](functional-module-boundaries.md): selected F01–F09
  implementation is complete; the document identifies remaining verification and
  separate follow-ups. It is not a new extraction plan.
- [Orchestrator correctness](orchestrator-correctness.md): evidence identity,
  catalogue discovery and guarded catalogue-only deletion are implemented.
  General recovery and disk/queue/network cleanup remain deferred.
- [ISO8583 processor](processor-iso8583-v1-v2-plan.md): processor delivery and V2 work.
- [Runtime debug and cleanup](runtime-debug-mcp-cleanup-spec.md): deferred companion
  cleanup execution and diagnostic completeness.
- [Artemis and 3DS](work-plane-artemis-3ds.md): Artemis/delayed delivery is implemented;
  the full APATA/App mock, selector/splitter and CloseLook remain deferred.

## Delivered baseline and verification

- [Rabbit/WorkPlane isolation](work-plane-module-boundaries.md) and
  [Redis extraction](redis-adapter-extraction.md) record completed delivery.
  Current contracts live in [Work Plane boundaries](../architecture/work-plane-boundaries.md)
  and [runtime responsibilities](../architecture/runtime-responsibilities.md).
- [Acceptance framework](e2e-test-system.md): replacement implemented and legacy
  framework removed. Use [the coverage matrix](../ci/acceptance-coverage.md) for scope.
- [Swarm deployment](artemis-swarm-dev-deploy.md) and
  [full Swarm acceptance](artemis-swarm-full-acceptance.md) are dated environment
  evidence, not proof of the current tree or current deployment state.
- Current MCP/IDE references: [MCP](../mcp/README.md) and
  [VS Code companion](../../vscode-pockethive/README.md).

Do not use historical test totals or publication notes as current delivery gates.
Future work belongs in [the backlog](../todo/README.md); historical material that
still has value belongs in [the archive](../archive/readme.md). Removed superseded
plans and closed review reports remain available through Git history.
