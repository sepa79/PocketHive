Status: draft

# Ninja Bee — Composable Pipeline Worker (Branching) + Capabilities Model

## Goal
Introduce a single “composable” worker (**Ninja Bee**) that can execute a **configurable pipeline** per incoming WorkItem, including **branching**, to avoid needing many single-purpose workers for short, multi-step transactions.

This document focuses on the **Capabilities** approach needed to support step-level configuration and UI editing.

## Non-goals (v1)
- Arbitrary code execution / scripting.
- Loop constructs (best-effort scheduling/gating is allowed, but no “while”/unbounded loops in the pipeline definition).
- Reintroducing multiple independent capability definitions for the same behavior (no duplicate SSOT).

## Problem statement
Today, capabilities are effectively “all config fields for a worker image”. This breaks down for Ninja Bee because:
- configuration becomes **per pipeline step**, not “one flat worker config”;
- a single Ninja Bee instance may run multiple operations (builder/processor/scheduler logic) in a chosen order.

## Concept summary (runtime)
- Input remains as-is: for each received WorkItem, the worker runs the full pipeline.
- Branching is supported: a step can decide the next step/output route.
- Interceptors remain as they are.
- The worker appends step results to the WorkItem (step history) as it progresses through the pipeline.

## Capabilities model (proposal)
Ninja Bee uses a **single worker manifest** (per image) that contains:
1) **Global worker capabilities** (as today): options not tied to a particular step type.
2) A catalog of **step type capabilities**: each supported step type declares its own schema/config entries and routing/outcome surface.

### Manifest shape extension (draft)
Extend the capability manifest with a new optional field (name TBD; pick one and standardize):
- `pipelineSteps`: array of step type definitions

Each step type definition:
- `type`: stable identifier used in scenario/pipeline config (e.g. `http.request`, `scheduler.gate`)
- `label`: UI label
- `config`: config entries for this step type (same entry shape as existing `config[]`)
- `actions` / `panels`: optional, same semantics as existing manifest (but scoped to step type)
- `outcomes` or `outputs`: declarative branching surface for UI (see below)

Example (illustrative only):
```json
{
  "role": "ninja-bee",
  "image": { "name": "…", "tag": "…", "digest": "…" },
  "config": [{ "name": "metrics.enabled", "type": "boolean" }],
  "pipelineSteps": [
    {
      "type": "http.request",
      "label": "HTTP Request",
      "config": [
        { "name": "request.method", "type": "string", "options": ["GET","POST"] },
        { "name": "request.urlTemplate", "type": "string", "multiline": true }
      ],
      "outcomes": ["2xx", "4xx", "5xx", "error", "default"]
    }
  ]
}
```

### Branching: `outcomes` vs named outputs
We need a stable, declarative surface so UI can offer routing without guessing.

Two viable approaches:
- **Outcome labels**: small fixed set per step type (`2xx`, `4xx`, `error`, `default`, etc.). Simple for UI and config.
- **Named outputs (ports)**: step types declare `outputs[]` with stable ids (similar to ports). Better long-term if we want explicit routing between outputs and multiple downstream branches.

Pick one per contract and keep it SSOT; do not support both shapes without a versioned migration plan.

## SSOT constraints (important)
To avoid “two independent lists of fields” for the same behavior:
- Ninja Bee step capabilities must be the **only** maintained capabilities for the behaviors it absorbs (e.g., generator/template/builder/processor logic).
- Any remaining standalone workers (e.g. moderator/trigger/postprocessor) keep their own manifests as-is.
- Do not duplicate field definitions across multiple manifests for the same operation.

## UI v2 implications
- UI resolves Ninja Bee capabilities by image (as today) and additionally reads `pipelineSteps[]`.
- UI pipeline editor:
  - step picker comes from `pipelineSteps[].type`
  - step config form comes from `pipelineSteps[].config`
  - branching UI is driven by `pipelineSteps[].outcomes` or `pipelineSteps[].outputs`
- Runtime “Details” view:
  - shows pipeline config (from worker config) and per-step execution traces (from WorkItem step history / runtime status), without guessing.

## Open questions
- Contract for pipeline definition storage: where and how the pipeline config is stored (scenario template vs worker config vs dedicated object).
- How step-level outcomes map to output routing keys/queues for multi-output fan-out.
- How “best-effort pacing” (scheduler/gate step) should be expressed and surfaced in status.

