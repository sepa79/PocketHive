# Scenario Bundle Examples

Real patterns from existing bundles. Copy and adapt.

> **work: syntax reminder** — always use map form. `out: { out: queue }` not `out: queue`.

## Pattern 1: CSV → Request Builder → TCP Processor

Use when: Test data is in CSV and the target system speaks TCP (XML, ISO-8583, etc.)

See: `bundles/pcs-auth-csv/`

```yaml
id: my-csv-tcp
name: CSV to TCP Service
template:
  image: swarm-controller:latest
  bees:
    - role: dataProvider
      image: generator:latest
      config:
        inputs:
          type: CSV_DATASET
          csv:
            filePath: /app/scenario/datasets/testdata.csv
            ratePerSec: 5
            rotate: true
            skipHeader: true
        worker:
          message:
            bodyType: SIMPLE
            body: |
              {
                "pan": "{{ payloadAsJson.pan }}",
                "amount": "{{ payloadAsJson.amount }}"
              }
            headers:
              x-ph-call-id: my-tcp-call
      work:
        out:
          out: build

    - role: request-builder
      image: request-builder:latest
      config:
        worker:
          templateRoot: /app/scenario/templates
          serviceId: default
      work:
        in:
          in: build
        out:
          out: proc

    - role: processor
      image: processor:latest
      config:
        baseUrl: "{{ sut.endpoints['tcp-server'].baseUrl }}"
        worker:
          mode: THREAD_COUNT
          threadCount: 5
          tcpTransport:
            type: socket
            connectionReuse: PER_THREAD
            readTimeoutMs: 5000
      work:
        in:
          in: proc
        out:
          out: post

    - role: postprocessor
      image: postprocessor:latest
      config:
        worker:
          postprocessor:
            publish-all-metrics: true
      work:
        in:
          in: post
```

Template (`templates/default/my-tcp-call.yaml`):
```yaml
serviceId: default
callId: my-tcp-call
protocol: TCP
behavior: REQUEST_RESPONSE
transport: socket
endTag: "</Response>"
bodyTemplate: |
  <?xml version="1.0"?><Request>
    <PAN>{{ payloadAsJson.pan }}</PAN>
    <Amount>{{ payloadAsJson.amount }}</Amount>
  </Request>
headersTemplate:
  Content-Type: application/xml
```

SUT (`sut/tcp-mock-local/sut.yaml`):
```yaml
id: tcp-mock-local
name: TCP Mock (local)
type: sandbox
endpoints:
  tcp-server:
    kind: TCP
    baseUrl: tcp://tcp-mock-server:9090
```

## Pattern 2: CSV → Request Builder → HTTP Processor

Use when: Test data is in CSV and the target system is HTTP REST.

See: `bundles/horizon-account-card-creation/`

```yaml
id: my-csv-http
name: CSV to HTTP API
template:
  image: swarm-controller:latest
  bees:
    - role: dataProvider
      image: generator:latest
      config:
        inputs:
          type: CSV_DATASET
          csv:
            filePath: /app/scenario/datasets/testdata.csv
            ratePerSec: 5
            rotate: true
            skipHeader: true
        worker:
          message:
            bodyType: SIMPLE
            body: |
              {
                "account_no": "{{ payloadAsJson.account_no }}",
                "customer_code": "{{ payloadAsJson.customer_code }}"
              }
            headers:
              x-ph-call-id: create-account
              x-ph-service-id: myapi
      work:
        out:
          out: build

    - role: request-builder
      image: request-builder:latest
      config:
        worker:
          templateRoot: /app/scenario/templates
          serviceId: myapi
      work:
        in:
          in: build
        out:
          out: proc

    - role: processor
      image: processor:latest
      config:
        baseUrl: "{{ sut.endpoints['myapi'].baseUrl }}"
        worker:
          mode: THREAD_COUNT
          threadCount: 10
      work:
        in:
          in: proc
        out:
          out: post

    - role: postprocessor
      image: postprocessor:latest
      config:
        worker:
          postprocessor:
            publish-all-metrics: true
      work:
        in:
          in: post
```

## Pattern 3: Scheduler → Direct HTTP (no request-builder)

Use when: Simple HTTP calls without complex per-request templating.

```yaml
id: simple-http
name: Simple HTTP Load
template:
  image: swarm-controller:latest
  bees:
    - role: generator
      image: generator:latest
      config:
        inputs:
          type: SCHEDULER
          scheduler:
            ratePerSec: 10
            maxMessages: 1000
        worker:
          message:
            bodyType: HTTP
            path: /api/health
            method: GET
            headers:
              content-type: application/json
      work:
        out:
          out: proc

    - role: processor
      image: processor:latest
      config:
        baseUrl: "{{ sut.endpoints['default'].baseUrl }}"
      work:
        in:
          in: proc
        out:
          out: post

    - role: postprocessor
      image: postprocessor:latest
      config:
        worker:
          postprocessor:
            publish-all-metrics: true
      work:
        in:
          in: post
```

## Pattern 4: Multi-stage with Redis coordination

Use when: Phase 1 creates data and pushes to Redis; Phase 2 consumes atomically.

See: `bundles/horizon-account-card-creation/`

```yaml
# Phase 1: Create accounts, push to Redis
- role: accountCreator
  image: generator:latest
  config:
    inputs:
      type: CSV_DATASET
      csv:
        filePath: /app/scenario/datasets/accounts.csv
        ratePerSec: 10
        rotate: false
        skipHeader: true
    worker:
      message:
        bodyType: SIMPLE
        body: |
          { "account_no": "{{ payloadAsJson.account_no }}" }
        headers:
          x-ph-call-id: create-account
          x-ph-service-id: myapi
  work:
    out:
      out: account-build

- role: accountBuilder
  image: request-builder:latest
  config:
    worker:
      templateRoot: /app/scenario/templates
      serviceId: myapi
  work:
    in:
      in: account-build
    out:
      out: account-proc

- role: accountProcessor
  image: processor:latest
  config:
    baseUrl: "{{ sut.endpoints['myapi'].baseUrl }}"
    worker:
      mode: THREAD_COUNT
      threadCount: 10
  work:
    in:
      in: account-proc
    out:
      out: account-redis

- role: accountToRedis
  image: postprocessor:latest
  work:
    in:
      in: account-redis
  config:
    worker:
      interceptors:
        redisUploader:
          enabled: true
          host: redis
          port: 6379
          phase: AFTER
          sourceStep: FIRST
          pushDirection: RPUSH
          fallbackList: "ph:myapp:accounts"

# Phase 2: Consume from Redis and execute transactions
- role: transactionProvider
  image: generator:latest
  config:
    inputs:
      type: REDIS_DATASET
      redis:
        host: redis
        port: 6379
        listName: "ph:myapp:accounts"
        ratePerSec: 50
    worker:
      message:
        bodyType: SIMPLE
        body: "{{ payload }}"
        headers:
          x-ph-call-id: create-transaction
          x-ph-service-id: myapi
  work:
    out:
      out: txn-build

- role: transactionBuilder
  image: request-builder:latest
  config:
    worker:
      templateRoot: /app/scenario/templates
      serviceId: myapi
  work:
    in:
      in: txn-build
    out:
      out: txn-proc

- role: transactionProcessor
  image: processor:latest
  config:
    baseUrl: "{{ sut.endpoints['myapi'].baseUrl }}"
    worker:
      mode: RATE_PER_SEC
      ratePerSec: 100.0
  work:
    in:
      in: txn-proc
    out:
      out: txn-post

- role: transactionPostprocessor
  image: postprocessor:latest
  config:
    worker:
      postprocessor:
        publish-all-metrics: true
  work:
    in:
      in: txn-post
```

## Pattern 5: With moderator (traffic shaping)

Use when: You need rate limiting, sine-wave traffic, or buffer guard control.

```yaml
- role: generator
  image: generator:latest
  config:
    inputs:
      type: SCHEDULER
      scheduler:
        ratePerSec: 100
  work:
    out:
      out: moderate

- role: moderator
  image: moderator:latest
  config:
    worker:
      mode:
        type: sine
        sine:
          minRatePerSec: 5
          maxRatePerSec: 50
          periodSeconds: 120
  work:
    in:
      in: moderate
    out:
      out: proc

- role: processor
  image: processor:latest
  config:
    baseUrl: "{{ sut.endpoints['default'].baseUrl }}"
  work:
    in:
      in: proc
    out:
      out: post

- role: postprocessor
  image: postprocessor:latest
  work:
    in:
      in: post
```

## Pattern 6: OAuth2 token acquisition

Use when: Target API requires OAuth2 client credentials flow.

See: `bundles/paymentsecurity-get-access-token/`

> **Auth placement rule**: Put `auth:` under `config.worker` on the request-builder.
> Use a hardcoded `tokenUrl` (not `sut.endpoints[...]`) — the Orchestrator does not
> resolve SUT expressions in nested config fields, only in `config.baseUrl`.

```yaml
- role: generator
  image: generator:latest
  config:
    inputs:
      type: SCHEDULER
      scheduler:
        ratePerSec: 5
        maxMessages: 50
    worker:
      message:
        bodyType: SIMPLE
        body: '{ "amount": "100.00" }'
        headers:
          x-ph-call-id: myCall
  work:
    out:
      out: build

- role: request-builder
  image: request-builder:latest
  config:
    worker:
      templateRoot: /app/scenario/templates
      serviceId: myapi
      auth:
        - tokenKey: "myapi:auth"
          type: oauth2-client-credentials
          tokenUrl: "http://wiremock:8080/auth/token"   # hardcoded — NOT sut.endpoints
          clientId: "my-client-id"
          clientSecret: "my-secret"
          scope: "read write"
  work:
    in:
      in: build
    out:
      out: proc

- role: processor
  image: processor:latest
  config:
    baseUrl: "{{ sut.endpoints['myapi'].baseUrl }}"   # sut.endpoints IS valid here
  work:
    in:
      in: proc
    out:
      out: post

- role: postprocessor
  image: postprocessor:latest
  work:
    in:
      in: post
```

## Pattern 7: Two independent pipelines in one swarm

Use when: Two flows share a swarm (e.g. auth + topup loop).

See: `bundles/pcs-auth-topup-redis-clearing/`

Key points:
- Each pipeline has its own queue namespace (e.g. `auth-build`, `topup-build`)
- Postprocessors can forward to a shared downstream (e.g. clearing exporter)
- Use distinct role names — no two bees can share a role name in one swarm

```yaml
template:
  image: swarm-controller:latest
  bees:
    ## Pipeline A
    - role: authProvider
      image: generator:latest
      work:
        out:
          out: auth-build
      config: { ... }

    - role: authBuilder
      image: request-builder:latest
      work:
        in:
          in: auth-build
        out:
          out: auth-proc
      config: { ... }

    - role: authProcessor
      image: processor:latest
      work:
        in:
          in: auth-proc
        out:
          out: post
      config: { ... }

    - role: postprocessor
      image: postprocessor:latest
      work:
        in:
          in: post
        out:
          out: clearing    # forward to clearing exporter
      config: { ... }

    ## Pipeline B
    - role: topupProvider
      image: generator:latest
      work:
        out:
          out: topup-build
      config: { ... }

    - role: topupBuilder
      image: request-builder:latest
      work:
        in:
          in: topup-build
        out:
          out: topup-proc
      config: { ... }

    - role: topupProcessor
      image: processor:latest
      work:
        in:
          in: topup-proc
        out:
          out: post-topup
      config: { ... }

    - role: topupPostprocessor
      image: postprocessor:latest
      work:
        in:
          in: post-topup
      config: { ... }

    ## Shared downstream
    - role: clearingExporter
      image: clearing-export:latest
      work:
        in:
          in: clearing
      config: { ... }
```

## Pattern 8: Scenario plan (automated ramp + auto-stop)

Use when: You want the swarm to automatically ramp rates and/or stop itself on a
fixed schedule without manual intervention.

See: `docs/ai/SCENARIO_PLAN_GUIDE.md` for full reference.

```yaml
id: my-ramp-scenario
name: Ramp Load Test with Auto-Stop
template:
  image: swarm-controller:latest
  bees:
    - role: generator
      image: generator:latest
      work:
        out:
          out: build
      config:
        inputs:
          type: SCHEDULER
          scheduler:
            ratePerSec: 5       # start low — plan will ramp it up
            maxMessages: 0
        worker:
          message:
            bodyType: SIMPLE
            body: '{"event": "load-test"}'
            headers:
              x-ph-call-id: my-call

    - role: request-builder
      image: request-builder:latest
      config:
        worker:
          templateRoot: /app/scenario/templates
          serviceId: myapi
      work:
        in:
          in: build
        out:
          out: proc

    - role: processor
      image: processor:latest
      config:
        baseUrl: "{{ sut.endpoints['myapi'].baseUrl }}"
      work:
        in:
          in: proc
        out:
          out: post

    - role: postprocessor
      image: postprocessor:latest
      config:
        worker:
          postprocessor:
            publish-all-metrics: true
      work:
        in:
          in: post

plan:
  bees:
    - instanceId: generator
      steps:
        - stepId: ramp-mid
          name: Ramp to 25/s at 1 minute
          time: PT1M
          type: config-update
          config:
            inputs:
              scheduler:
                ratePerSec: 25

        - stepId: ramp-peak
          name: Ramp to 50/s at 2 minutes
          time: PT2M
          type: config-update
          config:
            inputs:
              scheduler:
                ratePerSec: 50

  swarm:
    - stepId: swarm-stop
      name: Stop after 5 minute soak
      time: PT5M
      type: stop
```

Key points:
- The generator starts at 5/s; the plan ramps it to 25/s then 50/s automatically
- The swarm stops itself after 5 minutes — no manual `swarm.stop` needed
- `instanceId: generator` matches the bee's `role` name
- `stepId` values must be unique within the plan
- Plan progress is visible in `swarm.get` under `envelope.data.context.scenario`
