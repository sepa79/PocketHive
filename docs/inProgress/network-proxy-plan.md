# Network Proxy Plan

> Status: in progress
> Scope: Scenario Manager, Orchestrator, Swarm Controller, UI V2, external proxy control plane

This plan introduces a controlled network-fault layer for PocketHive runs so swarms can route SUT traffic through a proxy stack and apply fault profiles without hand-editing existing scenarios.

The design target is explicit:

- `HTTPS` and `TCPS` are mandatory.
- v1 is shared per SUT, not isolated per swarm.
- existing SUT-aware scenarios should continue to use normal `sut.endpoints[...]` authoring.
- `DIRECT` means today's behavior: no proxy on the path.
- there are no implicit fallbacks or silent protocol downgrades.

## 1. Problem statement

PocketHive already models SUT endpoints and resolves them during `swarm-create`, but today there is no first-class way to:

- route traffic through a controllable proxy layer,
- switch a swarm onto a network-fault profile at start time,
- change that profile at runtime from UI or scenario plan,
- support secure downstreams (`HTTPS`, `TCPS`) without breaking hostname validation.

Simple `baseUrl -> toxiproxy-host:port` rewrites are not sufficient for secure traffic because TLS clients validate hostname and often rely on SNI. The proxy layer therefore must preserve the client-visible authority used by workers.

## 2. Design goals

- Keep scenario authoring stable for SUT-aware templates.
- Make proxy usage a swarm/runtime choice, not a worker-code concern.
- Treat fault profiles as explicit versioned config, not raw ad-hoc Toxiproxy payloads.
- Expose both UI control and API control.
- Keep v1 blast radius explicit: one proxied SUT endpoint can affect multiple swarms that share that SUT binding.

## 3. Non-goals

- Per-swarm network isolation in v1.
- Transparent support for non-SUT literal URLs embedded in worker config.
- Replacing the SUT model redesign already planned elsewhere.
- Letting workers talk directly to Toxiproxy or know anything about toxics.

## 4. Architecture summary

v1 uses a shared proxy layer per SUT endpoint:

1. PocketHive resolves a SUT as usual.
2. If proxying is enabled, PocketHive binds the chosen SUT to a `NetworkProfile`.
3. When proxying is enabled, the resolved client endpoint points at a proxy-facing authority that preserves `HTTPS` / `TCPS` semantics.
4. A dedicated proxy control service programs the proxy stack and exposes a PocketHive-specific API.
5. UI V2 and scenario-plan runtime steps operate on `NetworkProfile` ids, not raw Toxiproxy toxics.

Planned proxy stack:

- `HAProxy` in TCP passthrough mode for secure authority-preserving ingress.
- `Toxiproxy` as the fault engine for delay, timeout, reset, bandwidth, and related TCP-level faults.
- `network-proxy-manager-service` as the PocketHive adapter and SSOT for bindings and active profiles.

## 5. Core model

The proxy project must depend only on the following runtime facts:

- `sutId`
- endpoint id
- protocol/kind
- client-visible authority used by workers
- upstream authority to reach the real test environment

This is the compatibility boundary with the planned SUT redesign. The SUT subsystem may change how these values are stored, but the proxy manager should consume one canonical resolved shape and remain agnostic to the backing YAML/schema evolution.

### 5.0 Secure authority model

For secure downstreams we need two explicit addresses for the same logical endpoint.

- `client-visible authority`
  - the authority used by PocketHive workers
  - for `HTTPS` / `TCPS` this is the host and port the client believes it is connecting to
  - this authority drives TLS hostname verification and usually SNI
- `real upstream authority`
  - the actual backend host and port the proxy stack forwards TCP to
  - this may be an internal LB, private DNS name, node IP, or other non-public target

Example:

- worker config uses `https://api.testenv.company.com/payments`
- `client-visible authority = api.testenv.company.com:443`
- `real upstream authority = internal-alb-123.eu-west-1.elb.amazonaws.com:8443`

PocketHive workers must only see the client-visible address. The proxy manager and proxy stack must know both values.

`TCPS` support in v1 is intentionally narrower than `HTTPS`:

- each proxied `TCPS` service must use its own dedicated `ip:port`,
- v1 routing for `TCPS` may differentiate services by listener port alone,
- `SNI` is not required for `TCPS` in v1,
- multi-service `TCPS` fan-in behind one shared secure listener is deferred beyond v1.

Recommended canonical resolved endpoint shape:

- `endpointId`
- `kind`
- `clientBaseUrl`
  - full URL handed to workers, for example `https://api.testenv.company.com`
- `clientAuthority`
  - host:port derived from `clientBaseUrl`, for example `api.testenv.company.com:443`
- `upstreamAuthority`
  - host:port known only to proxy control, for example `internal-alb-123.eu-west-1.elb.amazonaws.com:8443`

Rules:

- In `DIRECT` mode, workers still use `clientBaseUrl`; no proxy binding exists.
- In `PROXIED` mode, workers continue to use `clientBaseUrl`; only the routing path changes.
- `clientAuthority` and `upstreamAuthority` may be identical for simple environments.
- `clientAuthority` and `upstreamAuthority` must be allowed to differ for `HTTPS` / `TCPS`.

v1 simplification for `TCPS`:

- when a `TCPS` endpoint is modeled as a dedicated `ip:port`, `clientAuthority` and routing selection may rely on that port-level uniqueness,
- hostname-based differentiation is not required for those endpoints in v1,
- future work may extend this to shared listeners and `SNI`-aware routing if needed.

Compatibility guidance with SUT redesign:

- the current `SutEndpoint.baseUrl` model is not sufficient as the long-term secure-routing contract on its own,
- the SUT redesign should expose one canonical resolved endpoint DTO that carries both client-facing and upstream routing data,
- the proxy manager should depend on that resolved DTO, not on raw SUT YAML layout.

Ownership decision:

- code owner for `ResolvedSutEndpoint` and `ResolvedSutEnvironment` is `common/swarm-model`,
- semantic owner is the SUT resolution flow in orchestrator,
- `network-proxy-manager-service` is a consumer of the resolved model, not its owner.

### 5.1 NetworkProfile

New first-class contract, separate from SUT:

- `id`
- `name`
- `faults[]`
  - explicit typed faults such as `latency`, `timeout`, `reset-peer`, `bandwidth`
- `targets`
  - endpoint ids or endpoint groups the profile applies to

Profiles live in their own registry. They are not embedded into SUT definitions.

Profiles are only used when a swarm runs in `PROXIED` mode. There is no `direct` profile. If a no-fault proxy path is needed, define an explicit profile such as `passthrough`.

### 5.2 NetworkBinding

Runtime binding between a swarm and the selected network behavior:

- `swarmId`
- `sutId`
- `networkMode`
  - `DIRECT`
  - `PROXIED`
- `networkProfileId`
- `effectiveMode`
- `appliedAt`
- `appliedBy`
- `affectedEndpoints[]`

Rules:

- `DIRECT` means no proxy binding and no dependency on the proxy stack.
- `PROXIED` requires `networkProfileId`.
- `PROXIED` with no active faults should use an explicit profile such as `passthrough`.

In v1, any `PROXIED` binding is operationally shared per SUT/proxied endpoint, even if initiated by one swarm.

## 6. v1 secure traffic approach

`HTTPS` and `TCPS` require preserving the client-facing hostname. v1 therefore must not rely on replacing worker URLs with `toxiproxy:port`.

The design assumption for v1 is:

- the resolved SUT endpoint presented to workers uses a proxy-facing authority that is valid for TLS,
- the proxy manager knows the corresponding real upstream authority,
- HAProxy accepts the secure downstream connection and forwards raw TCP to Toxiproxy,
- Toxiproxy forwards TCP to the real upstream target while faults are active.

Operational consequence:

- v1 is shared per SUT endpoint.
- if two swarms use the same proxied SUT endpoint, they share the same active fault behavior.

This is accepted for v1 and tracked explicitly as a limitation.

## 7. PocketHive integration points

### 7.1 Scenario Manager

Add a registry for `NetworkProfile` definitions similar to SUT/scenario registries:

- `GET /network-profiles`
- `GET /network-profiles/{id}`
- optional raw YAML endpoint in the same style as SUT editing

Scenario Manager remains the authoring/config source. It should not program proxies directly.

### 7.2 Orchestrator

Extend swarm creation/start-time selection with:

- `networkMode = DIRECT | PROXIED`
- `networkProfileId` required only for `PROXIED`

On `swarm-create`, the orchestrator should:

1. resolve scenario and SUT as it does today,
2. if `networkMode = PROXIED`, resolve the chosen `NetworkProfile`,
3. if `networkMode = PROXIED`, call `network-proxy-manager-service` to bind the SUT/profile,
4. persist the selected profile in swarm state and journal,
5. continue producing the `SwarmPlan`.

On `swarm-remove`, the orchestrator should call the proxy manager to release the binding only when the swarm ran in `PROXIED` mode.

### 7.3 Swarm Controller

v1 controller responsibilities:

- surface current network profile in runtime/journal/status context,
- support runtime profile changes initiated by UI or scenario plan,
- remain unaware of Toxiproxy details beyond the manager API contract.

Runtime profile changes are journal-driven, not control-plane outcome-driven:

- if a profile change is triggered by scenario plan execution, the normal plan command/journal trail already exists and the applied profile change should append journal/status detail only,
- if a profile change is triggered manually from Hive UI, PocketHive should append a `hive` journal entry describing the operator action and the resulting profile state,
- no dedicated control-plane command/outcome family is required for profile changes in v1.

### 7.4 UI V2

Required UI surfaces:

- Create Swarm modal:
  - select `networkMode`
  - when `PROXIED`, select `networkProfileId`
- Swarm details:
  - current profile
  - affected endpoints
  - recent proxy changes
- Network console:
  - inspect bound proxies and active toxics
  - apply/clear profile at runtime
  - show blast-radius warning for shared-per-SUT v1 behavior

### 7.5 Scenario Plan

The current plan engine only supports `config-update`, `start`, and `stop`; unsupported step types are ignored today. See [SCENARIO_PLAN_GUIDE.md](../scenarios/SCENARIO_PLAN_GUIDE.md) and [TimelineScenario.java](../../swarm-controller-service/src/main/java/io/pockethive/swarmcontroller/scenario/TimelineScenario.java).

Add a new swarm-level step type:

- `network-profile`

Example shape:

```yaml
plan:
  swarm:
    - stepId: net-on
      name: Enable reltest latency
      time: PT30S
      type: network-profile
      config:
        profileId: reltest-latency-250ms

    - stepId: net-off
      name: Return to proxy passthrough
      time: PT2M
      type: network-profile
      config:
        profileId: passthrough
```

The controller should map this to a call into `network-proxy-manager-service`, then emit journal/status updates tied to the step execution. No dedicated profile-change outcome contract is required in v1.

## 8. Proposed external service

Add a dedicated service:

- `network-proxy-manager-service`

Responsibilities:

- maintain the canonical state of proxy bindings and active profiles,
- create/update/remove Toxiproxy proxies,
- program HAProxy routing/backends for secure passthrough,
- expose a PocketHive-specific REST API for bind/apply/clear/status,
- provide read models for UI V2.

The manager service is the only PocketHive component that talks directly to proxy internals.

### 8.1 Proposed API surface

- `POST /api/network/bindings`
  - bind `sutId` + `networkMode` + optional `networkProfileId` for a swarm
- `POST /api/network/bindings/{swarmId}/apply-profile`
  - switch runtime profile
- `POST /api/network/bindings/{swarmId}/clear`
  - remove proxy binding and return to `DIRECT`
- `GET /api/network/bindings/{swarmId}`
  - current binding and effective endpoints
- `GET /api/network/proxies`
  - operational list for UI/debug

The exact DTOs must be defined once in a canonical contract module or schema, not duplicated across UI/orchestrator/service.

## 9. Milestones

### M0 - Architecture and contracts

- [ ] Finalize `NetworkProfile` contract and registry location.
- [ ] Finalize runtime boundary with the future SUT redesign.
- [ ] Define canonical REST DTOs for proxy manager integration.
- [ ] Define journal/status fields for network profile state.

Exit criteria:

- one approved doc contract for profile/binding DTOs
- explicit decision recorded for secure hostname-preserving path

### M1 - Shared-per-SUT secure proxy foundation

- [ ] Stand up `network-proxy-manager-service`.
- [ ] Add HAProxy + Toxiproxy deployment for local/runtime environments.
- [ ] Support `HTTPS` and `TCPS` through the proxy stack.
- [ ] Implement create/remove binding flow from orchestrator.
- [ ] Persist `networkMode` and `networkProfileId` in swarm state and journal.

Exit criteria:

 - one swarm can start with `networkMode = PROXIED` and a chosen `networkProfileId`
- secure traffic reaches the real test environment through the proxy stack
- clearing/removing the swarm releases runtime binding state

### M2 - UI control surface

- [ ] Add profile selector to Create Swarm modal.
- [ ] Add swarm network panel in UI V2.
- [ ] Add runtime actions: apply profile, clear profile, refresh state.
- [ ] Add blast-radius warning for v1 shared-per-SUT behavior.

Exit criteria:

- operator can create a swarm with proxy enabled from UI only
- operator can inspect and switch profile at runtime from UI only

### M3 - Scenario plan integration

- [ ] Extend Scenario Plan contract with `network-profile`.
- [ ] Implement controller-side execution for `network-profile`.
- [ ] Emit journal/status updates for profile-change steps.
- [ ] Document authoring and ordering rules for network steps.

Exit criteria:

- a scenario plan can change network profile over time without manual UI interaction

### M4 - Isolation upgrade (future)

- [ ] Design per-swarm DNS/gateway isolation.
- [ ] Add per-swarm binding namespaces and blast-radius containment.
- [ ] Support multiple swarms targeting the same SUT with different active profiles.

Exit criteria:

- two swarms can target the same SUT endpoint simultaneously with different profiles

## 10. Tracking checklist

### Contracts

- [ ] `NetworkProfile` schema
- [ ] `NetworkBinding` schema
- [ ] Orchestrator create/update request changes
- [ ] Status/journal payload updates

### Services

- [ ] Scenario Manager profile registry
- [ ] Orchestrator proxy-manager client
- [ ] Swarm Controller runtime profile client
- [ ] New proxy manager service

### UI

- [ ] Create Swarm selector
- [ ] Swarm network status panel
- [ ] Proxy console
- [ ] Runtime apply/clear actions

### Testing

- [ ] Local compose path for proxy stack
- [ ] Integration tests for binding/apply/clear lifecycle
- [ ] `HTTPS` verification path
- [ ] `TCPS` verification path
- [ ] Failure tests for missing profile / unsupported endpoint / broken upstream

## 11. Open questions


## 12. Risks

- Shared-per-SUT v1 can create operator confusion if the same proxied endpoint is reused by multiple swarms.
- Secure traffic support depends on explicit authority handling; any hidden hostname rewrite will cause flaky TLS failures.
- If literal URLs remain in scenarios, those paths will bypass the proxy layer by design.
- If proxy manager state is not the single source of truth, UI/runtime state will drift.

## 13. Implementation notes

- Keep all proxy behavior explicit and versioned.
- Do not let workers discover or synthesize proxy routes themselves.
- Do not duplicate SUT data into separate "proxied SUT" definitions.
- Fail fast when a requested profile cannot be applied to the selected endpoint/protocol set.
