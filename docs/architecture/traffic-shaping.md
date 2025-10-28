# PocketHive Swarm Traffic Shaping (Simple Overview)

*A friendly, non‑technical one‑pager about how we keep load smart and safe.*

---

## What is a Swarm?
A **Swarm** is an isolated set of small workers ("bees") that generate, shape, and process traffic to a **System Under Test (SUT)**. One central brain — the **Swarm Controller** — watches the queues and sends simple config updates so everything stays safe and realistic.

```mermaid
flowchart LR
  %% Swarm left→right; queues are cylinders
  subgraph SWARM[Swarm]
    direction LR
    G["Generator<br/>(steady global rate)"] --> Q1(("Queue: Gen→Mod"))
    Q1 --> M["Moderator<br/>(admit & shapes)"]
    M  --> Q2(("Queue: Mod→Proc"))
    Q2 --> P["Processor<br/>(talks to SUT)"]
    P  -->|requests| SUT["System Under Test"]
    P  --> PP["Post-Processor<br/>(aggregates & exports)"]
  end

  %% Controller shown below, with short dashed control/telemetry lines
  C["Swarm Controller<br/>(watches queues & sends config)"]

  %% --- Styling: make queues + controller stand out ---
  classDef queue fill:#e8f4fd,stroke:#1e88e5,stroke-width:2px;
  classDef ctrl  fill:#fff3cd,stroke:#ffb703,stroke-width:2px;

  class Q1,Q2 queue;
  class C ctrl;
```

---

## Who does what (at a glance)
- **Generator** – creates messages from datasets at **one global speed** (deterministic; no local spikes/jitter).
- **Moderator** – decides how many messages **pass through** and can add safe **spikes/jitter**.
- **Processor** – talks to the SUT and emits results/metrics.
- **Post‑Processor** – aggregates results and exports summaries.
- **Swarm Controller** – the **single authority**: watches queue health and adjusts Generator speed and Moderator admit/shapes.

---

## How shaping works (plain English)
1. **Keep a small buffer** before the Moderator so shapes (spikes/jitter) never starve.
2. **Protect the SUT**: if the queue before the Processor grows, slow the Moderator.
3. **Stay balanced**: when the Moderator slows, also nudge the Generator down so the upstream buffer doesn’t explode.
4. **Recover smoothly** once healthy again (small steps, short pauses).

---

## How the buffer works (the "reservoir")
*Goal: keep a small queue before the Moderator so shaping never starves and remains smooth.*

```mermaid
flowchart LR
  %% Straight lane; queues are cylinders
  G["Generator<br/>(steady global rate)"] --> Q1(("Buffer before Moderator"))
  Q1 --> M["Moderator<br/>(admit & shapes)"] --> Q2(("Queue to Processor")) --> P["Processor"]

  %% Controller
  C["Swarm Controller"]
  C -. "watch buffer" .-> Q1
  C -. "set speed" .-> G
  C -. "set admit & shapes" .-> M

  %% Comments (styled)
  TB1["Why buffer?<br/>• Keeps Moderator fed during shapes<br/>• Smooths tiny ups/downs<br/>• Avoids bursty starvation"] -.-> Q1
  TB2["Target buffer: just enough for the next minute (configurable)"] -.-> Q1

  %% Styling
  classDef queue   fill:#e8f4fd,stroke:#1e88e5,stroke-width:2px;
  classDef ctrl    fill:#fff3cd,stroke:#ffb703,stroke-width:2px;
  classDef comment fill:#eef7ff,stroke:#4c6faf,stroke-width:1.5px,stroke-dasharray:5 3,color:#1b2a4e;

  class Q1,Q2 queue;
  class C ctrl;
  class TB1,TB2 comment;

```

---

## Prefill before a planned spike
*When a spike is scheduled soon, the Controller gently builds the buffer first.*

```mermaid
flowchart LR
  G["Generator"] --> Q1(("Buffer")) --> M["Moderator<br/>(spike starts at T)"] --> Q2(("Queue to Processor")) --> P["Processor"]
  C["Swarm Controller"]
  C -. "watch buffer" .-> Q1
  C -. "raise speed (temporarily)" .-> G

  %% Comment (styled)
  Note1["Spike scheduled soon<br/>→ build a little extra buffer now"] -.-> Q1

  %% Styling
  classDef queue   fill:#e8f4fd,stroke:#1e88e5,stroke-width:2px;
  classDef ctrl    fill:#fff3cd,stroke:#ffb703,stroke-width:2px;
  classDef comment fill:#eef7ff,stroke:#4c6faf,stroke-width:1.5px,stroke-dasharray:5 3,color:#1b2a4e;

  class Q1,Q2 queue;
  class C ctrl;
  class Note1 comment;

```

---

## Backpressure: protect the SUT and keep buffers sane
*If the queue to the Processor grows, we ease off the Moderator and align the Generator so the upstream buffer doesn’t explode.*

```mermaid
flowchart LR
  G["Generator"] --> Q1(("Buffer before Moderator")) --> M["Moderator<br/>(admit slowed)"] --> Q2(("Queue to Processor<br/>(growing)")) --> P["Processor"]
  C["Swarm Controller"]
  C -. "watch" .-> Q2
  C -. "lower admit" .-> M
  C -. "lower speed" .-> G

  %% Comments (styled)
  N1["Backpressure signal:<br/>Processor queue rising"] -.-> Q2
  N2["Controller reduces admit<br/>AND re-aligns Generator"] -.-> M

  %% Styling
  classDef queue   fill:#e8f4fd,stroke:#1e88e5,stroke-width:2px;
  classDef ctrl    fill:#fff3cd,stroke:#ffb703,stroke-width:2px;
  classDef comment fill:#eef7ff,stroke:#4c6faf,stroke-width:1.5px,stroke-dasharray:5 3,color:#1b2a4e;

  class Q1,Q2 queue;
  class C ctrl;
  class N1,N2 comment;

```

