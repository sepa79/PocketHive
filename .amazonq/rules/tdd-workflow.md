# TDD Workflow for Scenario Bundle Development

When creating or modifying scenario bundles, follow this test-driven workflow.
All MCP tools are registered under the single `pockethive-bundles` server.

## Role-based thinking

Adopt these roles at each phase. You are one AI but think from each perspective:

- **Architect** — During requirements and design: What pipeline shape? Which workers? What data flow?
- **Developer** — During implementation: Write scenario.yaml, templates, datasets, SUT config.
- **Tester** — During verification: Is the swarm healthy? Are messages flowing? Are payloads correct?
- **DevOps** — During deployment and cleanup: Sync, create, start, stop, remove, commit.

Always state which role you are acting as when entering a new phase.

## Before modifying existing working code

**STOP before changing any template, scenario.yaml, or dataset that was previously working.**
Require concrete evidence before making any change:

1. There must be a specific error, validator failure, log message, or queue pile-up that
   justifies the change. "I think this might be wrong" is not sufficient.
2. A non-zero `matchCount` on a TCP/HTTP mock mapping is evidence the pipeline was
   working in a previous run — treat it as a green test and do not change working code.
3. If `bundle.validate` passes and the mock shows prior matches, the only valid reason
   to change a template is a new requirement or a confirmed runtime error.
4. State the specific evidence before editing any file. If no evidence exists, do not
   make the change.

## Workflow: Design → Implement → Gate → Deploy → Gate → Verify → Gate → Refine

### Phase 0: ENVIRONMENT CHECK (DevOps)

1. `git.execute branch` — extract the `*` line to resolve the current branch. Store it — every HiveMind call in this session uses it.
2. `session_start` (HiveMind) — open a session with the resolved branch, `project_id: qa-nft-pockethive-bundles`, and a concise goal. Read the startup summary for active learnings and open issues before proceeding.
3. **Consult HiveMind context** — before touching any files or tools:
   - `context.get_branch_brief` — read recent decisions, risks, and open threads on this branch.
   - `learning_get_recent` — fetch active learnings relevant to the feature/tool being worked on.
   - `issue_list` — check for open issues on this branch/feature. If a relevant open issue exists, factor it into the approach before proceeding.
4. `tools.check` — verify docker, git, maven are available.
5. `health.check` — confirm Orchestrator, Scenario Manager, RabbitMQ, and Prometheus are UP.
   Also check `pockethiveRefStale` — if `true`, run `docs.refresh` to re-sync
   reference docs from the PocketHive repo before proceeding.
3. **If targeting a remote stack** (non-localhost `POCKETHIVE_BASE_URL`):
   - `debug.docker-logs` will not work — use `debug.journal` and `debug.tap` instead.
   - `scenario.sync` will not work — use `scenario.deploy` (HTTP zip upload, works remotely).
   - `docker.execute` / `docker.compose` will not work — skip those steps.
   - `sutId` in `swarm.create` must match a bundle-local `sut/<sutId>/sut.yaml` —
     do NOT pass `sutId` for built-in scenarios that have no `sut/` directory.
4. If any service is DOWN, diagnose with `docker.compose` command `ps` and `logs`
   (local only). For remote stacks, check `debug.journal` for recent error events.
5. If the stack is not running and `POCKETHIVE_ROOT` is known, build and start it:
   - Run `./build-hive.sh --quick` in the PocketHive repo root.
     This builds jars, Docker images, and starts the full stack via docker compose.
     The `--quick` flag skips tests during the Maven build.
   - Wait for the script to complete, then `health.check` again.
   - If it still won't start, tell the user and stop.
6. **Quality gate**: All three services must be UP to proceed.

### Phase 1: DESIGN (Architect)

7. Gather requirements from the user:
   - Target system protocol (HTTP REST, SOAP/XML, TCP, ISO-8583)
   - Data source (CSV, Redis, scheduler)
   - Pipeline stages needed
   - SUT endpoints
   - Rate/volume expectations
   - Template complexity
7. Choose the pipeline pattern (consult `docs/pockethive-ref/examples/` when available).
8. Document the design decision before writing any files:
   - Pipeline: `generator → [moderator →] [request-builder →] processor → postprocessor`
   - Data flow: source → transform → target → output
   - Queue wiring plan: which `out` connects to which `in`
9. `entry_append` (HiveMind, type `decision`) — record the chosen pipeline pattern and queue wiring plan.

### Phase 2: IMPLEMENT (Developer)

9. Create bundle directory and all files:
   - `scenario.yaml` — main definition
   - `templates/<serviceId>/<callId>.yaml` — if using request-builder
   - `datasets/<file>.csv` — if using CSV input
   - `sut/<sut-id>/sut.yaml` — if using SUT references
   - `variables.yaml` — if parameterized
10. **Quality gate: Offline validation**
   - Run `bundle.validate`.
   - If FAIL: fix the reported errors and re-run. Do NOT proceed until validation passes.
   - Check: scenario.yaml `id` matches folder name.
   - Check: every `x-ph-call-id` has a matching template.
   - Check: queue wiring chains correctly (`out` → `in`).

### Phase 3: DEPLOY (DevOps)

11. `scenario.sync` — copy bundles to Scenario Manager and trigger reload.
12. `scenario.list` — verify the scenario appears.
13. `scenario.get` with the scenario ID — verify it parsed correctly.
14. **Quality gate: Scenario loaded**
    - If scenario is missing: check `id` matches folder name, re-sync.
    - If scenario has parse errors: fix and re-sync.
    - Do NOT proceed until `scenario.get` returns the expected structure.
15. `swarm.create` with `templateId`, `sutId`, `variablesProfileId`.
    - On 409: `swarm.remove` first, then retry.
16. Poll readiness — **do NOT rely solely on `swarm.wait-ready`** (it can time out the MCP
    connection for slow stacks). Use this pattern instead:
    - Call `swarm.wait-ready` with default timeout. If it returns `{ ready: true }`, proceed.
    - If it returns `{ ready: false }` or times out, poll `swarm.get` every 5s manually
      until `context.totals.healthy == context.totals.desired` and `swarmStatus == READY`.
    - Also check `debug.journal` for `swarm-health-degraded` events if workers are slow.
17. `swarm.start`.
17. Wait 5-10 seconds, then `swarm.get`.
18. **Quality gate: Swarm running**
    - Check `status: RUNNING` and `health: RUNNING`.
    - If not running: `debug.journal` for alert events, `debug.docker-logs` for errors.
    - If swarm-controller failed to start: check the scenario structure, fix, and restart from step 10.

### Phase 4: VERIFY (Tester)

19. `debug.queues` filtered by swarmId.
    - **Check: Work queues exist** with expected names.
    - **Check: Messages are flowing** (counts > 0 and not only piling up).
20. `debug.tap` on postprocessor OUT (or the last worker) with `maxItems: 3`.
21. `debug.tap.read` to inspect actual payloads.
    - **Check: Response structure** matches expectations.
    - **Check: No error responses** (HTTP 4xx/5xx, TCP connection refused).
22. `debug.tap.close`.
23. If messages aren't flowing or payloads are wrong:
    - `debug.docker-logs` for each worker role in the pipeline, starting from generator.
    - `debug.journal` for control-plane errors.
24. **Quality gate: Data plane healthy**
    - Messages flow through all queues.
    - Postprocessor receives correctly structured responses.
    - No error patterns in logs.
25. **Check metrics** (if `publish-all-metrics: true`) — `debug.prometheus` with query
    `ph_transaction_total_latency_ms{ph_swarm="<swarmId>"}`. Verify results are non-empty.
    Also try `ph_transaction_processor_success{ph_swarm="<swarmId>"}` to confirm success rate.

### Phase 5: REWORK (Developer + Tester loop)

If any quality gate in Phase 4 fails:

25. **Diagnose** — Identify the failing component using the debugging decision tree below.
26. `entry_append` (HiveMind, type `risk`) — record the failure and diagnosis before making any change.
27. **Fix** — Edit the relevant files (scenario.yaml, templates, datasets, SUT).
28. **Re-validate** — `bundle.validate` to catch offline errors.
29. **Re-deploy** — `scenario.sync`, then `swarm.stop` + `swarm.remove` + `swarm.create` + `swarm.start`.
30. **Re-verify** — Back to step 19.
31. If the fix resolves the issue, `learning_capture` (HiveMind) — record what caused the failure and what fixed it.
32. If a platform bug is confirmed, `issue_report` (HiveMind) + `github.create_issue` — track it in both places.
33. **Max rework cycles**: 3 attempts. If still failing after 3 cycles:
    - Summarize what was tried and what failed.
    - Present the diagnosis to the user for guidance.
    - Do NOT keep looping silently.

### Phase 6: DOCUMENT (Developer)

31. Write `README.md` for the bundle covering:
    - What the scenario tests
    - Pipeline flow diagram
    - Required SUT endpoints
    - Dataset format
    - Variables (if any)
    - How to run
    - Metrics to monitor
    - Cleanup steps

### Phase 7: CLEANUP (DevOps)

32. `swarm.stop` then `swarm.remove`.
33. `git.status` — review all changes.
34. `git.execute` command `add` with `args: ["bundles/<name>"]`.
35. `git.execute` command `commit` with `args: ["-m", "Add <name> scenario bundle"]`.
36. If user requests: `git.execute` command `push`.
37. `rule_check_submit` (HiveMind) — submit checks for all applicable rules (see hivemind-rules.md).
38. `entry_append` (HiveMind, type `progress`) — record the bundle as completed with a link to the commit.
39. `session_end` (HiveMind) — close the session with `status: completed`. Read the closeout report.

## Quality gate summary

| Gate | Phase | Pass criteria | Fail action |
|---|---|---|---|
| Environment | 0 | All services UP | Diagnose with docker.compose |
| Offline validation | 2 | `bundle.validate` passes | Fix and re-validate |
| Scenario loaded | 3 | `scenario.get` returns expected structure | Fix id/structure, re-sync |
| Swarm running | 3 | status=RUNNING, health=RUNNING | Check journal + logs, fix, restart |
| Data plane healthy | 4 | Messages flow, payloads correct, no errors | Enter rework loop (max 3 cycles) |

## Debugging decision tree

```
Scenario not in scenario.list?
  → Check scenario.yaml id matches folder name
  → Re-run scenario.deploy (works on local and remote stacks)

Swarm create fails (409)?
  → swarm.remove the existing swarm, then retry

Swarm create fails (500) with 'Failed to resolve SUT environment'?
  → The sutId refers to a bundle-local SUT (sut/<sutId>/sut.yaml in the bundle)
  → NOT the global sut-environments.yaml on the server
  → Either add sut/<sutId>/sut.yaml to the bundle, or omit sutId if the scenario
    has no sut.endpoints[] references that need resolving

Swarm status not RUNNING?
  → debug.journal — look for alert events and template-invalid errors
  → debug.docker-logs — check swarm-controller logs (local stacks only)
  → Common: missing SUT endpoint, bad image name, config parse error

Queues empty (no messages flowing)?
  → debug.docker-logs for the generator (local only) or debug.journal for errors
  → For CSV: verify filePath, skipHeader, rotate settings
  → For Redis: verify the list has data
  → For Scheduler: verify ratePerSec > 0

Messages stuck in a queue (piling up)?
  → The downstream worker is failing
  → debug.docker-logs for the consumer role (local only)
  → debug.journal for template-invalid or alert events (works on remote)
  → debug.tap on the stuck queue to inspect the WorkItem payload
  → Common: baseUrl wrong, template rendering error, connection refused

Processor returning errors?
  → debug.tap on processor OUT — check response status codes
  → debug.docker-logs for processor (local only)
  → Verify SUT endpoint is reachable from inside the Docker network

Template rendering errors?
  → bundle.validate to catch offline
  → debug.journal for template-invalid events (contains the error message)
  → Check Pebble syntax: {{ }} not { }
  → Check SpEL: eval('#functionName(args)') with correct quoting
  → Check field access: payload.field vs payloadAsJson.field

Need to inspect the PocketHive stack itself?
  → Local: docker.compose with command: "ps" / "logs"
  → Remote: debug.journal for control-plane events; debug.queues for queue state
```

## Tool quick reference

All tools are in the single `pockethive-bundles` MCP server.

### Scenario lifecycle

| Task | Tool |
|---|---|
| Check stack is up | `health.check` |
| List bundles | `bundle.list` |
| Read bundle file | `bundle.read` |
| Validate templates | `bundle.validate` |
| Sync to stack | `scenario.sync` |
| List loaded scenarios | `scenario.list` |
| Get scenario detail | `scenario.get` |
| Create swarm | `swarm.create` |
| Start swarm | `swarm.start` |
| Get swarm status | `swarm.get` |
| Stop swarm | `swarm.stop` |
| Remove swarm | `swarm.remove` |
| List swarms | `swarm.list` |

### Debugging

| Task | Tool |
|---|---|
| Check queues | `debug.queues` |
| Tap messages | `debug.tap` → `debug.tap.read` → `debug.tap.close` |
| Read worker logs | `debug.docker-logs` |
| Read journal | `debug.journal` |
| Send config update | `debug.config-update` |
| Query Prometheus | `debug.prometheus` |

### Dev ops

| Task | Tool | Example |
|---|---|---|
| Docker commands | `docker.execute` | `command: "ps", args: ["--filter", "name=swarm"]` |
| Docker Compose | `docker.compose` | `command: "logs", args: ["scenario-manager"]` |
| Git operations | `git.execute` | `command: "add", args: ["bundles/my-scenario"]` |
| Maven commands | `maven.execute` | `command: "verify", workingDir: "<pockethive-root>"` |
| NPM commands | `npm.execute` | `command: "install"` |
| Tool availability | `tools.check` | Returns which tools are installed |
| Git status (quick) | `git.status` | |
| Git diff (quick) | `git.diff` | |
| Refresh PocketHive docs | `docs.refresh` | Re-syncs reference docs + regenerates capabilities |

## Example: "Create a scenario for a TCP payment service"

### Phase 0 — Environment (DevOps)
- `git.execute branch` → resolve current branch (e.g. `feature/tcp-payment`)
- `session_start` (HiveMind) → open session on resolved branch, goal: "create tcp-payment bundle"
- `context.get_branch_brief` (HiveMind) → read recent decisions and risks on this branch
- `learning_get_recent` (HiveMind) → check for relevant active learnings
- `issue_list` (HiveMind) → check for open issues on this branch/feature
- `tools.check` → verify docker, git available
- `health.check` → verify stack is UP

### Phase 1 — Design (Architect)
- Ask: protocol? TCP. Format? XML. Endpoint? tcp-mock-server:8080. Data? CSV with PANs.
- Pipeline: generator(CSV) → request-builder → processor(TCP) → postprocessor
- Queue plan: `out:build` → `in:build, out:proc` → `in:proc, out:post` → `in:post`
- `entry_append` (HiveMind, `decision`) → record pipeline and queue wiring choice

### Phase 2 — Implement (Developer)
- Create `bundles/tcp-payment/scenario.yaml`
- Create `bundles/tcp-payment/templates/default/auth.yaml` (TCP template)
- Create `bundles/tcp-payment/datasets/cards.csv`
- Create `bundles/tcp-payment/sut/local-mock/sut.yaml`
- `bundle.validate` → fix until green

### Phase 3 — Deploy (DevOps)
- `scenario.sync` → `scenario.get` → verify loaded
- `swarm.create` → `swarm.start` → `swarm.get` → verify RUNNING

### Phase 4 — Verify (Tester)
- `debug.queues` → messages flowing?
- `debug.tap` on postprocessor → payloads correct?
- `debug.docker-logs` if issues

### Phase 5 — Rework if needed (Developer + Tester)
- `entry_append` (HiveMind, `risk`) → record failure before changing anything
- Fix → re-validate → re-sync → re-deploy → re-verify (max 3 cycles)
- `learning_capture` (HiveMind) → record root cause and fix if resolved

### Phase 6 — Document (Developer)
- Write `bundles/tcp-payment/README.md`

### Phase 7 — Cleanup (DevOps)
- `swarm.stop` + `swarm.remove`
- `git.execute add` + `git.execute commit`
- `rule_check_submit` (HiveMind) → submit applicable rule checks
- `entry_append` (HiveMind, `progress`) → record bundle completed + commit ref
- `session_end` (HiveMind) → close session, read closeout report
