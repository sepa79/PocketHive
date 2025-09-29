
# Scenario Builder UI – MVP Implementation Checklist

## Phase 1: Assets & Wiring (Scenario Manager is authoritative)
- [ ] API client for Scenario Manager (`/scenarios`, `/runs`)
- [ ] CRUD screens: SUTs, Datasets, Swarm Templates
- [ ] Dataset providers supported: Redis, CSV (path), Inline list (dev-only)
- [ ] Processor placeholders for auth (UI fields disabled with tooltip)

## Phase 2: Timeline Composer
- [ ] Timeline canvas (tracks per swarm instance group)
- [ ] Blocks: Ramp, Hold, Spike, Pause, Signal, WaitFor
- [ ] Drag/drop, resize, snap-to-grid, duplicate
- [ ] Right-side Inspector for selected entity (Scenario/Track/Block)
- [ ] Inter-swarm coordination: `Signal` and `WaitFor`

## Phase 3: Validation & Dry-Run
- [ ] Local math: effective rate per second; cumulative message count
- [ ] Dataset consumption estimation (rows/sec checking exhaustion warnings)
- [ ] Conflict checks (gaps/overlaps, invalid WaitFor w/o Signal, negative durations)

## Phase 4: Apply & Runs
- [ ] Export (download JSON), Save (POST /scenarios)
- [ ] Apply saved or inline (`POST /scenarios/{id}/apply` or `/scenarios/apply`)
- [ ] Run list & details (status, log links, SSE stream)
- [ ] Cancel run

## Charts (always-on in Inspector/Preview)
- [ ] TPS over time (sum + per-track)
- [ ] Concurrent connections estimate
- [ ] Per-SUT request volume split
