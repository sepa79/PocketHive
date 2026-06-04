# DB Query Worker MVP

Status: MVP implemented and smoke-tested on the large HiveForge swarm

## Goal

Add a DB-only load worker that executes one configured SQL statement per input
work item against an explicitly configured database connection. The MVP targets
database bombardment and DB-link/side-effect checks for test systems.

Pipeline shape:

```text
generator / redis-dataset -> db-query -> postprocessor
```

## Non-negotiable design constraints

- No adapter fallback: every worker config declares `adapter` explicitly.
- No connection discovery: MVP uses an explicit `jdbcUrl`, username, and
  password in scenario config or resolved `vars`.
- Scenario variables are rendered into bee config by the orchestrator before
  typed worker config binding. Exact `{{ vars.name }}` tokens preserve the
  resolved value type; embedded tokens render as strings. Missing variables
  fail fast.
- One query per work item; no DB sequences or multi-statement transaction
  scripts in MVP.
- SQL is a template with typed bind params, not string concatenation.
- Result mode is required and explicit.
- Retry policy is explicit and limited to exception matching and SQLState class
  matching in MVP.

## Supported adapters

- `POSTGRES`
- `ORACLE`

## Supported statement types

- `SELECT`
- `INSERT`
- `UPDATE`

Out of MVP:

- Oracle PL/SQL blocks
- stored procedures
- `DELETE`
- multi-query transaction scripts

## Worker config contract

```yaml
config:
  worker:
    adapter: POSTGRES
    templateRoot: /app/scenario/templates/db
    serviceId: db
    queryId: dblink-check
    threadCount: "{{ vars.threadCount }}"
    queryTimeoutMs: "{{ vars.queryTimeoutMs }}"
    connection:
      jdbcUrl: "{{ vars.dbJdbcUrl }}"
      username: "{{ vars.dbUsername }}"
      password: "{{ vars.dbPassword }}"
    pool:
      maxSize: "{{ vars.poolMaxSize }}"
      minIdle: "{{ vars.poolMinIdle }}"
      connectionTimeoutMs: "{{ vars.poolConnectionTimeoutMs }}"
      validationTimeoutMs: "{{ vars.poolValidationTimeoutMs }}"
      idleTimeoutMs: "{{ vars.poolIdleTimeoutMs }}"
      maxLifetimeMs: "{{ vars.poolMaxLifetimeMs }}"
    retry:
      maxAttempts: "{{ vars.retryMaxAttempts }}"
      initialBackoffMs: "{{ vars.retryInitialBackoffMs }}"
      backoffMultiplier: "{{ vars.retryBackoffMultiplier }}"
      maxBackoffMs: "{{ vars.retryMaxBackoffMs }}"
      on:
        - EXCEPTION
        - SQLSTATE_CLASS_08
        - SQLSTATE_CLASS_40
```

## Query template contract

Templates live under `templateRoot` and are keyed by `(serviceId, queryId)`.

```yaml
serviceId: db
queryId: dblink-check
adapter: POSTGRES
statementType: SELECT
sqlTemplate: |
  select status, remote_marker
  from dblink_probe
  where probe_id = :probeId
params:
  - name: probeId
    source: payload.probeId
    type: STRING
result:
  mode: FIRST_ROW
validation:
  minRows: 1
  columns:
    - name: status
      equals: OK
    - name: remote_marker
      notNull: true
    - name: remote_marker
      regex: "^DBLINK-"
extracts:
  - fromColumn: remote_marker
    to: dblinkMarker
    required: false
```

For `INSERT` / `UPDATE`:

```yaml
result:
  mode: UPDATE_COUNT
validation:
  minAffectedRows: 1
```

## Result modes

- `FIRST_ROW` - require a result set and carry the first row.
- `ALL_ROWS` - carry all returned rows.
- `UPDATE_COUNT` - carry the JDBC update count.
- `NONE` - execute and carry status only.

## Validation MVP

Supported checks:

- `minRows`
- `maxRows`
- `minAffectedRows`
- `maxAffectedRows`
- column `equals`
- column `notNull`
- column `regex`

Validation failures abort the current work item.

## Retry MVP

Supported retry tokens:

- `EXCEPTION`
- `SQLSTATE_CLASS_<NN>` where `<NN>` is the two-character SQLState class.

No vendor-specific error-code retry matching in MVP.

## Output

The worker appends a new `WorkItem` step containing:

- adapter
- serviceId
- queryId
- statementType
- result mode
- attempt count
- duration
- selected rows or update count according to `result.mode`
- extracted values merged into the payload for downstream workers

Failures abort the current work item. Successful executions also add step
headers with the same DB execution metadata, including `x-ph-db-query-duration-ms`,
`x-ph-db-query-attempts`, `x-ph-db-query-rows`, and optional
`x-ph-db-query-update-count`.

The worker also emits the shared transaction outcome headers consumed by the
postprocessor ClickHouse sink:

- `x-ph-call-id`: `<serviceId>/<queryId>`
- `x-ph-processor-status`: `200` for a successful DB execution
- `x-ph-processor-success`: `true`
- `x-ph-processor-duration-ms`: DB execution duration
- `x-ph-business-code`: `OK`
- `x-ph-business-success`: `true`
- `x-ph-dim-*`: low-cardinality DB dimensions such as adapter, serviceId,
  queryId, statementType, resultMode, attempts, row count, and update count

## Metrics status

The MVP emits DB execution metadata in the outgoing `WorkItem` and maps
successful DB executions into the existing ClickHouse tx-outcome contract. The
postprocessor still records aggregate pipeline Micrometer metrics
(`ph_hop_latency_ms`, `ph_total_latency_ms`, `ph_hops`, `ph_errors_total`, and
`ph_processor_*`).

The DB smoke bundle explicitly configures the postprocessor with
`txOutcomeSinkMode: CLICKHOUSE_V2`. Dashboard queries can use
`ph_tx_outcome_v2.processorDurationMs` for DB query latency and the `dimensions`
map for DB-specific facets.

Failure observability is still out of MVP: a DB exception aborts the work item
before it reaches postprocessor, so failed DB attempts are not written to
ClickHouse yet. The next increment should add an explicit failure-forwarding
contract if failed executions must be represented as tx-outcomes.

## Testing plan

- Unit tests for template loading and strict validation.
- Unit tests for SQL rendering with typed bind params.
- Unit tests for retry decisions by exception and SQLState class.
- Testcontainers coverage for Postgres execution.
- Oracle runtime path is built through JDBC adapter tests and should be
  validated manually against a real Oracle target before release.

## Large-swarm smoke evidence

Environment:

- Orchestrator ingress: `http://192.168.88.50:8088/orchestrator`
- Scenario Manager ingress: `http://192.168.88.50:8088/scenario-manager`
- Test Postgres service: `dbquery-test-postgres` on `pockethive_default`
- JDBC URL used by the smoke profiles:
  `jdbc:postgresql://tasks.dbquery-test-postgres:5432/dbquery`

Runtime images pushed:

- `192.168.88.54:5000/pockethive/db-query:dev-20260603-1520-g9bbbab8f`
- `192.168.88.54:5000/pockethive/orchestrator:dev-dbquery-mvp-20260604`
- `192.168.88.54:5000/pockethive/scenario-manager:dev-dbquery-mvp-20260604`

Smoke swarms:

- `db-query-mvp-select`
- `db-query-mvp-insert`
- `db-query-mvp-update`

Observed DB result after the final run:

```text
probe_id     | status   | remote_marker | hit_count
-------------+----------+---------------+----------
PH-DB-INSERT | INSERTED | DBLINK-insert | 5
PH-DB-SMOKE  | READY    | DBLINK-local  | 0
PH-DB-UPDATE | UPDATED  | DBLINK-update | 5
```

All smoke work queues were drained (`messageCount=0`) and the db-query workers
started a Hikari pool against Postgres.
