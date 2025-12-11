# Capacity Modeler — Notes & Future Ideas (v1 draft)

The Capacity Modeler is an experimental Hive UI tool for reasoning about
throughput, latency, and bottlenecks using a synthetic graph of services,
dependencies, and databases. This document captures the current behaviour and
ideas for future iterations.

---

## 1. Current capabilities (this branch)

- Canvas with three node types:
  - `IN` (Synthetic IN): defines offered load via TPS or client concurrency.
  - `Service`: has inbound threads, internal latency, optional DB and OUT deps.
  - `OUT`: synthetic remote dependency, with latency and optional pool (DB).
- Capacity model:
  - Per‑service `maxConcurrentIn`, transport (`tomcat`/`jetty`/`netty`),
    HTTP client (`httpclient`/`webclient`), dependency pool size, and
    dependency latency.
  - DB nodes are modeled as `OUT` nodes with `-db` suffix and pool+latency.
  - Parallel vs sequential deps:
    - When **Call OUT dependencies in parallel** is off, non‑DB deps are
      treated as sequential (latencies sum).
    - When on, non‑DB deps are treated as parallel (latencies max); DB latency
      is always added on top.
  - Per‑node bottleneck highlighting and theoretical drop rate.
- Graph‑wide propagation:
  - IN nodes can run in TPS or concurrency mode.
  - TPS is propagated through the graph, with each service capping throughput
    according to its effective capacity and downstream limits.
- Capacity explorer:
  - Small inline chart and a modal for the primary `Service` node.
  - Sweeps incoming TPS and shows effective throughput plus theoretical drop
    rate, including DB capacity limits and dep latency.
- Bee animation:
  - Builds a main path from the first `IN` through services to an `OUT`.
  - Main bee travels the full round‑trip path, including DB loops at each
    service on the chain.
  - Non‑DB deps are visualised as:
    - Parallel fan‑out when **Call OUT dependencies in parallel** is on.
    - Sequential loops on the main bee path when it is off.

---

## 2. Future ideas (to consider later)

These are known‑good directions that could make the modeler more useful without
overcomplicating the core:

### 2.1 Target‑driven “what do I need?” mode

- Let users set a target end‑to‑end TPS and compute:
  - The minimum adjustments to threads/pools/DB capacity needed to reach it.
  - Which components are currently the limiting factors and by how much.
- UI sketch:
  - A small panel with “Target TPS” input and a summary:
    - “To reach 500 req/s: increase Service 2 DB pool from 20 → 40.”

### 2.2 End‑to‑end latency + error summary

- Add a compact summary card that synthesises:
  - Effective end‑to‑end TPS.
  - Approximate end‑to‑end latency across the main path.
  - Global theoretical drop rate from the main IN.
  - Top N bottlenecks with short labels (“Service 2 DB pool saturated”).
- This should make it easier to treat the modeler as a “single glance”
  dashboard instead of reading each node.

### 2.3 Sensitivity / “what hurts most” view

- For the main service (or selected node), calculate which parameter has the
  largest impact on capacity around the current operating point:
  - Inbound threads vs dependency pool vs DB latency.
- UI sketch:
  - A small badge or mini‑chart next to sliders saying:
    - “DB latency dominant” or “Inbound threads dominant”.

### 2.4 Queueing / backlog hinting

- When offered TPS exceeds effective TPS, estimate backlog growth:
  - Simple `backlogGrowth = offeredTps − effectiveTps` per second.
  - Optional conversion into “time to fill” if a max backlog size is given.
- Surface this in:
  - Node cards (“Backlog grows by ~50 req/s at this node”).
  - The summary card (global backlog trend).

### 2.5 Scenario presets

- Provide a few built‑in starting shapes:
  - Simple REST + DB.
  - REST → async worker → DB.
  - REST → fan‑out to multiple deps → DB.
- Goal is to let users start from a realistic pattern instead of a blank canvas,
  and to communicate recommended modeling patterns.

### 2.6 Export / import of scenarios

- Add a simple JSON/YAML serialisation for the capacity graph:
  - List of nodes with their config.
  - List of edges.
- Support:
  - Export: to attach a capacity hypothesis to a PR or document.
  - Import: paste a saved sketch back into the modeler.

### 2.7 “Map to real swarm” helper

- Make it easier to connect the synthetic model to actual PocketHive swarms:
  - Allow mapping model nodes to real service IDs / worker roles.
  - Store this mapping alongside the graph (for future integrations).
- Later, we could:
  - Overlay real metrics (TPS, latency, error rate) onto the model nodes.
  - Compare measured capacity vs modeled capacity.

---

## 3. Out of scope for now

- Scenario Manager persistence for capacity graphs (would likely live under the
  Scenario Manager module as a separate feature).
- Side‑by‑side A/B comparison UI for two different configurations of the same
  graph (kept as a possible future extension).

