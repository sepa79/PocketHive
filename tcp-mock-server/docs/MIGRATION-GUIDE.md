# Migration Guide - Enhanced TCP Mock Server

## Overview

The TCP Mock Server has been enhanced with complete WireMock parity. Existing mappings continue to work without changes, but you can now leverage advanced features.

---

## Backward Compatibility

### ✅ All Existing Mappings Work Unchanged

```json
{
  "id": "echo",
  "requestPattern": "^ECHO.*",
  "responseTemplate": "{{message}}",
  "responseDelimiter": "\n",
  "priority": 5
}
```

**No changes required.** This mapping continues to work exactly as before.

---

## New Features You Can Adopt

### 1. Advanced Request Matching

#### Before: Pattern-only matching
```json
{
  "requestPattern": "^\\{.*\\}$",
  "responseTemplate": "{\"status\":\"ok\"}"
}
```

#### After: Add field-level matching
```json
{
  "requestPattern": "^\\{.*\\}$",
  "advancedMatching": {
    "jsonPath": {
      "expression": "$.action",
      "equalTo": "payment"
    }
  },
  "responseTemplate": "{\"status\":\"ok\"}"
}
```

**Benefit:** Match only JSON messages where `action` field equals "payment".

---

### 2. Request Field Extraction

#### Before: Static responses
```json
{
  "responseTemplate": "{\"status\":\"approved\",\"id\":\"12345\"}"
}
```

#### After: Extract fields from request
```json
{
  "responseTemplate": "{\"status\":\"approved\",\"id\":\"{{request.jsonPath '$.transactionId'}}\",\"amount\":\"{{request.jsonPath '$.amount'}}\"}"
}
```

**Benefit:** Echo back specific fields from the request.

---

### 3. Per-Mapping Delays

#### Before: Global latency only
```json
{
  "responseTemplate": "RESPONSE"
}
```

#### After: Add fixed delay per mapping
```json
{
  "responseTemplate": "RESPONSE",
  "fixedDelayMs": 1000
}
```

**Benefit:** Simulate slow responses for specific operations.

---

### 4. Fault Injection

#### Before: Manual error responses
```json
{
  "responseTemplate": "ERROR"
}
```

#### After: Use fault injection
```json
{
  "responseTemplate": "{{fault:CONNECTION_RESET}}"
}
```

**Options:**
- `CONNECTION_RESET` - Close connection immediately
- `EMPTY_RESPONSE` - Send empty response
- `MALFORMED_RESPONSE` - Send corrupted data
- `RANDOM_DATA` - Send random bytes

**Benefit:** Test client resilience to network failures.

---

### 5. Proxying

#### Before: Mock all responses
```json
{
  "responseTemplate": "MOCKED_RESPONSE"
}
```

#### After: Proxy to real system
```json
{
  "responseTemplate": "{{proxy:real-system.example.com:8080}}"
}
```

**Benefit:** Record/replay mode - forward to real system when needed.

---

## Migration Examples

### Example 1: Enhance JSON Payment Mapping

**Before:**
```json
{
  "id": "payment",
  "requestPattern": "^\\{.*payment.*\\}$",
  "responseTemplate": "{\"status\":\"approved\"}",
  "priority": 10
}
```

**After:**
```json
{
  "id": "payment",
  "requestPattern": "^\\{.*\\}$",
  "advancedMatching": {
    "jsonPath": {
      "expression": "$.type",
      "equalTo": "payment"
    }
  },
  "responseTemplate": "{\"status\":\"approved\",\"transactionId\":\"{{uuid}}\",\"amount\":\"{{request.jsonPath '$.amount'}}\",\"timestamp\":\"{{now}}\"}",
  "fixedDelayMs": 100,
  "priority": 10
}
```

**Improvements:**
- Precise JSON field matching
- Extract amount from request
- Generate unique transaction ID
- Add timestamp
- Simulate 100ms processing delay

---

### Example 2: Add Fault Testing

**Before:**
```json
{
  "id": "echo",
  "requestPattern": "^ECHO.*",
  "responseTemplate": "{{message}}",
  "priority": 5
}
```

**After:** Add fault injection variant
```json
[
  {
    "id": "echo-normal",
    "requestPattern": "^ECHO:NORMAL:.*",
    "responseTemplate": "{{message}}",
    "priority": 10
  },
  {
    "id": "echo-fault",
    "requestPattern": "^ECHO:FAULT:.*",
    "responseTemplate": "{{fault:CONNECTION_RESET}}",
    "priority": 10
  }
]
```

**Benefit:** Test both normal and fault scenarios with different prefixes.

---

### Example 3: Add XML Field Extraction

**Before:**
```json
{
  "id": "soap",
  "requestPattern": "^<.*>.*</.*>$",
  "responseTemplate": "<response><status>success</status></response>",
  "priority": 10
}
```

**After:**
```json
{
  "id": "soap",
  "requestPattern": "^<.*>.*</.*>$",
  "advancedMatching": {
    "xmlPath": {
      "expression": "operation",
      "equalTo": "GetBalance"
    }
  },
  "responseTemplate": "<response><status>success</status><balance>{{randomValue type='INT'}}</balance><accountId>{{request.xmlPath 'accountId'}}</accountId></response>",
  "fixedDelayMs": 50,
  "priority": 10
}
```

**Improvements:**
- Match specific SOAP operation
- Extract accountId from request
- Generate random balance
- Add realistic delay

---

## Testing Your Migrations

### 1. Test Existing Mappings
```bash
# Verify old mappings still work
echo "ECHO:test" | nc localhost 9090
```

### 2. Test New Features
```bash
# Test JSON field extraction
echo '{"type":"payment","amount":100}' | nc localhost 9090

# Test fault injection
echo "FAULT_TEST:reset" | nc localhost 9090
```

### 3. Verify via Admin API
```bash
# Check all mappings loaded
curl http://localhost:8090/__admin/mappings

# Check request history
curl http://localhost:8090/__admin/requests
```

---

## Best Practices

### 1. Use Priority Wisely
- Specific patterns: priority 20-30
- General patterns: priority 10-15
- Catch-all/default: priority 1-5

### 2. Leverage Field Extraction
- Extract IDs, amounts, timestamps from requests
- Echo back correlation IDs for traceability

### 3. Add Delays for Realism
- Fast operations: 10-50ms
- Medium operations: 100-500ms
- Slow operations: 1000-5000ms

### 4. Test Fault Scenarios
- Add fault variants for critical paths
- Use different prefixes to trigger faults

### 5. Use Stateful Scenarios
- Model multi-step workflows
- Track state transitions

---

## Rollback Plan

If you encounter issues, simply remove the new fields:

```json
{
  "id": "mapping",
  "requestPattern": "^PATTERN.*",
  "responseTemplate": "RESPONSE"
}
```

The server will use defaults:
- `responseDelimiter`: `\n`
- `priority`: `1`
- `advancedMatching`: `null` (pattern-only)
- `fixedDelayMs`: `null` (no delay)

---

## Support

For questions or issues:
1. Check [WIREMOCK-PARITY-COMPLETE.md](WIREMOCK-PARITY-COMPLETE.md) for feature documentation
2. Review example mappings in `mappings/` directory
3. Test with admin API: `http://localhost:8090/__admin/`
