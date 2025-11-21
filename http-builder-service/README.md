# HTTP Builder Service (Experimental)

The HTTP Builder worker takes a generic `WorkItem` from Rabbit, resolves a disk‑backed template based on `serviceId` and `callId`, and appends an HTTP request envelope step that the existing `processor-service` can send to the target system.

## Inputs and outputs

- **Input**: `WorkerInputType.RABBITMQ` – a normal work queue, typically fed by Data Providers or other workers.
- **Output**: `WorkerOutputType.RABBITMQ` – the queue that the HTTP `processor-service` consumes.
- The worker looks for two headers on the current `WorkItem`:
  - `x-ph-service-id` (optional): logical service namespace.
  - `x-ph-call-id` (required): which HTTP call template to apply.

If `x-ph-service-id` is missing or blank, the worker falls back to `pockethive.worker.config.serviceId` (default `default`). If `x-ph-call-id` is missing or a matching template is not found, the worker logs a warning and returns the original `WorkItem` unchanged.

## Configuration

Service config (`http-builder-service/src/main/resources/application.yml`):

```yaml
pockethive:
  worker:
    config:
      templateRoot: /app/http-templates
      serviceId: default
```

Capability manifest (`scenario-manager-service/capabilities/http-builder.latest.yaml`) exposes:

- `templateRoot`: root directory for HTTP templates (baked into the image, overridable via a Docker volume).
- `serviceId`: default logical service id used when the header is not present.

## Template files

Templates live under `templateRoot` in JSON or YAML files and are loaded once at startup into memory. Each file represents a single HTTP call:

```json
{
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
- `method` – HTTP method (`GET`, `POST`, etc.).
- `pathTemplate` – relative path, rendered via Pebble/SpEL (may contain parameters).
- `bodyTemplate` – rendered body (SOAP XML, JSON, or any text).
- `headersTemplate` – map of header name → templated value.

At runtime the worker uses the shared `TemplateRenderer`/SpEL integration from the Worker SDK. The template context includes:

- `payload` – `WorkItem.payload()` (text).
- `headers` – `WorkItem.headers()`.
- `workItem` – the full `WorkItem` instance.

## Selecting serviceId and callId

You can treat `serviceId` as a namespace for calls. Three common patterns:

1. **Single service, only callId**

- Config:

  ```yaml
  pockethive:
    worker:
      config:
        templateRoot: /app/http-templates
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

If you run the same HTTP Builder image in multiple swarms, each swarm can set a different default `serviceId` in its config:

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

The processor combines this `path` with its configured `baseUrl` to execute the HTTP call. If no `callId` is present or no matching template is found, the HTTP Builder logs a warning and leaves the `WorkItem` unchanged.

