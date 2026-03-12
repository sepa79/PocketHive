# Hive UI Topology & Editors — Plan

> Status: in progress  
> Scope: Hive UI (TopologyView, HivePage), STOMP topology client, docs (no change to control‑plane contracts)

This plan shrinks the Hive topology code into small, testable pieces and prepares the
ground for graphical editors (scenario and SUT editing) without changing runtime
behaviour.

---

## 1. Goals

- Make `TopologyView.tsx` manageable:
  - no `// @ts-nocheck`,
  - < ~400 lines of code,
  - clear separation between **data**, **layout**, and **rendering**.
- Make guard and SUT overlays easy to reason about and test.
- Reuse the same topology primitives for future **Scenario / SUT editors**.

---

## 2. Current state (baseline)

- `TopologyView.tsx`:
  - Subscribes to `subscribeTopology` and `subscribeComponents`.
  - Builds `GraphNode[]` + guard overlays and React Flow nodes/edges in one file.
  - Handles layout (fallback positioning, swarm grouping) and user interactions
    (drag, fit view, selection).
  - Uses `ShapeNode` + `SwarmGroupNode` (now extracted into `TopologyShapes.tsx`)
    as React Flow node types.
- Guard visualisation:
  - Swarm controller → generator: RPS control arrow.
  - Swarm controller → moderator input: depth guard arrow.
  - Swarm controller → processor input: backpressure arrow.

---

## 3. Refactor plan

### 3.1 Extract a pure topology builder

- [ ] Add `ui/src/pages/hive/TopologyBuilder.ts`:
  - [ ] `buildGraph(topology: Topology, componentsById: Record<string,Component>, swarmId?: string): { nodes: GraphNode[]; links: GraphLink[] }`
    - Filters / orders nodes (generator‑first BFS, unconnected tail).
    - Applies swarm filtering (`swarmId` vs “all swarms” view).
  - [ ] Export `GraphNode` / `GraphLink` types so other modules don’t re‑declare them.
- [ ] Make `TopologyView` call `buildGraph(...)` instead of building `nodes/links`
      directly inside the `subscribeTopology` effect.
- [ ] Add unit tests for `buildGraph` covering:
  - generator → moderator → processor → postprocessor chain,
  - multiple swarms,
  - SUT / WireMock nodes (treated as non‑swarm components).

### 3.2 Separate layout from graph building

- [ ] Add a small helper in `TopologyBuilder.ts` (or a sibling file):
  - [ ] `layoutGraph(graph: {nodes,links}, swarmId?: string, previousPositions?: Map<string,{x:number,y:number}>): GraphNode[]`
    - Wraps the existing `applyFallbackPositions` logic.
    - Keeps position updates pure: inputs are previous positions and the graph.
- [ ] In `TopologyView`:
  - [ ] Use `layoutGraph(...)` to get positioned `GraphNode[]`.
  - [ ] Convert them into React Flow nodes with `ShapeNodeData` / `SwarmGroupNodeData`.

### 3.3 Extract guard overlay logic

- [ ] Add a pure helper:
  - [ ] `buildGuardEdges(baseEdges: Edge[], graph: {nodes,links}, guardQueuesBySwarm, queueDepths): Edge[]`
    - Implements the three arrows (RPS, depth, backpressure) based on
      `trafficPolicy.bufferGuard` from controller status.
    - Returns a new `Edge[]` rather than mutating in place.
- [ ] Replace the in‑file guard logic in `TopologyView` with a call to `buildGuardEdges`.
- [ ] Unit tests:
  - [ ] Ensure exactly one RPS arrow from controller → generator.
  - [ ] Ensure depth/backpressure arrows target the correct consumer nodes and carry
        the expected labels (using the existing “depth 150..260 target 200” style).

### 3.4 Encapsulate React Flow layout concerns

- [ ] Introduce a tiny hook, e.g. `useTopologyLayout`:
  - [ ] Tracks `rfNodes`, `draggingIds`, and `pendingFitRef`.
  - [ ] Exposes `nodes`, `onNodesChange`, `onNodeDragStart`, `onNodeDragStop`, `onInit`.
  - [ ] Handles `fitViewToNodes` + resize observer.
- [ ] Simplify `TopologyView` to:
  - subscribe to data (topology + components),
  - call `buildGraph` → `layoutGraph`,
  - pass nodes/edges into `useTopologyLayout`,
  - render `<ReactFlow>` with the returned handlers.

### 3.5 Legend & controls as separate components

- [ ] Extract the legend and “Reset View” button into `TopologyLegend.tsx`:
  - [ ] Props: `{ types: string[], getShape(type), getFill(type), onResetView() }`.
  - [ ] Keep visuals unchanged.
- [ ] `TopologyView` only calculates node types and passes them down.

### 3.6 Type hygiene — remove `// @ts-nocheck`

- [ ] Once builder/layout/overlays are split:
  - [ ] Introduce explicit types for:
    - `GuardQueuesBySwarm`,
    - `QueueDepths`,
    - `GraphNode` / `GraphLink`.
  - [ ] Remove `// @ts-nocheck` from `TopologyView.tsx`.
  - [ ] Fix remaining type errors rather than suppressing them.

---

## 4. Non‑goals (for this refactor)

- No changes to:
  - control‑plane contracts,
  - Swarm Controller or Orchestrator behaviour,
  - guard semantics,
  - SUT / WireMock mappings.
- No new features (multi‑SUT on canvas, scenario editing); those are covered by
  `docs/toBeReviewed/scenario-sut-editor-plan.md` and related docs.

---

## 5. Next steps

- [ ] Implement 3.1–3.3 and keep `TopologyView` behaviour identical (verified via
      `TopologyView.test.tsx` and manual smoke in the Hive UI).
- [ ] Once the refactor is stable, extend the topology builder to drive future
      scenario/SUT editors so we don’t duplicate graph logic.  
