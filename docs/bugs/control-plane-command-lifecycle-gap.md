# Control-plane commands lack a unified completion protocol

**Area:** Control-plane architecture / Orchestrator lifecycle tracking  
**Status:** Open  
**Impact:** Commands can be accepted by the Orchestrator but never reach a reliable completed/failed/timeout state.

---

## Problem

PocketHive control-plane contracts describe commands as:

- `signal.<command>...`
- followed by `event.outcome.<command>...`
- with clients watching outcomes and alerts to learn the final result

In practice, the implementation is inconsistent across command types.

Today:

- `swarm-start` is partially tracked by the Orchestrator and has a timeout path.
- `swarm-stop` is partially tracked by the Orchestrator and has a timeout path.
- `swarm-remove` expects a final `Removed` outcome, but the Orchestrator does **not** track timeout / missing outcome for remove.
- `config-update` returns `202` with an outcome watch topic, but the Orchestrator does **not** track completion at all.

This creates a gap between the contract and the runtime model:

- the system has an **accepted/published** notion,
- but it does **not** enforce a single, explicit command lifecycle for all commands.

As a result, missing outcomes lead to different failure modes depending on the command:

- some commands fail via timeout,
- some commands remain stuck indefinitely,
- some commands are effectively fire-and-forget even though the contract advertises an outcome.

---

## Observed examples

### 1. `swarm-remove` can be published and then disappear into a race

Observed on `auth-runner-e2e`:

- Orchestrator accepted `POST /api/swarms/auth-runner-e2e/remove`
- Orchestrator logged `SEND swarm-remove ...`
- the target `swarm-controller` instance started **after** the remove signal was sent
- the controller logs never showed `RECV ... swarm-remove`
- the swarm remained alive and kept publishing status deltas for many minutes

This indicates a race where a command can be published before the target queue/listener is ready.

The current Orchestrator code does not register a pending timeout for `swarm-remove`, so no timeout/error path is triggered when the outcome never arrives.

### 2. `swarm-start` is safer, but still not built on a general receive/completion model

`swarm-start` is tracked more robustly than remove:

- the Orchestrator registers a pending operation,
- there is a timeout path,
- status metrics can partially heal missing explicit outcome signals.

However, this is still ad-hoc behavior for a specific command family, not a general command lifecycle.

### 3. `config-update` advertises outcomes but is not tracked by the backend

`POST /api/components/{role}/{instance}/config` returns `202` and watch topics for:

- `event.outcome.config-update...`
- `event.alert...`

But the Orchestrator:

- does not register a pending operation,
- does not enforce timeout,
- does not change state on missing outcome,
- ignores `outcome.config-update` in `SwarmSignalListener`

So `config-update` is effectively:

- publish signal,
- return watch topic to the caller,
- forget.

If the signal is published but the outcome never arrives, the backend does not close that command in any way.

---

## Why this is an architectural bug

The issue is not limited to a single endpoint or a single queue race.

The deeper problem is that PocketHive currently lacks one consistent command lifecycle model such as:

1. command accepted by Orchestrator
2. command observed by target
3. command completed with success/failure
4. command timed out if completion never happens

Instead, different commands rely on different mixtures of:

- AMQP send only
- outcome events
- controller status heuristics
- local store transitions
- no timeout at all

This leads to the following contradictions:

- REST returns `202` and timeout metadata for commands that the backend does not actually track to timeout.
- the control-plane contract implies outcome-based completion, but some commands are not enforced as outcome-driven operations.
- idempotency can block re-send of a command even when the previous attempt never reached a reliable terminal state.

---

## Expected behavior

Every command that returns `202` should follow one explicit model:

1. Orchestrator records the command as pending.
2. The target receives the command through an explicit delivery/observation protocol.
3. The target emits a terminal completion result:
   - success
   - rejected / not ready
   - failed
4. If no terminal result arrives before deadline, the Orchestrator closes the command as timeout/failure.

At the architecture level, this likely means:

- a unified tracker for all asynchronous commands,
- explicit timeout semantics for every command type,
- consistent retry/idempotency rules tied to terminal completion,
- explicit receive acknowledgement semantics instead of assuming that `publish` plus eventual outcome is enough.

---

## Follow-up direction

This should be addressed as a larger control-plane upgrade after the current Users / Scenario work.

Planned direction:

- introduce full command receipt acknowledgement,
- unify command lifecycle tracking across all async commands,
- refactor Orchestrator / control-plane code into cleaner modules and classes around command dispatch, tracking, timeout, and completion.

This is not a short-term patch item; it is a design-level gap that should be fixed centrally rather than endpoint-by-endpoint.
