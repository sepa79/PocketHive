# Control Plane Rules (Reference)

This document is a pointer. The single source of truth for control-plane contracts lives in:

- `docs/ARCHITECTURE.md` (envelope model, routing families, command/outcome semantics, status/alert rules)
- `docs/spec/asyncapi.yaml` (channels and routing keys)
- `docs/spec/control-events.schema.json` (payload schemas)

Use those sources for any changes to control-plane topics, envelopes, or payload requirements.

Operational reminders:
- Use the shared routing utilities; do not hand-craft routing keys.
- Always emit a `data` object (use `{}` for no-arg signals).
- Preserve `correlationId` and `idempotencyKey` semantics end-to-end.
