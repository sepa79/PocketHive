# Swarm task rejection is not surfaced as an operator-visible failure

**Area:** Docker Swarm runtime diagnostics / journal / lifecycle UX  
**Status:** Open  
**Impact:** A Swarm service task can be rejected by Docker while PocketHive reports only a missing lifecycle outcome.

## Problem

In full `SWARM_STACK` mode, dynamic worker services can fail before the worker
process starts. One confirmed case was Docker rejecting worker tasks because a
bind mount source path did not exist on the scheduled node.

The immediate operator-visible symptom was:

```text
Missing outcome for swarm-template correlation=<create-correlation>
```

That message is technically downstream of the real failure. The actual Docker
task error was only visible through `docker service ps`:

```text
invalid mount config for type "bind": bind source path does not exist: ...
```

## Expected Behavior

When a dynamic Swarm service has rejected/failed tasks during lifecycle
provisioning, PocketHive should surface that directly:

- append a Hive or Swarm journal entry with service name, role, node, task
  state, and Docker error,
- emit or expose a lifecycle failure reason tied to the original correlation id,
- make E2E diagnostics fail with the Docker task rejection instead of a generic
  missing outcome timeout,
- keep the message explicit rather than adding fallback scheduling behavior.

## Notes

This is a hardening/usability bug. The missing shared scenarios runtime mount is
handled separately as deployment configuration. This bug is only about reporting
runtime task rejection clearly.
