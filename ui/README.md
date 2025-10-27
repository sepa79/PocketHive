# PocketHive UI

PocketHive's UI is a web console for monitoring and controlling swarms in the PocketHive ecosystem. It connects to the control plane over STOMP and renders the Hive topology with React Flow, letting you inspect components and stream logs in real time.

## Control Surface

- The UI consumes control-plane events over STOMP using a **read-only** subscription.
- All lifecycle and configuration actions are executed through the Orchestrator's REST endpoints instead of publishing directly to AMQP.

Example commands (replace IDs and host as needed):

```bash
curl -X POST "http://localhost:8080/api/swarms/demo-swarm/start" \
  -H 'Content-Type: application/json' \
  -d '{
    "idempotencyKey": "00000000-0000-4000-8000-000000000001",
    "notes": "Kick off demo swarm"
  }'

curl -X POST "http://localhost:8080/api/components/ingest/ingest-1/config" \
  -H 'Content-Type: application/json' \
  -d '{
    "idempotencyKey": "00000000-0000-4000-8000-000000000002",
    "patch": { "enabled": true }
  }'
```

Refer to the [Orchestrator REST API guide](../docs/ORCHESTRATOR-REST.md) for the complete list of lifecycle and configuration endpoints.

## Getting Started

Install dependencies:

```bash
npm install
```

### Development

Run the development server with hot reload (default at http://localhost:5173):

```bash
npm run dev
```

### Build

Type‑checks the project and generates production assets in `dist/`:

```bash
npm run build
```

### Test

Execute the unit test suite:

```bash
npm test
```

## Key Panels

- **Hive** – manage swarms, browse components, view the topology graph and inspect component details.
- **Buzz** – stream IN/OUT/Other logs and view current configuration with adjustable message limits.
- **Queen** – create new swarms from predefined templates.
- **Nectar** – reserved for upcoming features.

