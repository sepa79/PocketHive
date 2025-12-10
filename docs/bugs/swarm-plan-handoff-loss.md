# Swarm plan handoff can be lost on controller ready

**Area:** Orchestrator runtime (`SwarmSignalListener`, `ScenarioTimelineRegistry`)  
**Status:** Known issue (needs fix)  
**Impact:** Plan‑driven swarms may start without their scenario plan after a transient send failure.

---

## Problem

When a swarm‑controller reports `ready.swarm-controller`, the orchestrator hands off two things:

- the **swarm template** (`SwarmPlan`) via the `swarm-template` control signal, and  
- the **scenario timeline** (plan) via the `SWARM_PLAN` control signal.

The code path for the timeline looks like this (simplified):

```java
timelines.remove(controllerInstance).ifPresent(planJson -> {
    try {
        Map<String, Object> args = json.readValue(planJson, new TypeReference<>() {});
        String swarmId = plan != null ? plan.id() : info != null ? info.swarmId() : null;
        if (swarmId == null || swarmId.isBlank()) {
            log.warn("cannot send swarm-plan ... without swarm id");
            return;
        }
        String signal = ControlPlaneSignals.SWARM_PLAN;
        // build ControlSignal + sendControl(...)
    } catch (Exception e) {
        log.warn("plan send", e);
    }
});
```

Key points:

- `ScenarioTimelineRegistry.remove(controllerInstance)` **removes** the cached timeline before any parsing or AMQP send happens.
- Any of the following conditions cause the method to log and return without re‑registering the plan:
  - JSON parsing fails (`json.readValue` throws).  
  - `swarmId` cannot be resolved from the template/`Pending` info.  
  - Serialisation or `sendControl(...)` throws (e.g. transient AMQP error).
- After that, the registry no longer holds the timeline for this controller instance.

Result: the controller never receives a `SWARM_PLAN` signal, there is no retry path, and
the swarm starts without its scenario plan. From the operator’s perspective this looks like:

- swarm containers start successfully,  
- but **plan‑driven behaviour never runs**, and no obvious error is surfaced in Hive.

The bug is purely in the orchestrator runtime; editors/UI just determine which plan is stored.

---

## Conditions & scope

The issue is hit when all of these are true:

- A scenario plan has been registered for a swarm‑controller instance in `ScenarioTimelineRegistry`.
- That controller emits `ready.swarm-controller`.
- At least one of:
  - the timeline JSON is malformed or incompatible with the current `ObjectMapper`,  
  - the orchestrator cannot resolve `swarmId` from `SwarmPlan` / `SwarmCreateTracker.Pending`, or  
  - serialisation / `sendControl` fails (e.g. AMQP outage, routing issue).

The issue affects **only** the timeline (`SWARM_PLAN`) handoff; the template (`swarm-template`) uses
`SwarmPlanRegistry` and has similar one‑shot semantics but is not covered here.

---

## Expected behaviour

- The scenario timeline should remain cached until the orchestrator has **successfully handed it off**
  to the swarm‑controller (or the swarm is explicitly failed / aborted).
- Transient errors (AMQP hiccups, temporary routing problems) should not permanently drop the plan.
- If the controller restarts and emits another `ready.swarm-controller`, the orchestrator should still
  be able to send the same timeline again, unless the operator removes or replaces it.

---

## Possible fix directions (design sketch)

1. **Do not remove before successful send**

   Change the code so that:

   - `ScenarioTimelineRegistry` exposes a `find(instanceId)` method that *does not remove* the entry.  
   - `onControllerReady` reads the plan via `find` and attempts to parse + send it.  
   - Only after a successful `sendControl(...)` should the entry be explicitly removed, e.g. via
     `timelines.remove(instanceId)` in the success path.

   This makes the handoff idempotent with respect to retries: another `ready.swarm-controller`
   can safely re‑send the plan as long as it’s still in the registry.

2. **Re‑register on failure**

   If we want to keep the current `remove(...)` API, another option is:

   - Call `remove` to get the JSON.  
   - On any exception (parse, missing swarm id, send failure), re‑register the same JSON:

   ```java
   String json = timelines.remove(controllerInstance).orElse(null);
   if (json != null) {
       try {
           // parse + send
       } catch (Exception e) {
           log.warn("plan send", e);
           timelines.register(controllerInstance, json); // restore for retry
       }
   }
   ```

   This keeps the rest of the code unchanged but ensures the timeline is not permanently lost.

3. **Optional: add explicit retry / error signalling**

   In addition to (1) or (2), we may want to:

   - Emit an explicit **error event** (e.g. `error.swarm-plan`) when the send fails, so Hive can surface
     a visible error instead of silent plan loss.  
   - Introduce a small retry policy (backoff, max attempts) for sending `SWARM_PLAN` while the plan
     stays cached.

---

## Notes

- Any fix must honour existing control‑plane contracts: no new signals or routing keys without updating
  the architecture docs first.  
- Timeline JSON remains opaque at the orchestrator layer; this bug is independent of the plan’s schema
  or editor behaviour.

