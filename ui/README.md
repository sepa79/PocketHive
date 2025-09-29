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

### Scenario Remote (`@ph/scenario`)

Run the Module Federation remote in isolation. The dev server exposes the remote entry at
`http://localhost:5173/assets/remoteEntry.js` while also mounting the placeholder UI for quick visual checks.

```bash
npm run dev:scenario
```

Build the remote for distribution. Bundled assets are written to `dist/scenario/` so they can be published independently of the
main console bundle.

```bash
npm run build:scenario
```

To consume the remote from a host application, configure Module Federation (or the compatible loader) with the following
settings:

- **Remote name**: `@ph/scenario`
- **Remote entry URL**: `<scenario-server>/assets/remoteEntry.js`
- **Exposed module**: `./ScenarioApp`

Hosts can then import the module with `import('@ph/scenario/ScenarioApp')` and call the exported `mount` helper to render the
Scenario Builder placeholder into a DOM node.

### Shell integration and shared hooks

PocketHive's host shell now centralises cross-cutting providers—such as UI configuration and shared state—in the
`ShellProviders` component. Wrap both the host router and any remote mount points with this component to ensure the remote
consumes the same React Query client, configuration context and future theme/auth providers as the host.

Remote modules should import hooks and context accessors from the `@ph/shell` barrel. This guarantees a single instance of
shared utilities like `useConfig` and `useUIStore` across host and remote bundles when Module Federation loads the remote at
runtime. See `src/scenario/ScenarioApp.integration.test.tsx` for an example that asserts the remote receives the host-provided
RabbitMQ/Prometheus endpoints and store state when rendered through the shell.

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

