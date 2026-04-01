# TCP Mock Server - Quick Reference Card

## 🎯 Request Matching

```json
{
  "requestPattern": "^PATTERN.*",           // Regex (required)
  "advancedMatching": {                     // Optional
    "jsonPath": {"expression": "$.field", "equalTo": "value"},
    "xmlPath": {"expression": "tag", "equalTo": "value"},
    "length": {"greaterThan": 100, "lessThan": 1000},
    "startsWith": "PREFIX",
    "endsWith": "SUFFIX",
    "contains": "KEYWORD",
    "equalTo": "EXACT_MATCH"
  }
}
```

## 📝 Response Templates

### Basic Variables
```
{{message}}          - Original request
{{timestamp}}        - Current timestamp (ms)
{{uuid}}             - Random UUID
{{random}}           - Random integer
{{request.length}}   - Message length
```

### Field Extraction
```
{{request.jsonPath '$.field.subfield'}}
{{request.xmlPath 'tagName'}}
{{request.regex '^CMD:(\\w+):(.*)$' group 1}}
```

### Transformations
```
{{base64 encode}}    {{base64 decode}}
{{urlEncode}}        {{urlDecode}}
{{uppercase}}        {{lowercase}}
```

### Date/Time
```
{{now}}                                    - ISO timestamp
{{now format='yyyy-MM-dd HH:mm:ss'}}      - Custom format
```

### Random Values
```
{{randomValue type='UUID'}}
{{randomValue type='INT'}}
{{randomValue type='LONG'}}
```

### State Variables
```
{{state.variableName}}
```

## ⚡ Special Responses

### Fault Injection
```json
{"responseTemplate": "{{fault:CONNECTION_RESET}}"}
{"responseTemplate": "{{fault:EMPTY_RESPONSE}}"}
{"responseTemplate": "{{fault:MALFORMED_RESPONSE}}"}
{"responseTemplate": "{{fault:RANDOM_DATA}}"}
```

### Proxying
```json
{"responseTemplate": "{{proxy:hostname:port}}"}
```

## ⏱️ Delays

```json
{
  "fixedDelayMs": 1000    // Per-mapping delay in milliseconds
}
```

## 🔄 Stateful Scenarios

```json
{
  "scenarioName": "payment-flow",
  "requiredScenarioState": "Started",
  "newScenarioState": "Authorized"
}
```

## 🎚️ Priority

```json
{
  "priority": 25    // Higher = evaluated first (default: 1)
}
```

## 📊 Delimiters

```json
{
  "responseDelimiter": "\n"       // Newline (default)
  "responseDelimiter": "\u0003"   // ETX character
  "responseDelimiter": ""         // No delimiter (binary)
}
```

## 🔍 Admin API

```bash
# List all mappings
GET /__admin/mappings

# List all requests
GET /__admin/requests

# List unmatched requests
GET /__admin/requests/unmatched

# Get scenarios
GET /__admin/scenarios

# Reset all
POST /__admin/reset

# Health check
GET /__admin/health
```

## 📋 Complete Mapping Example

```json
{
  "id": "payment-processing",
  "requestPattern": "^\\{.*\\}$",
  "advancedMatching": {
    "jsonPath": {
      "expression": "$.type",
      "equalTo": "payment"
    },
    "length": {
      "greaterThan": 50
    }
  },
  "responseTemplate": "{\"status\":\"approved\",\"transactionId\":\"{{uuid}}\",\"amount\":\"{{request.jsonPath '$.amount'}}\",\"timestamp\":\"{{now}}\"}",
  "responseDelimiter": "\n",
  "fixedDelayMs": 100,
  "description": "Payment processing with validation",
  "priority": 25,
  "enabled": true,
  "scenarioName": "payment-flow",
  "requiredScenarioState": "Started",
  "newScenarioState": "Authorized"
}
```

## 🚀 Quick Start

### 1. Create Mapping File
Save as `mappings/my-mapping.json`

### 2. Start Server
```bash
docker run -p 8090:8090 -p 9090:9090 \
  -v ./mappings:/app/mappings \
  tcp-mock-server
```

### 3. Test
```bash
# Send request
echo "TEST_MESSAGE" | nc localhost 9090

# Check via API
curl http://localhost:8090/__admin/requests
```

## 📚 Documentation

- **[WIREMOCK-PARITY-COMPLETE.md](WIREMOCK-PARITY-COMPLETE.md)** - Full feature guide
- **[MIGRATION-GUIDE.md](MIGRATION-GUIDE.md)** - Migration from old mappings
- **[TCP-MOCK-WIREMOCK-PARITY-SUMMARY.md](TCP-MOCK-WIREMOCK-PARITY-SUMMARY.md)** - Implementation summary
