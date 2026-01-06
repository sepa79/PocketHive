# Control Plane — Reference

This directory holds implementation notes and migration docs. The control-plane contract is defined by:

- `docs/ARCHITECTURE.md` (SSOT for envelope model, routing, and semantics)
- `docs/spec/asyncapi.yaml` (channels and routing keys)
- `docs/spec/control-events.schema.json` (payload schemas)

Use the shared routing utilities (`ControlPlaneRouting`) instead of hand-crafting routing keys.
