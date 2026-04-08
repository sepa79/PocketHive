# TCP Mock Server - Capabilities & Features

Complete overview of TCP Mock Server capabilities, features, and use cases.

## Core Capabilities

### 1. TCP Protocol Support
- **Plain TCP** - Standard TCP socket connections
- **TLS/SSL** - Secure TCP connections (tcps://)
- **Multiple Transports** - Socket, NIO, Netty
- **Binary & Text** - Handle any data format
- **Custom Delimiters** - Per-mapping request and response framing
- **Wire Profiles** - Explicit binary framing modes (see [Wire Profiles](#wire-profiles))

### 2. Request/Response Mocking
- **Pattern Matching** - Regex-based request matching
- **Priority System** - Control match order (1-100)
- **Template Responses** - Dynamic response generation
- **Multiple Formats** - JSON, XML, SOAP, ISO-8583, binary
- **Fault Injection** - Simulate network failures

### 3. Advanced Matching
- **JSONPath** - Match JSON structure and values
- **XPath** - Match XML elements and attributes
- **Length-Based** - Match by message size
- **Contains** - Simple substring matching
- **Regex** - Full regex pattern support

### 4. Stateful Scenarios
- **Multi-Step Flows** - Model complex interactions
- **State Transitions** - Change state based on requests
- **Conditional Matching** - Match only in specific states
- **Scenario Reset** - Return to initial state
- **Multiple Scenarios** - Run parallel scenarios

### 5. Response Features
- **Template Variables** - Dynamic content generation
- **SpEL Expressions** - Evaluate expressions
- **Delays** - Simulate latency
- **Custom Delimiters** - Per-mapping request and response framing
- **Proxy Mode** - Forward to real backend

---

## Feature Details

### Pattern Matching

#### Basic Patterns
```regex
^ECHO.*          # Starts with ECHO
.*PAYMENT.*      # Contains PAYMENT
^[0-9]{4}$       # Exactly 4 digits
```

#### Advanced Patterns
```regex
^0[12]00.*       # ISO-8583 auth/financial
<soap:Envelope.* # SOAP messages
^\{.*"type".*\}$ # JSON with type field
```

### Template Variables

#### Built-in Variables
```
{{message}}              - Original request
{{now}}                  - Current timestamp (ISO)
{{nowIso}}              - Current timestamp (ISO)
{{uuid}}                - Random UUID
{{randInt(1,100)}}      - Random integer
{{randLong(min,max)}}   - Random long (use strings for large numbers)
```

#### SpEL Functions
```
{{eval(#md5_hex(message))}}           - MD5 hash
{{eval(#sha256_hex(message))}}        - SHA-256 hash
{{eval(#base64_encode(message))}}     - Base64 encode
{{eval(#base64_decode(message))}}     - Base64 decode
{{eval(#hmac_sha256_hex(key,msg))}}   - HMAC-SHA256
{{eval(#regex_match(msg,pattern))}}   - Regex test
{{eval(#regex_extract(msg,pattern,1))}} - Extract group
{{eval(#json_path(payload,path))}}    - JSONPath query
{{eval(#date_format(now,pattern))}}   - Format date
```

### Fault Injection

Simulate real-world failures:

#### Connection Reset
```json
{
  "responseTemplate": "{{fault:CONNECTION_RESET}}"
}
```
Closes connection immediately without response.

#### Empty Response
```json
{
  "responseTemplate": "{{fault:EMPTY_RESPONSE}}"
}
```
Sends nothing, keeps connection open.

#### Malformed Response
```json
{
  "responseTemplate": "{{fault:MALFORMED_RESPONSE}}"
}
```
Sends corrupted data.

#### Random Data
```json
{
  "responseTemplate": "{{fault:RANDOM_DATA}}"
}
```
Sends random bytes.

### Proxy Mode

Forward requests to real backend:

```json
{
  "id": "proxy-to-prod",
  "pattern": "^PROD_.*",
  "responseTemplate": "{{proxy:production-server:9090}}",
  "priority": 50
}
```

Useful for:
- Partial mocking (some requests mocked, others proxied)
- Recording real responses
- Testing with real backend

---

## Use Cases

### 1. API Testing
Mock REST/SOAP APIs for testing:
```json
{
  "pattern": "^\\{.*\"endpoint\":\"/users\".*\\}$",
  "response": "{\"users\":[{\"id\":1,\"name\":\"Alice\"}]}",
  "priority": 20
}
```

### 2. Financial Systems
Mock ISO-8583 payment switches:
```json
{
  "pattern": "^0200.*",
  "response": "0210{{message:4}}00",
  "priority": 50,
  "description": "Authorization response"
}
```

### 3. Legacy System Integration
Mock mainframe or legacy TCP services:
```json
{
  "pattern": "^STX.*ETX$",
  "response": "STX{{eval(#md5_hex(message))}}ETX",
  "wireProfile": "STX_ETX",
  "responseDelimiter": "",
  "priority": 30
}
```

### 7. Multi-Line XML / Document Protocols
Accumulate the full XML document before matching:
```json
{
  "id": "xml-doc-handler",
  "wireProfile": "DELIMITER",
  "requestDelimiter": "</Document>",
  "requestPattern": ".*<RequestBody>.*",
  "responseTemplate": "<?xml version=\"1.0\"?>...<ResponseBody>...</ResponseBody></Document>",
  "responseDelimiter": "",
  "priority": 10
}
```
`wireProfile: DELIMITER` tells the server to buffer bytes until `requestDelimiter` is seen
before attempting to match. `responseDelimiter: ""` means the response body is written as-is.

### 8. Binary Length-Prefixed Protocols
Handle protocols with a 2-byte or 4-byte length header:
```json
{
  "id": "iso8583-2byte-len",
  "wireProfile": "LENGTH_PREFIX_2B",
  "requestPattern": "^0200.*",
  "responseTemplate": "0210{{message:4}}00",
  "responseDelimiter": "",
  "priority": 100
}
```
The server strips the inbound length header before matching and automatically prepends
a 2-byte length header on the response — matching what `LengthPrefix2BResponseReader` expects.

### 9. Fixed-Length Frames
Handle protocols where every message is exactly N bytes:
```json
{
  "id": "fixed-256",
  "wireProfile": "FIXED_LENGTH",
  "fixedFrameLength": 256,
  "requestPattern": ".*",
  "responseTemplate": "{{message}}",
  "responseDelimiter": "",
  "priority": 50
}
```

### 4. Load Testing
Generate realistic responses under load:
```json
{
  "pattern": ".*",
  "response": "{{uuid}}|{{now}}|SUCCESS",
  "fixedDelayMs": 50,
  "priority": 1
}
```

### 5. Chaos Engineering
Inject failures to test resilience:
```json
{
  "pattern": "^CHAOS_.*",
  "response": "{{fault:CONNECTION_RESET}}",
  "priority": 100
}
```

### 6. Multi-Step Workflows
Model complex business flows:
```json
[
  {
    "pattern": "^LOGIN.*",
    "scenarioName": "user-session",
    "requiredState": "Started",
    "response": "SESSION_{{uuid}}",
    "newState": "Authenticated"
  },
  {
    "pattern": "^QUERY.*",
    "scenarioName": "user-session",
    "requiredState": "Authenticated",
    "response": "DATA_{{randInt(1,1000)}}",
    "newState": "Authenticated"
  },
  {
    "pattern": "^LOGOUT.*",
    "scenarioName": "user-session",
    "requiredState": "Authenticated",
    "response": "BYE",
    "newState": "Completed"
  }
]
```

---

## Integration Patterns

### 1. Development Environment
Replace real services during development:
```yaml
services:
  app:
    environment:
      PAYMENT_SERVICE_URL: tcp://tcp-mock:8080
  tcp-mock:
    image: tcp-mock-server:latest
    ports:
      - "8080:8080"
```

### 2. CI/CD Pipeline
Mock external dependencies in tests:
```bash
# Start mock server
docker run -d -p 8080:8080 tcp-mock-server:latest

# Run tests
mvn test -Dpayment.service.url=tcp://localhost:8080

# Stop mock server
docker stop tcp-mock
```

### 3. Performance Testing
Generate consistent responses for load tests:
```yaml
# docker-compose.yml
services:
  tcp-mock:
    image: tcp-mock-server:latest
    volumes:
      - ./mappings:/app/mappings
    environment:
      - POCKETHIVE_TCP_MOCK_DEFAULT_DELAY=10
```

### 4. Demo Environments
Provide realistic demos without real backends:
```bash
# Load demo mappings
curl -X POST http://localhost:8080/api/ui/mappings \
  -H "Content-Type: application/json" \
  -d @demo-mappings.json
```

---

## Advanced Features

### 1. Request Verification
Verify expected requests were received:
```javascript
// Add verification
POST /api/enterprise/verification/expect
{
  "pattern": "^PAYMENT.*",
  "countType": "atLeast",
  "count": 10
}

// Check results
GET /api/enterprise/verification/results
```

### 2. Recording Mode
Capture real traffic for mapping creation:
```javascript
// Start recording
POST /api/enterprise/recording/start

// Send traffic...

// Stop recording
POST /api/enterprise/recording/stop

// Captured requests available in UI
```

### 3. Bulk Operations
Manage multiple mappings:
```bash
# Export all mappings
curl http://localhost:8080/api/ui/mappings > mappings.json

# Import mappings
curl -X POST http://localhost:8080/api/ui/mappings \
  -H "Content-Type: application/json" \
  -d @mappings.json
```

### 4. Global Settings
Configure server-wide behavior:
```javascript
// Set global headers
POST /api/enterprise/settings
{
  "globalHeaders": {
    "X-Server": "TCP-Mock",
    "X-Version": "1.0"
  },
  "defaultDelay": 50,
  "corsEnabled": true
}
```

---

## Performance Characteristics

### Throughput
- **Concurrent Connections**: 1000+
- **Requests/Second**: 10,000+
- **Response Time**: <5ms (without delay)
- **Memory**: ~100MB base + ~1KB per mapping

### Scalability
- **Mappings**: Tested with 10,000+ mappings
- **Requests**: Handles millions of requests
- **Scenarios**: 100+ concurrent scenarios
- **Recording**: Minimal overhead when disabled

### Limits
- **Max Message Size**: 10MB (configurable)
- **Max Mappings**: No hard limit (memory dependent)
- **Max Connections**: OS dependent (typically 10,000+)
- **Request History**: 10,000 requests (configurable)

---

## Security Features

### Authentication
- **Dashboard Login**: Username/password protection
- **Environment Variables**: Configurable credentials
- **Session Management**: Secure session handling

### Network Security
- **TLS/SSL Support**: Secure TCP connections
- **Certificate Validation**: Optional cert verification
- **CORS Control**: Configurable cross-origin policy

### Data Protection
- **No Persistence**: Requests not saved to disk by default
- **Memory Only**: All data in memory (cleared on restart)
- **Export Control**: Manual export only

---

## Monitoring & Observability

### Metrics
- **Request Count**: Total requests received
- **Match Rate**: Percentage of matched requests
- **Response Times**: Average/min/max latency
- **Error Rate**: Failed requests

### Logging
- **Structured Logs**: JSON format
- **Log Levels**: DEBUG, INFO, WARN, ERROR
- **Request Logging**: Full request/response logging
- **Scenario Logging**: State transition tracking

### Health Checks
```bash
# Health endpoint
curl http://localhost:8080/__admin/health

# Metrics endpoint
curl http://localhost:8080/api/metrics
```

---

## Comparison with Alternatives

### vs WireMock
- ✅ **TCP Support**: Native TCP (WireMock is HTTP-only)
- ✅ **Binary Data**: Full binary support
- ✅ **Stateful Scenarios**: Built-in state management
- ✅ **Real-time UI**: Live request monitoring
- ⚖️ **HTTP Mocking**: WireMock better for HTTP

### vs MockServer
- ✅ **Simpler Setup**: No complex configuration
- ✅ **Better UI**: More intuitive interface
- ✅ **Template System**: Powerful templating
- ⚖️ **Protocol Support**: MockServer supports more protocols

### vs Custom Scripts
- ✅ **No Coding**: Configuration-based
- ✅ **UI Management**: Visual configuration
- ✅ **Pattern Matching**: Advanced matching built-in
- ✅ **Scenarios**: State management included

---

## Getting Help

### Documentation
- **UI User Guide**: [UI-USER-GUIDE.md](UI-USER-GUIDE.md)
- **Quick Reference**: [QUICK-REFERENCE.md](QUICK-REFERENCE.md)
- **Scenario Setup**: [SCENARIO-SETUP.md](SCENARIO-SETUP.md)
- **Deployment**: [DEPLOYMENT-CHECKLIST.md](DEPLOYMENT-CHECKLIST.md)

### Examples
- Check `mappings/` folder for example configurations
- Use template selector in UI for quick starts
- Review test console examples

### Troubleshooting
1. Enable DEBUG logging
2. Check request journal for unmatched requests
3. Verify pattern syntax with regex tool
4. Test with test console
5. Review server logs

---

## Wire Profiles

The `wireProfile` field on a mapping declares the binary framing contract for the TCP
connection. When set on the highest-priority enabled mapping, it overrides auto-detection.

| `wireProfile` | Inbound framing | Outbound framing | Use case |
|---|---|---|---|
| `AUTO` *(default)* | Auto-detected from first bytes | Matches detected profile | Unknown or mixed protocols |
| `LINE` | Newline (`\n`) delimited | Append `responseDelimiter` | Plain text protocols |
| `DELIMITER` | Buffer until `requestDelimiter` | Append `responseDelimiter` | XML documents, custom text |
| `LENGTH_PREFIX_2B` | Strip 2-byte big-endian length header | Prepend 2-byte length header | ISO-8583 MC wire profile, financial |
| `LENGTH_PREFIX_4B` | Strip 4-byte big-endian length header | Prepend 4-byte length header | Generic binary protocols |
| `FIXED_LENGTH` | Read exactly `fixedFrameLength` bytes | No delimiter added | Mainframe, fixed-record systems |
| `STX_ETX` | Buffer between `0x02` and `0x03` bytes | No delimiter added | Legacy binary, POS terminals |
| `FIRE_FORGET` | Line-delimited (no response sent) | None | One-way notifications |

### Notes
- `wireProfile` is resolved from the **highest-priority enabled mapping**. All connections
  on the port share the same framing — design mappings so the highest-priority one declares
  the correct profile for the protocol in use.
- `FIXED_LENGTH` requires `fixedFrameLength` to be set. If omitted, defaults to 128 bytes.
- `LENGTH_PREFIX_2B` and `LENGTH_PREFIX_4B` automatically handle both inbound stripping
  and outbound prepending — the `responseTemplate` should contain only the payload body.
- `STX_ETX` passes the full frame including STX/ETX bytes to the matcher and template engine.
- When `wireProfile` is absent or `AUTO`, the server inspects the first 4 bytes to detect
  the protocol. Explicit declaration is always preferred for production mappings.

---

## Roadmap
### Planned Features
- GraphQL support
- WebSocket mocking
- gRPC support
- Distributed recording
- Machine learning pattern suggestions
- Performance profiling
- Request replay
- Mapping versioning

### Community Contributions
We welcome contributions! Areas of interest:
- Additional protocol support
- UI enhancements
- Performance optimizations
- Documentation improvements
- Example scenarios
