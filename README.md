# PocketHive

![PocketHive logo](pockethive-logo-readme.svg)

**PocketHive** is a portable transaction swarm: compact, composable components that let you generate, moderate, process, and test workloads with clear boundaries and durable queues.

## Architecture

![Processing Flow](pockethive-flow.svg)

### Components

- **Generator** — creates events/payloads at a configurable rate.
- **Queue (A/B)** — durable FIFO buffers between stages; supports retries and dead‑letter queues; isolates backpressure.
- **Moderator** — enforces validation, limits, and policy; tags and audits messages.
- **Processor** — performs execution and scoring; produces side effects/outputs only.
- **PostProcessor** — handles **metrics, telemetry, logs, export, and archival**. This stage centralizes observability so the Processor can stay minimal.
- **Test Environment** — sandbox for A/B, simulations, and replays; bidirectional link with the Processor for rapid iteration.

### Notes

- The **PostProcessor** was added in this iteration to own all metrics/telemetry concerns.
- The diagram and the logo are SVG, resolution‑independent, and safe to embed directly in the repository.

---

_PocketHive · portable transaction · swarm_
