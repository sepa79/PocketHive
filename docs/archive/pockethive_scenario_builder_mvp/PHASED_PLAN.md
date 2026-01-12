
# PocketHive Scenario Builder — Overall MVP Phased Plan
Generated: 2025-09-29T13:42:29.089079Z

> **Superseded**  
> This phased plan is kept for historical context. For current Scenario editor behaviour and gaps, see `docs/scenarios/SCENARIO_EDITOR_STATUS.md`.

## Phase A — Contract & Schemas (Done in this pack)
- Single-source **Orchestrator→Controller** contract: `contracts/orchestrator_controller_contract_v0/contract.md`
- Shared **Scenario Block Schema** (hold/ramp/pause with strict fields) used by UI & Scenario Manager

## Phase B — Scenario Manager (Backend)
- CRUD + strict validation
- Apply by id or inline -> returns runId
- Runs read model + SSE
- Storage: Postgres tables for scenario, versions, runs, events

## Phase C — Scenario Builder UI (Frontend)
- Micro-frontend integration into shell under `/scenario/*`
- Timeline composer (tracks, blocks), validation, export
- Save to Scenario Manager; Apply via Orchestrator start endpoint
- Basic run monitor (list + event tail)

## Phase D — Orchestrator Wiring
- Fetch scenario by id; expand to swarms
- Send one Plan per swarm (`sig.config-update.{swarmId}` with kind=plan)
- Send Start to each swarm; optional Stop at end/cancel

## Phase E — Hardening
- Contract conformance tests (accept/reject)
- E2E: UI -> Manager -> Orchestrator -> Controller (mocked first, then real)
- Observability tags: runId, planId, swarmId

## Milestones
- M1: Contract finalized & published
- M2: Scenario Manager CRUD + validation live
- M3: UI timeline + save/export working
- M4: Apply flow (end-to-end with mocks)
- M5: Live run with Controller executing a real plan
