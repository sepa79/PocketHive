# Request Builder Service (Experimental)

The Request Builder worker takes a generic `WorkItem` from Rabbit, resolves a disk‑backed template based on `serviceId` and `callId`, and appends an HTTP or TCP request envelope step that the existing `processor-service` can send to the target system.

## Inputs and outputs

- **Input**: `WorkerInputType.RABBITMQ` – a normal work queue, typically fed by Data Providers or other workers.
- **Output**: `WorkerOutputType.RABBITMQ` – the queue that the `processor-service` consumes.
- The worker looks for two headers on the current `WorkItem`:
  - `x-ph-service-id` (optional): logical service namespace.
  - `x-ph-call-id` (required): which request template to apply.

If `x-ph-service-id` is missing or blank, the worker uses `pockethive.worker.config.serviceId` (default `default`). Behaviour for missing `callId` / template is explicit and configurable via `passThroughOnMissingTemplate`:

- `true` (default) – log a warning and return the original `WorkItem` unchanged.
- `false` – log a warning and drop the message (no output).

## Configuration

Service config (`request-builder-service/src/main/resources/application.yml`):

```yaml
pockethive:
  worker:
    config:
      templateRoot: /app/templates/http
      serviceId: default
      passThroughOnMissingTemplate: true
```

To use the baked-in TCP templates instead, set `templateRoot: /app/templates/tcp`.

Capability manifest (`scenario-manager-service/capabilities/request-builder.latest.yaml`) exposes:

- `templateRoot`: root directory for templates of one protocol family (for example `/app/templates/http`).
- `serviceId`: default logical service id used when the header is not present.

## Template files

Templates live under `templateRoot` in JSON or YAML files and are loaded once at startup into memory. Each file represents a single HTTP or TCP call:

```json
{
  "protocol": "HTTP",
  "serviceId": "payments",
  "callId": "AuthorizePayment",
  "method": "POST",
  "pathTemplate": "/soap/payments",
  "bodyTemplate": "<soap>...</soap>",
  "headersTemplate": {
    "content-type": "text/xml; charset=utf-8",
    "SOAPAction": "AuthorizePayment"
  }
}
```

Fields:

- `serviceId` – optional; if missing/blank, the worker fills it with the configured `serviceId`.
- `callId` – required; identifies the call. Together with `serviceId` it forms the lookup key.
- `protocol` – required (`HTTP`, `TCP`, `ISO8583`), explicit in every template.
- `method` – HTTP method (`GET`, `POST`, etc.).
- `pathTemplate` – relative path, rendered via Pebble/SpEL (may contain parameters).
- `bodyTemplate` – rendered body (SOAP XML, JSON, or any text).
- `headersTemplate` – map of header name → templated value.

At runtime the worker uses the shared `TemplateRenderer`/SpEL integration from the Worker SDK. The template context includes:

- `payload` – `WorkItem.payload()` (text).
- `headers` – `WorkItem.headers()`.
- `workItem` – the full `WorkItem` instance.

Templates are loaded once at startup; if any template file is malformed, the worker fails fast on startup instead of silently skipping it.

## Selecting serviceId and callId

You can treat `serviceId` as a namespace for calls. Three common patterns:

1. **Single service, only callId**

- Config:

  ```yaml
  pockethive:
    worker:
      config:
        templateRoot: /app/templates/http
        serviceId: default
  ```

- Template:

  ```json
  { "serviceId": "default", "callId": "AuthorizePayment", ... }
  ```

- Upstream sets only:

  ```json
  "headers": { "x-ph-call-id": "AuthorizePayment" }
  ```

The worker resolves key `default::AuthorizePayment` and renders that template.

2. **Multiple services using the same builder**

- Templates:

  ```json
  { "serviceId": "payments", "callId": "GetCustomer", ... }
  { "serviceId": "cards",    "callId": "GetCustomer", ... }
  ```

- Upstream chooses the logical service:

  ```json
  "headers": {
    "x-ph-service-id": "payments",
    "x-ph-call-id": "GetCustomer"
  }
  ```

  or

  ```json
  "headers": {
    "x-ph-service-id": "cards",
    "x-ph-call-id": "GetCustomer"
  }
  ```

The worker resolves `payments::GetCustomer` or `cards::GetCustomer` respectively, letting you reuse `callId` names per service.

3. **Different defaults per swarm**

If you run the same Request Builder image in multiple swarms, each swarm can set a different default `serviceId` in its config:

- Swarm A:

  ```yaml
  pockethive.worker.config.serviceId: payments
  ```

- Swarm B:

  ```yaml
  pockethive.worker.config.serviceId: cards
  ```

Upstream only sets `x-ph-call-id`, and the configured default `serviceId` selects which templates to use:

- Swarm A → `payments::<callId>`
- Swarm B → `cards::<callId>`

## Output shape

When a template is found, the worker appends a new step whose payload is a JSON envelope understood by `processor-service`:

```json
{
  "path": "/api/example",
  "method": "POST",
  "headers": {
    "content-type": "application/json",
    "x-ph-call-id": "Example"
  },
  "body": "{... rendered body ...}"
}
```

The processor combines this `path` with its configured `baseUrl` to execute the HTTP call. If no `callId` is present or no matching template is found, the Request Builder logs a warning and leaves the `WorkItem` unchanged.

## Overriding templates

- The Docker image bakes default templates under `/app/templates/{protocol}`.
- You can override or extend these by mounting a host directory at the same path, for example:

  ```yaml
  services:
    request-builder:
      volumes:
        - ./my-templates-http:/app/templates/http:ro
  ```

- On startup the worker scans the effective `templateRoot` and fails fast if any template cannot be parsed. Callers can use the `scenario-templating-check` tool to list loaded `(serviceId, callId)` pairs and validate scenarios against the available templates.
