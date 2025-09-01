# PocketHive Control-Plane Rules

These rules define non-negotiable contracts for emitting and consuming control-plane messages.

- MUST emit control events on `ph.control` topic exchange with routing key pattern: `ev.{kind}.{role}.{instance}`.
- MUST use `role` ∈ {`generator`,`moderator`,`processor`} — do not use ambiguous `name`.
- MUST include the envelope fields: `event`, `kind`, `version`, `role`, `instance`, `messageId`, `timestamp`.
- SHOULD include `queues.in` and/or `queues.out` in `status-full` events (and whenever bindings change).
- SHOULD send `status-delta` events every ~5 seconds with lightweight `data` (e.g., `{ tps }`).
- MUST consume signals from a dedicated durable queue `ph.control.<role>.<instance>` bound to `ph.control` with explicit topics:
  - `sig.config-update`
  - `sig.config-update.<role>`
  - `sig.config-update.<role>.<instance>`
  - `sig.status-request`
  - `sig.status-request.<role>`
  - `sig.status-request.<role>.<instance>`
- MUST respond to signals routed to `sig.config-update` and `sig.status-request` at the appropriate scope (global, role, or instance).
- MUST publish a `status-full` event upon startup and upon receiving `status-request` (at any scope).
- MAY publish `lifecycle.{state}`, `metric.*`, and `alert` events as appropriate.
- UI MUST derive graph edges strictly from `queues.in/out` when present; otherwise render minimal default.
- UI MUST treat `status-delta` as additive to the latest `status-full` snapshot.

Artifacts:
- AsyncAPI: `ui/spec/asyncapi.yaml` (channels `ev.*` and `sig.*`).
- JSON Schema: `ui/spec/control-events.schema.json`.
