# Moderator Pattern Shaper — Architecture
**Version:** 1.0 • **Date:** 2025-10-28 15:25:24Z  
**Scope:** Moderator-only shaping with constant Generator rate (ack‑pacer).  
**Broker:** RabbitMQ (durable queues, manual ACK, small prefetch).

---

## 1) Problem Statement
We must transform a constant-rate input stream (from Generator) into realistic traffic shapes (human-like or synthetic) **purely in Moderator** without inspecting payloads, cloning, or requeueing. The queue is the buffer; shaping happens by **when** the Moderator ACKs each message.

---

## 2) Top-Level Design
- **Ack‑Pacer:** Moderator consumes with small prefetch, computes a **target rate r(t)** from a configurable **Pattern**, sleeps until the next token, then **publish→ACK**.
- **Pattern = Steps + Transitions + Repeat:** A single cycle with a configurable **duration** is built from **steps** (each with its own shaping mode and mutators). The pattern can **repeat** to cover the whole run. Time can be expressed as **clock times** (HH:MM, with TZ) or **percent of pattern**.
- **Warp:** A linear time mapping that lets the pattern play faster/slower. `warpFactor: 1 = realtime; 2 = 2× faster; 12 = 12× faster (24h plays in 2h)`.
- **Determinism:** All jitter/burst randomness uses a **seeded PRNG** advanced once per decision; no message/header inspection.

---

## 3) Pattern Model
### 3.1 Time
```yaml
time:
  mode: warp            # 'realtime' or 'warp'
  warpFactor: 1         # 1=realtime; 2=2x faster; 12=12x faster (24h -> 2h); etc.
  tz: Europe/London     # used for clock-based steps and calendar alignment
```
> Warp scales **how fast** we move through the pattern. It does **not** change baseRateRps.

### 3.2 Run & Pattern
```yaml
run:
  totalTime: 30d        # how long the scenario runs (pre-warp units)

pattern:
  duration: 24h         # one cycle length (e.g., 10h, 24h, 1d, 1w)
  baseRateRps: 1000

  repeat:
    enabled: true
    until: totalTime    # or { occurrences: N }
    align: from_start   # 'from_start' (tile cycles) | 'calendar' (start at midnight / week boundary in tz)
```

### 3.3 Steps
Each **step** defines a subrange of the pattern and how to produce a **multiplier f(t)** over that subrange.

```yaml
steps:
  - id: morning
    range: { unit: clock, start: "06:00", end: "11:30" }   # or { unit: percent, startPct: 25, endPct: 40 }
    mode: ramp                                             # flat | ramp | sinus | duty
    params: { from: 0.8, to: 1.2 }
    mutators:                                              # applied in order, BEFORE transitions
      - type: cap
        min: 0.2
        max: 1.5
      - type: burst
        liftPct: 25                 # +25% during burst
        durationMs: { min: 5000, max: 15000 }    # deterministic random duration per occurrence
        everyMs:    { min: 300000, max: 600000 } # deterministic random gap between bursts
        jitterStartMs: 500          # small offset jitter per occurrence (seeded)
        seed: ph/mod/burst-01       # optional; defaults to global seed if absent
      - type: noise                 # small bounded wobble within step
        pct: 2
        seed: ph/mod/noise-01
    transition:
      type: smooth                  # none | linear | smooth
      duration: 2m                  # time (clock) or percent (% of step) depending on range.unit
```

**Modes:**
- `flat`: constant `factor`.
- `ramp`: linear `from → to` across the step (pre-transition).
- `sinus`: `center + amplitude * sin(phase + 2π * cycles * progress)`.
- `duty`: on/off pulsing via `onMs/offMs`, switching between `high/low` factors.

### 3.4 Transitions
At the tail of a step, blend from the step’s instantaneous value to the **next step’s initial** using:
- `none`: hard boundary
- `linear`: α in [0..1]
- `smooth`: α = smoothstep(u) = `3u² − 2u³`

### 3.5 Normalization (critical)
**Why:** keep totals stable regardless of shape.  
**How:** compute the **time-weighted mean** of the *raw* multiplier over **one pattern cycle** **after per-step mutators and transitions**, but **before global mutators**. If enabled, multiply **every** `f(t)` by a constant `k = 1 / mean`.  
This guarantees that **average target rate over one cycle equals `baseRateRps`**.  
Warn if `|k−1|` exceeds `tolerancePct` (operator feedback).

```yaml
normalization:
  enabled: true
  tolerancePct: 5
```

### 3.6 Global Mutators (optional)
Applied **after normalization** (intentionally changing totals), e.g. a one-off `spike`:
```yaml
globalMutators:
  - type: spike
    at: "12:00"
    width: 5m
    liftPct: 40
```

---

## 4) Target Rate and Pacing
- Final **multiplier**: `multiplier(t) = k * f_raw(t) * g(t)`  
  where `f_raw` is step+mutator+transition, `k` is normalization constant, `g` is global mutator product (or 1).
- **Target rate:** `r(t) = baseRateRps * multiplier(t)`.
- **Token bucket:** `tokens += ∫ r(t) dt` (profile time). If `tokens ≥ 1`, publish→ACK and decrement.

**Jitter (message‑agnostic):**
```yaml
jitter:
  type: sequence      # none | sequence | periodic
  maxMs: 50
  seed: ph/mod/run-001
  periodMs: 1000      # only for type=periodic
```
- PRNG advances **once per processed message**; with same seed and input order, runs are repeatable.

---

## 5) Repeat Semantics
- `from_start`: `t_in_cycle = (t_profile − start_profile) mod duration`.
- `calendar` (duration=1d/1w): align each cycle to local midnight / week boundary in `tz` so clock-based steps maintain human meaning across repeats.

---

## 6) Broker & Consumer
- **IN**: durable (lazy or quorum). **OUT**: durable.
- **Prefetch**: 1..10. Manual ACK; ACK only after successful publish.
- **No NACK/requeue for pacing.** Backlog lives in IN (disk-backed).

---

## 7) Determinism & Seeds
- One **global seed** for jitter (`jitter.seed`).
- Mutators that need randomness (e.g., `burst`) accept their own **seed**; default to the global if absent.
- All stochastic draws are from **local PRNG streams** only—no payload/header access required.

---

## 8) Metrics
- `target_rps`, `actual_rps_out`, `bucket_level`, `in_queue_depth`, `delay_ms_last`, `multiplier_now`, `norm_k`, `pattern_pos`, `step_id`.

---

## 9) Validation
- Steps cover full pattern; non-overlapping; transitions ≤ step length.
- Normalization on → average of one cycle equals baseRateRps (± tolerance).
- FIFO preserved per shard; no cloning.
- Repeatability with constant seeds and same input order.
