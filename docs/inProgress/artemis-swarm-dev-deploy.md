# Artemis development deployment on the large Swarm

Status: deployment preparation validated, 2026-09-21; remote deployment pending.
Source baseline: `713e7559`, plus the reviewed local closeout documentation and
Swarm deployment preparation. Final deploy revision must be committed and published
before HiveForge can inspect it.

## Target and execution

- HiveForge 0.5.9, environment `swarm`, executor `portainer-stack`.
  Fresh `refresh_environment` after repair: all four nodes READY.
- Project/deployment `pockethive-development`, component `stack`, profile `swarm-full`.
- Dev Git: `http://192.168.88.50:3001/hiveforge/PocketHive.git`.
- Dev images: `192.168.88.50:3001/hiveforge/<image>:<explicit-dev-tag>`.
- Use `tools/docker/remote-images.sh` for local image preparation. Remote lifecycle,
  node inventory and diagnostics use HiveForge MCP only; no SSH or remote Docker.
- Required source ref: `codex/artemis-work-plane` (not yet present in Dev Git).
  The Git push prohibition in AGENTS.md requires an explicit task-specific exception.

## Deployment preparation

The existing Swarm template predates Artemis and does not pass WORK selection.
Add an explicit required `POCKETHIVE_WORK_TYPE`, conditionally provision the Artemis
broker using the same image/options as local Compose, and pass its connection
settings to Orchestrator. Rabbit remains CONTROL. No new transport configuration
parser or naming owner is introduced.

Artemis has one replica, stop-first updates, and a HiveForge-managed shared data
root `/hf/state/artemis/data`, rendered through the existing bind-source mapping.
Image user/group are 1001:1001 (verified locally). Do not create another host path
or require an undeclared node label. This is a single broker, not Artemis HA.

## Current deployment decisions and remaining prerequisites

- User explicitly authorized commit/publication to the Dev repository on .50;
  no GitHub push is authorized. Register the published branch in HiveForge.
- MCP/Auth values are intentionally public test fixtures, per explicit user
  clarification. Set them through the non-secret runtime configuration.
- Public Dev ingress: `https://192.168.88.50:8443`, with the intentionally public
  test certificate/key shipped under runtime/dev-tls. HTTP8088 remains available;
  acceptance uses HTTPS with explicit certificate trust, not disabled verification.
- Infrastructure repair complete: all four nodes READY, old PH removed through
  HiveForge, PH data cleared, image cache pruned with explicit user authorization.
  Repair evidence is in ~/proxmox/DOCKER-SWARM-VM-REBUILD-TASK.md.
- MCP allowed Host includes the explicit ingress port: `192.168.88.50:8443`.

## Acceptance

Through the final public ingress: smoke, Artemis HTTP lifecycle, target-state
commands, cleanup after intentional failure, delayed delivery, then NW-4 binding
recovery. NW-4 must include observed NPM/HAProxy placement across different hosts
and shared NFS behavior, not just a local-style test on a Swarm endpoint.
Record image tag, source commit, HiveForge operation ID, target, run evidence and
verified cleanup. Do not claim OAuth issuer interoperability or load capacity.


## Prepared changes and local validation

- Explicit WorkPlane selection in deploy/update and the component requirements;
  conditional Artemis service with shared state, one replica and stop-first updates.
- Existing update action lacked the public ingress/host and two MCP secret variable
  bindings used by the shared tasks; added the same bindings as deploy.
- Docker Stack rejected the existing service-level `pids_limit: 128`. Moved the
  unchanged limit to `deploy.resources.limits.pids: 128`; no limit was removed.
- Both playbooks pass Ansible syntax checking. The existing action-root contract
  check passes. All four rendered combinations (swarm-full/swarm-reduced ×
  ARTEMIS/RABBITMQ) pass `docker stack config`, with test-only credentials in
  `/tmp/ph-swarm-render`. These checks do not constitute remote readiness or deploy.
- Local image publication commands prepared by the canonical helper in dry-run
  mode (`/tmp/ph-swarm-image-plan.log`); no images or source pushed.
