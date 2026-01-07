# TCP Mock Server - Capabilities & Features

Complete overview of TCP Mock Server capabilities, features, and use cases.

## Core Capabilities

### 1. TCP Protocol Support
- **Plain TCP** - Standard TCP socket connections
- **TLS/SSL** - Secure TCP connections (tcps://)
- **Multiple Transports** - Socket, NIO, Netty
- **Binary & Text** - Handle any data format
- **Custom Delimiters** - Configurable message boundaries

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
- **Custom Delimiters** - Control message framing
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
  "delimiter": "ETX",
  "priority": 30
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
      - TCP_MOCK_DEFAULT_DELAY=10
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
