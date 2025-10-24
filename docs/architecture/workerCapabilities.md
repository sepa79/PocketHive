# Worker capability catalogue

This document captures how PocketHive should describe worker capabilities so that the Hive UI and the Scenario Editor can render
configuration forms and actions without hard-coded role-specific logic.

## Goals

- Provide a single, versioned contract that tells clients which configuration fields, actions, and rich panels a bee supports.
- Allow the Scenario Editor to function before any swarm is provisioned by consuming file-based metadata.
- Keep runtime behaviour authoritative by letting bees emit their live capabilities once they boot, so drift can be detected.

## Manifest shape

Every bee publishes a machine-readable manifest, for example `capabilities.<role>.json`, that follows a shared schema:

- `schemaVersion`: identifies the contract pack version that defines the manifest structure.
- `capabilitiesVersion`: tracks the bee's own capability revision; bump when a field/action changes.
- `role`: the canonical component role (generator, trigger, etc.).
- `config`: array of fields with name, type, default, validation, and optional UI hints.
- `actions`: array of invokable operations with identifiers, human labels, required params, and the API endpoint/verb.
- `panels`: optional declarations that map to specialised UI modules (e.g., `wiremockMetrics`). Unknown panels fall back to the
  generic renderer built from `config`/`actions`.

The manifest schema itself lives alongside other scenario-builder contracts (blocks, tracks) so frontend and backend validate
against the same definitions.

## Authoring workflow

1. **Check in manifests with the bee source** — Each worker repository or submodule stores its manifest next to the component code.
   CI should fail if a release occurs without a manifest or when capabilities change without a version bump.
2. **Aggregate for distribution** — A contract-build step collects all per-bee manifests into a signed catalogue artifact,
   for example `contracts/capabilities/catalogue-v{n}.json`. This bundle ships with the Scenario Manager image and can be published
   to a CDN for UI consumption.
3. **Expose through Scenario Manager** — Scenario Manager serves the catalogue via `/capabilities` endpoints so both the Hive UI and
   the Scenario Editor fetch the same authoritative dataset during authoring.

## Scenario Editor behaviour

- Loads the aggregated catalogue on start (or when the user switches component versions) to render configuration forms and
  available actions without contacting live workers.
- Warns authors when a scenario references `capabilitiesVersion` values that are not present in the currently mounted catalogue.
- Allows rich panels when `panels` entries match registered UI modules; otherwise the generic renderer is used.

## Runtime reconciliation

1. When a bee boots, its first `status-full` heartbeat includes the same manifest payload.
2. Swarm controllers persist the latest per-instance manifest and forward it to the Orchestrator.
3. The Orchestrator maintains a runtime catalogue keyed by role and version. Scenario Manager periodically compares this runtime
   state against the static bundle and surfaces drift (e.g., new optional fields) so operators know to refresh the offline pack.
4. During plan submission, the Orchestrator rejects scenarios that reference capabilities absent from the runtime catalogue,
   keeping authoring and execution aligned.

## Versioning and governance

- Treat the manifest schema as part of the scenario-builder contract pack. Update the schema first, then roll out backend/frontend
  consumers.
- Require `capabilitiesVersion` bumps for any change observable by clients. This keeps caches and catalogues coherent.
- Record component image digests or semantic versions inside the aggregated catalogue to aid provenance checks and drift detection.
- When drift is detected between runtime and static catalogues, fail CI or alert operators so new manifests are published before
  promoting the change to production.
