# Redis Sequence Generator

> Status: archived. Current Pebble/SpEL templating documentation lives in
> `../guides/templating-basics.md` and `../guides/templating-advanced.md`.

The Redis sequence generator provides deterministic, thread-safe sequence generation for unique identifiers in PocketHive scenarios. Sequences are backed by Redis INCR operations and support multiple formats (numeric, alphabetic, alphanumeric, binary, hex) with printf-style formatting.

## Overview

Use Redis sequences when you need:

- **Unique transaction IDs** that increment across all requests
- **Per-SUT sequence counters** that persist across swarm restarts
- **Multiple independent sequences** within a single scenario
- **Thread-safe generation** across concurrent workers
- **Deterministic formatting** with zero-padding and custom widths

Sequences are accessed via SpEL functions in Pebble templates and automatically wrap when reaching the maximum value calculated from the format string.

## Configuration

### Worker SDK Configuration

Redis connection settings are configured in scenarios:

```yaml
id: my-scenario
name: My Scenario
template:
  bees:
    - role: generator
      config:
        redis:
          enabled: true
          host: redis
          port: 6379
          username: myuser      # Optional
          password: mypass      # Optional
          ssl: false            # Optional
        worker:
          message:
            body: |
              {
                "txnId": "{{ eval("#sequence('my-key', 'numeric', '%010d')") }}"
              }
```

The configuration is enabled by default and uses `redis:6379` when not specified. Each worker maintains thread-local Redis connections for optimal performance.

### SUT Configuration

Define sequence keys directly in scenarios:

```yaml
id: test-scenario
name: Test Scenario
template:
  bees:
    - role: generator
      config:
        worker:
          message:
            body: |
              {
                "txnId": "{{ eval("#sequence('my-txn-seq', 'numeric', '%010d')") }}"
              }
```

Each scenario can define its own sequence keys. Multiple scenarios can use the same keys to share sequences or different keys for isolation.

## Usage in Scenarios

### Basic Usage

Use the `#sequence()` function in Pebble templates with three parameters:

```yaml
id: basic-sequence
name: Basic Sequence Example
template:
  bees:
    - role: generator
      config:
        worker:
          message:
            body: |
              {
                "txnId": "{{ eval("#sequence('my-key', 'numeric', '%010d')") }}"
              }
```

**Parameters:**
1. **key** (string) – Redis key for the sequence counter
2. **mode** (string) – Character set: `alpha`, `numeric`, `alphanum`, `binary`, `hex` (append `_lower` for lowercase)
3. **format** (string) – Printf-style format with `%S` (uppercase), `%s` (lowercase), `%d` (numeric)

### Using Sequence Keys

Reference sequence keys directly in templates:

```yaml
id: sequence-keys
name: Sequence Keys Example
template:
  bees:
    - role: generator
      config:
        worker:
          message:
            body: |
              {
                "transactionId": "{{ eval("#sequence('my-txn-seq', 'numeric', '%010d')") }}"
              }
```

This pattern ensures:
- Different scenarios use independent sequences
- Sequences persist across swarm restarts
- Keys can be environment-specific

### Multiple Sequences

Use multiple keys for different identifier types:

```yaml
id: multiple-sequences
name: Multiple Sequences Example
template:
  bees:
    - role: generator
      config:
        worker:
          message:
            body: |
              {
                "transactionId": "{{ eval("#sequence('my-txn-seq', 'numeric', '%010d')") }}",
                "referenceId": "{{ eval("#sequence('my-ref-seq', 'alpha', '%6S')") }}",
                "batchId": "{{ eval("#sequence('my-batch-seq', 'alphanum', '%8S')") }}"
              }
```
```

### Advanced Options

Use `#sequenceWith()` for custom start offsets and maximum values:

```yaml
body: |
  {
    "id": "{{ eval("#sequenceWith('custom-key', 'numeric', '%06d', 1000, 999999)") }}"
  }
```

**Additional Parameters:**
4. **startOffset** (Long, nullable) – Starting value (default: 1)
5. **maxSequence** (Long, nullable) – Maximum before wrapping (default: auto-calculated from format)

**Common Examples:**

```yaml
# Start at 5000, auto-calculate max
{{ eval("#sequenceWith('key', 'numeric', '%06d', 5000, null)") }}

# Start at 1, limit to 1000 before wrapping
{{ eval("#sequenceWith('limited-key', 'alpha', '%3S', null, 1000)") }}

# Custom range: 100-500
{{ eval("#sequenceWith('range-key', 'numeric', '%03d', 100, 500)") }}

# High-volume transaction IDs starting at 1000000
{{ eval("#sequenceWith('txn-key', 'numeric', '%010d', 1000000, null)") }}
```

## Format Strings

### Format Syntax

Format strings use printf-style tokens:

- `%S` – Uppercase alphabetic/alphanumeric characters
- `%s` – Lowercase alphabetic/alphanumeric characters
- `%d` – Numeric digits
- `%0Nd` – Zero-padded numeric (N = width)
- `%NS` / `%Ns` – Fixed-width alphabetic (N = width)

### Examples

| Format | Mode | Output Examples |
|--------|------|-----------------|
| `%010d` | numeric | `0000000001`, `0000000002`, `0000000123` |
| `%6S` | alpha | `AAAAAA`, `AAAAAB`, `AAAAAC` |
| `%4s%2d` | alpha | `aaaa01`, `aaaa02`, `aaab01` |
| `%8S` | alphanum | `AAAAAAAA`, `AAAAAAAB`, `0000000A` |
| `%4S` | hex | `0000`, `0001`, `FFFF` |
| `TXN-%06d` | numeric | `TXN-000001`, `TXN-000002` |

### Modes

| Mode | Characters | Use Case |
|------|-----------|----------|
| `alpha` | A-Z | Uppercase reference codes |
| `alpha_lower` | a-z | Lowercase reference codes |
| `numeric` | 0-9 | Transaction IDs, counters |
| `alphanum` | A-Z, 0-9 | Mixed identifiers |
| `alphanum_lower` | a-z, 0-9 | Lowercase mixed identifiers |
| `binary` | 0-1 | Binary flags |
| `hex` | 0-9, A-F | Hexadecimal identifiers |
| `hex_lower` | 0-9, a-f | Lowercase hex identifiers |

## Sequence Behavior

### Incrementing

Sequences use odometer-style incrementing where each format segment has its own modulo range:

```
Format: %2S%2d
Sequence: AA01 → AA02 → ... → AA99 → AB01 → AB02 → ... → ZZ99 → AA01 (wraps)
```

### Wrapping

Sequences automatically wrap when reaching the maximum value:

- **Auto-calculated max**: Derived from format (e.g., `%4S` in alpha mode = 26^4 = 456,976)
- **Custom max**: Specified via `#sequenceWith()` fifth parameter
- **Wrapping behavior**: `(value - 1) % max + 1` ensures sequences stay in range 1..max

### Thread Safety

- **Redis INCR** is atomic across all threads and processes
- **ThreadLocal connections** prevent connection sharing issues
- **Concurrent access** is safe – multiple workers can call the same sequence key simultaneously

### Persistence

- Sequences persist in Redis across swarm restarts
- Use `FLUSHDB` or `DEL ph:seq:<key>` to reset a sequence
- Different Redis instances provide complete isolation

## Examples

### HTTP Transaction Scenario

```yaml
id: payment-transactions
name: Payment Transaction Load Test
template:
  bees:
    - role: generator
      instances: 1
      config:
        inputs:
          type: SCHEDULER
          scheduler:
            ratePerSec: 100
        worker:
          message:
            method: POST
            path: /api/payments
            headers:
              content-type: application/json
            body: |
              {% set now = eval('now') %}
              {
                "transactionId": "{{ eval("#sequence('payment-txn-seq', 'numeric', '%012d')") }}",
                "referenceId": "{{ eval("#sequence('payment-ref-seq', 'alphanum', '%8S')") }}",
                "amount": 100.00,
                "timestamp": "{{ eval("#date_format(now, 'yyyy-MM-dd''T''HH:mm:ssZ')") }}"
              }
```

### TCP Message Scenario

```yaml
id: tcp-messaging
name: TCP Message Sequence Test
template:
  bees:
    - role: generator
      instances: 1
      config:
        inputs:
          type: SCHEDULER
          scheduler:
            ratePerSec: 50
        worker:
          message:
            body: |
              MSG{{ eval("#sequence('tcp-msg-seq', 'numeric', '%08d')") }}|DATA|END
```

## Performance Characteristics

- **Redis latency**: ~1ms per INCR operation (network-dependent)
- **Format caching**: Parsed formats cached per worker instance
- **Mode caching**: Enum lookups cached to avoid repeated valueOf() calls
- **Math optimization**: Bit shifts for powers of 2, lookup tables for common bases
- **Memory**: ~50-60% reduction via pre-computed modulo values and interned cache keys

Typical throughput: 1000+ sequences/sec per worker with local Redis.

## Troubleshooting

### Sequences Not Incrementing

**Symptom**: Same value returned on every call

**Causes**:
- Redis not running or unreachable
- Incorrect host/port configuration
- Network connectivity issues

**Solution**: Check Redis connectivity and configuration:

```bash
redis-cli -h redis -p 6379 PING
redis-cli -h redis -p 6379 GET ph:seq:my-key
```

### Format Validation Errors

**Symptom**: `IllegalArgumentException: Format must contain at least one valid token`

**Cause**: Format string missing `%S`, `%s`, or `%d` tokens

**Solution**: Ensure format contains at least one valid token:

```yaml
# ❌ Invalid
{{ eval('#sequence("key", "numeric", "TXN-")') }}

# ✅ Valid
{{ eval('#sequence("key", "numeric", "TXN-%06d")') }}
```

### Sequence Collisions

**Symptom**: Two scenarios produce overlapping sequences

**Cause**: Both scenarios use the same Redis key

**Solution**: Use scenario-specific keys or include scenario/swarm ID:

```yaml
# Per-scenario (recommended)
{{ eval("#sequence('my-scenario-txn-seq', 'numeric', '%010d')") }}

# Per-swarm
{% set key = workItem.headers['x-ph-swarm-id'] ~ '-seq' %}
{{ eval("#sequence('" + key + "', 'numeric', '%010d')") }}
```

## See Also

- [Worker SDK Quick Start](worker-sdk-quickstart.md) – Template rendering and SpEL functions
- [Scenario Guide](../scenarios/scenario-guide.md) – Scenario structure and SUT configuration
- [Control Plane Worker Guide](../control-plane/worker-guide.md) – Worker configuration properties
