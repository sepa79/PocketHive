Status: future / design

# Hive Graph View (Stable Single‑Swarm)

## Goal
Provide a **stable, non‑flickering** graphical view for a single swarm that
visualizes topology, status, and relationships without coupling layout to
live status events.

## Non‑Goals (MVP)
- Multi‑swarm graph.
- Auto‑layout that changes positions on every refresh.
- Realtime animation of message flow (optional later).

## UX Summary
- **Left rail:** list of bees (search + status badges).
- **Center:** topology graph (nodes + edges, stable layout).
- **Right panel:** details for selected bee (status‑full, config, IO, bindings).

## Data Sources (SSOT)
- **Static layout:** scenario topology + bees
  - `GET /scenario-manager/scenarios/{scenarioId}`
  - `template.bees[*].ports`
  - `topology.edges`
- **Live status:** control‑plane `status-full` + `status-delta` metrics
  - Used to color nodes/edges and show health, not to create/remove them.

## Anti‑Flicker Rules
- Layout is built **once** from topology and cached per swarm.
- Live updates **only modify styles** (color/labels), not structure.
- If status is missing, show **last‑known state** + “stale” badge.
- Never drop nodes just because status‑full wasn’t received yet.

## View Model (UI)
- **GraphNode**: `{ id, role, label, ports[], status?, health?, lastSeen? }`
- **GraphEdge**: `{ id, fromNodeId, fromPort, toNodeId, toPort, status? }`
- **BeeDetail**: merged view of scenario bee + latest status‑full.

## Layout Options (choose one)
- **Option A: simple SVG layout**
  - Deterministic positioning: columns by role or topo depth.
  - Lowest flicker risk, no heavy dependency.
- **Option B: Cytoscape**
  - Stable layout if locked after first render.
  - More features, medium complexity.
- **Option C: React Flow**
  - Fast UX iteration, but must lock positions to avoid reflow.

## MVP Plan (Incremental)
- [ ] Add UI v2 “Hive Graph” page (single swarm).
- [ ] Build static graph from scenario topology (no live status).
- [ ] Inject status/health badges (status‑full + status‑delta).
- [ ] Right panel: bee details (config + bindings + latest status).
- [ ] Visual edge list (topology edges) + port labels.
- [ ] Stale indicator if no updates for >N seconds.

## Follow‑ups
- [ ] Optional message sampling overlay via debug taps.
- [ ] Edge health derived from worker health (upstream/downstream).
- [ ] Graph interaction: zoom/pan, highlight connected nodes.

