# Artemis development deployment on the large Swarm

Status: deployed and ingress acceptance passed, 2026-09-21. Deployment remains
**degraded** because the Java MCP service repeatedly exits with code 1.

## Revision, target and execution

- Source commit: `4a80a0d3`, branch `codex/artemis-work-plane`.
- Source published to `http://192.168.88.50:3001/hiveforge/PocketHive.git` with
  explicit user permission. No GitHub push.
- All 19 application images published with confirmed digests through
  `tools/docker/remote-images.sh --skip-package --push`, tag
  `dev-20260921-g4a80a0d3`, registry `192.168.88.50:3001/hiveforge`.
  Existing staged Java artifacts were built and tested at parent `713e7559`;
  the deploy commit changes manifests, a public test TLS identity and documentation.
- HiveForge 0.5.9, environment `swarm`, executor `portainer-stack`.
- Project/deployment `pockethive-development`, component `stack`, profile `swarm-full`.
- Deployment ID: `deployment-080fc7b8-a6f0-496c-baa2-f3f890e62c84`.
- Successful deploy operation: `uiop-48b3b914-a373-4f97-afb9-c5dbb831ae1d`;
  action operation `op-3dca5d93-42e2-4a8e-957e-98f2218ae70d`.
- Public ingress: `https://192.168.88.50:8443`; MCP allowed Host includes `:8443`.
  Clients explicitly trust `deploy/hiveforge/runtime/dev-tls/server.crt`.
  The key and MCP/Auth values are intentionally public development fixtures.
- WORK: ARTEMIS. CONTROL: RabbitMQ. No automatic adapter switching.

## Infrastructure and manifests

Before deployment the user requested repair and full test-environment cleanup.
The .51 manager recovered after freeing full Docker/log filesystems and restarting
Docker, without a Raft reset. Old PH was removed through HiveForge; its data was
cleared and `docker system prune -af` ran on all four nodes with explicit approval.
Evidence is in `~/proxmox/DOCKER-SWARM-VM-REBUILD-TASK.md`.

HiveForge subsequently observed all four nodes READY and verified managed-root
bind visibility on every node. Deployment itself used HiveForge MCP only.

The Swarm template now explicitly selects WORK, provisions Artemis conditionally,
and passes broker settings through existing product configuration. Artemis has one
replica, stop-first updates/rollback and shared state at `/hf/state/artemis/data`,
owned by UID/GID1001. Existing bind mapping renders host-visible paths. This is not HA.
The shared update playbook received the four public/MCP settings previously only
bound by deploy. The existing MCP PID limit moved to Stack-compatible
`deploy.resources.limits.pids` without changing its value.

## Validation and review

Both Ansible playbooks pass syntax checking; action-root contract and diff checks
pass. All four combinations of swarm-full/swarm-reduced and ARTEMIS/RABBITMQ render
and pass `docker stack config`. Nginx configuration including Dev TLS passes `nginx -t`.

Review passes: plan matches explicit adapter deployment and ingress acceptance;
style retains existing Ansible/template ownership; conciseness adds no new runtime
parser or abstraction; security uses explicitly public test fixtures and real TLS
with client trust; libraries unchanged; maintainability records environment,
revision, validation and remaining limitations. Adapter topology/naming continues
through existing product owners, not deployment-side reconstruction.

## Acceptance evidence

All tests use the official HTTPS ingress and the independent acceptance framework.
Targets and a test truststore are in `/tmp/ph-swarm-acceptance-4a80a0d3`.
Persistent evidence is under `acceptance-tests/runs/`.

| Group | Result | Evidence directory |
| --- | --- | --- |
| smoke | PASS, 1 test | `platform-smoke-0d5c2fb0-17bf-4316-ab97-5be506d11714` |
| lifecycle: target state | PASS | `target-state-lifecycle-c5722401-51f1-41c6-b70e-7efc26e48068` |
| lifecycle: HTTP traffic | PASS | `http-lifecycle-045be168-3303-4f80-9632-c575c883de3d` |
| lifecycle: intentional failure cleanup | PASS | `failure-cleanup-61ed313e-c9da-4faf-bfed-0558d792c9e7` |
| delayed-delivery | PASS; 3000 ms configured, 3048/3116/3004 ms observed | `delayed-delivery-663628a4-4366-40a3-a40c-ce268293c7f2` |
| network-binding-recovery | PASS, invalid candidate rejected, previous binding and traffic retained, removal verified | `network-binding-recovery-8cdfe845-3936-4c30-a5e4-2d61abd31ca5` |

All six deployed tests passed. Final official-ingress `list-swarms` returned `[]`.
Logs: `/tmp/ph-swarm-{smoke,lifecycle,delayed,binding}.log`.

## Remaining issues and limits

- 17/18 services reached 1/1. `pockethive-mcp` remains 0/1 with repeated exit1
  on multiple managers. HiveForge's current tools do not expose service logs.
  A request for a one-time read-only SSH/service-log diagnostic exception is pending;
  no direct remote deployment workaround was used.
- An isolated local run of the same MCP image/environment, resource limits and
  correctly owned state/tmpfs directories starts successfully. This does not
  establish the remote failure's cause. The local probe was stopped afterwards.
- NPM and HAProxy both run on mgr-2. The binding test used real shared NFS but did
  **not** prove cross-host propagation. NW-4 remains PARTIAL; deliberate distinct
  placement and another run are still required.
- No OAuth client interoperability or load-capacity claim is made.
- User-requested AGENTS.md update permits Dev/test repository pushes required by
  an authorized workflow; GitHub/other destinations require separate permission.

## MCP startup diagnosis and fix — 2026-09-21

After explicit user authorization, read-only host diagnostics showed Tomcat
failing to create `/tmp/tomcat.*` with `Read-only file system`. Docker service
inspect contained only the state bind; neither configured temporary mount existed.
The template now uses explicit volume entries of type tmpfs and a private
`spool/uploads` directory created by UID10001. Local startup passes with this
configuration; the private spool has mode0700. Root filesystem remains read-only.
Four Stack render combinations and the action-root contract pass. Remote update
and effective-mount verification are pending. No Java application change required.
