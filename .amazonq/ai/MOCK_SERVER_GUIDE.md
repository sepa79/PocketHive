# Mock Server Guide

PocketHive ships two mock servers for testing without a real SUT.

## WireMock (HTTP)

- Internal address (inside Docker network): `http://wiremock:8080`
- Host access: `http://localhost:8080`
- MCP tools: `mock.wiremock.list`, `mock.wiremock.add`, `mock.wiremock.reset`,
  `mock.wiremock.requests`, `mock.wiremock.unmatched`

### SUT definition

```yaml
id: wiremock-local
name: WireMock (local)
type: sandbox
endpoints:
  myapi:
    kind: HTTP
    baseUrl: http://wiremock:8080
```

### Adding a stub at runtime

```json
{
  "request": {
    "method": "POST",
    "urlPathPattern": "/api/v1/.*"
  },
  "response": {
    "status": 200,
    "headers": { "Content-Type": "application/json" },
    "body": "{\"result\": \"ok\", \"id\": \"{{randomValue type='UUID'}}\"}"
  }
}
```

Use `mock.wiremock.add { mapping: <above> }`.

### Debugging unmatched requests

`mock.wiremock.unmatched` returns requests that hit WireMock but matched no stub.
Use this when the processor returns 404 or unexpected responses.

### Pre-configured stubs

WireMock loads JSON files from `wiremock/mappings/` at startup. Add files there for
persistent stubs that survive resets.

---

## TCP Mock Server

- Internal TCP address: `tcp://tcp-mock-server:9090`
- Internal admin API (inside Docker network): `http://tcp-mock-server:8083`
- Host admin API: `http://localhost:8083`
- Host TCP port: `9090`
- Auth: Basic `admin:admin`
- MCP tools: `mock.tcp.list`, `mock.tcp.add`, `mock.tcp.reset`, `mock.tcp.requests`,
  `mock.tcp.unmatched`, `mock.tcp.scenarios`, `mock.tcp.reset-scenarios`

### SUT definition

```yaml
id: tcp-mock-local
name: TCP Mock (local)
type: sandbox
endpoints:
  tcp-server:
    kind: TCP
    baseUrl: tcp://tcp-mock-server:9090
```

### Mapping structure

```json
{
  "id": "my-xml-handler",
  "requestPattern": ".*<MyRequest>.*",
  "responseTemplate": "<?xml version=\"1.0\"?><MyResponse><Status>OK</Status></MyResponse>",
  "requestDelimiter": "</MyRequest>",
  "responseDelimiter": "",
  "priority": 10,
  "enabled": true,
  "description": "Handles MyRequest XML messages"
}
```

Key fields:
- `requestPattern` — regex matched against the raw request bytes (as UTF-8 string)
- `requestDelimiter` — string that marks end of request frame (e.g. `</Document>`)
- `responseDelimiter` — appended after response (use `""` for no delimiter)
- `priority` — higher wins when multiple patterns match
- `responseTemplate` — supports `{{timestamp}}`, `{{uuid}}`, `{{random}}`,
  `{{request.regex 'pattern' group N}}`, `{{message}}` (echo)

### Framing alignment — critical for TCP scenarios

The TCP mock's framing must match what the processor expects:

| Processor template `behavior` | Mock `requestDelimiter` | Mock `responseDelimiter` |
|---|---|---|
| `REQUEST_RESPONSE` with `endTag: "</Doc>"` | `</Doc>` | `""` (empty) |
| `FIRE_FORGET` | any (request is sent, no read) | n/a |
| `ECHO` | `\n` (default) | `\n` |
| `STREAMING` | n/a (reads up to maxBytes) | `""` |

**The `pcs-xml-auth` pre-configured mapping** uses `requestDelimiter: "</Document>"` and
`responseDelimiter: ""` — this matches the `pcs-auth-csv` and `pcs-auth-topup-redis-clearing`
bundles which use `endTag: "</Document>"` in their templates.

### ISO-8583 and binary wire formats

The TCP mock server works with UTF-8 text patterns. It **cannot** correctly handle
binary wire-format protocols like ISO-8583 `MC_2BYTE_LEN_BIN_BITMAP` because:

1. The 2-byte length prefix is binary, not text-delimited
2. The mock's response does not include the required length prefix header
3. The processor's `LengthPrefix2BResponseReader` will time out waiting for a
   properly framed response

**For ISO-8583 testing**, use one of:
- A real ISO-8583 simulator (e.g. jPOS, Postilion test harness)
- A custom TCP server that speaks the correct wire profile
- Switch the template to `behavior: FIRE_FORGET` for smoke testing (no response read)

### Debugging unmatched TCP requests

`mock.tcp.unmatched` is not available on all versions. Instead:
1. Check `mock.tcp.requests` to see what arrived
2. Compare the raw request body against your `requestPattern` regex
3. Verify `requestDelimiter` matches the `endTag` in your template

### Stateful scenarios

The TCP mock supports multi-step stateful flows:

```json
{
  "id": "auth-step1",
  "requestPattern": "INIT_.*",
  "responseTemplate": "INITIALIZED",
  "scenarioName": "auth-flow",
  "requiredScenarioState": null,
  "newScenarioState": "Authenticated",
  "priority": 50,
  "enabled": true
}
```

Use `mock.tcp.scenarios` to inspect state and `mock.tcp.reset-scenarios` to reset.

---

## Choosing the right mock

| Scenario | Use |
|---|---|
| HTTP REST / SOAP / JSON | WireMock |
| TCP XML (PCS, custom protocols) | TCP Mock with matching `requestDelimiter` |
| TCP ISO-8583 binary | External simulator (TCP mock not suitable) |
| TLS/SSL TCP | `tcp-mock-server-tls` on port 9091 |
| No mock needed (real SUT) | Bundle-local `sut/` pointing at real endpoint |
