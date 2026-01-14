# Protocol Logging Configuration

## Overview
PocketHive includes structured logging for both TCP and HTTP protocols at the processor (client) and TCP mock server (server) levels.

---

## Processor Service (Client-Side Logging)

### What's Logged

**TCP Transports** (SocketTransport, NioTransport, NettyTransport):
- **TCP_SEND**: Outbound requests with host, port, payload size, and content
- **TCP_RECV**: Inbound responses with host, port, payload size, latency, and content

**HTTP Handler** (HttpProtocolHandler):
- **HTTP REQUEST**: Method, URL, headers, body
- **HTTP RESPONSE**: Method, URL, status code, latency, response body

### Log Format

**TCP**:
```
DEBUG TCP_SEND host=tcp-mock port=8585 bytes=45 payload={"messageType":"AUTH","amount":100}
DEBUG TCP_RECV host=tcp-mock port=8585 bytes=38 latency=23ms payload={"status":"APPROVED","code":"00"}
```

**HTTP**:
```
DEBUG HTTP REQUEST POST http://wiremock:8080/api/payment headers={"content-type":"application/json"} body={"amount":100}
DEBUG HTTP RESPONSE POST http://wiremock:8080/api/payment -> 200 latency=45ms body={"status":"success"}
```

### Enable Logging
Set log level to DEBUG in `processor-service/src/main/resources/application.yml`:

```yaml
logging:
  level:
    io.pockethive.processor.transport: DEBUG  # TCP transports
    io.pockethive.processor.handler: DEBUG    # HTTP handler
```

Or via environment variable:
```bash
LOGGING_LEVEL_IO_POCKETHIVE_PROCESSOR_TRANSPORT=DEBUG
LOGGING_LEVEL_IO_POCKETHIVE_PROCESSOR_HANDLER=DEBUG
```

This enables logging for:
- **TCP**: `SocketTransport`, `NioTransport`, `NettyTransport`
- **HTTP**: `HttpProtocolHandler`

### Use Cases
- **Debugging**: See exact payloads sent/received
- **Performance**: Track per-request latency
- **Protocol Analysis**: Verify message formats
- **Troubleshooting**: Identify timeout/delimiter issues

---

## TCP Mock Server (Server-Side Logging)

### What's Logged
Netty's `LoggingHandler` provides wire-level visibility:
- Connection establishment/teardown
- Bytes received (hex dump + ASCII)
- Bytes sent (hex dump + ASCII)
- Channel events (active, inactive, exception)

### Log Format
```
DEBUG [id: 0x12345678, L:/0:0:0:0:0:0:0:0:8585 - R:/127.0.0.1:54321] REGISTERED
DEBUG [id: 0x12345678, L:/127.0.0.1:8585 - R:/127.0.0.1:54321] ACTIVE
DEBUG [id: 0x12345678, L:/127.0.0.1:8585 - R:/127.0.0.1:54321] READ: 45B
         +-------------------------------------------------+
         |  0  1  2  3  4  5  6  7  8  9  a  b  c  d  e  f |
+--------+-------------------------------------------------+----------------+
|00000000| 7b 22 6d 65 73 73 61 67 65 54 79 70 65 22 3a 22 |{"messageType":"|
|00000010| 41 55 54 48 22 2c 22 61 6d 6f 75 6e 74 22 3a 31 |AUTH","amount":1|
|00000020| 30 30 7d                                        |00}             |
+--------+-------------------------------------------------+----------------+
DEBUG [id: 0x12345678, L:/127.0.0.1:8585 - R:/127.0.0.1:54321] WRITE: 38B
DEBUG [id: 0x12345678, L:/127.0.0.1:8585 ! R:/127.0.0.1:54321] INACTIVE
```

### Enable Logging
Set log level to DEBUG in `tcp-mock-server/src/main/resources/application.yml`:

```yaml
logging:
  level:
    io.netty.handler.logging.LoggingHandler: DEBUG
```

Or via environment variable:
```bash
LOGGING_LEVEL_IO_NETTY_HANDLER_LOGGING_LOGGINGHANDLER=DEBUG
```

### Use Cases
- **Binary Protocol Debugging**: See exact byte sequences
- **Connection Issues**: Track socket lifecycle
- **Performance Analysis**: Identify network delays
- **Security Auditing**: Monitor all TCP traffic

---

## Production Considerations

### Performance Impact
- **Processor Logging**: Minimal (only when DEBUG enabled, string conversion on-demand)
- **Netty Logging**: Moderate (hex dump formatting has CPU cost)

### Recommendations
1. **Development**: Enable both for full visibility
2. **Testing**: Enable processor logging only
3. **Production**: Disable or use INFO level (logs only errors)

### Log Volume
At 1000 msg/sec with 100-byte payloads:
- **Processor**: ~200KB/sec log output
- **Netty**: ~500KB/sec log output (hex dumps)

### Filtering
Use log aggregation tools (Loki, ELK) to filter by:
- `TCP_SEND` / `TCP_RECV` keywords
- Host/port combinations
- Latency thresholds

---

## Example: Debugging Delimiter Issues

**Problem**: Processor times out waiting for response

**Step 1**: Enable processor DEBUG logging
```bash
docker exec processor-service sh -c "echo 'logging.level.io.pockethive.processor.transport.SocketTransport=DEBUG' >> /app/application.yml"
docker restart processor-service
```

**Step 2**: Check logs
```bash
docker logs processor-service | grep TCP_
```

**Expected Output**:
```
TCP_SEND host=tcp-mock port=8585 bytes=50 payload={"type":"AUTH"}
TCP_RECV host=tcp-mock port=8585 bytes=0 latency=3000ms payload=
```

**Diagnosis**: Empty response = delimiter mismatch or no mapping found

**Step 3**: Enable Netty logging on TCP mock
```bash
docker logs tcp-mock-server | grep -A 20 "READ:"
```

**Expected Output**:
```
READ: 50B
... hex dump shows request received ...
WRITE: 0B
... no response sent ...
```

**Root Cause**: TCP mock has no matching mapping, returns empty response

---

## tcpdump Integration (Optional)

For packet-level analysis, add tcpdump sidecar:

```yaml
# docker-compose.yml
services:
  tcpdump:
    image: nicolaka/netshoot
    network_mode: "service:processor"
    command: tcpdump -i any -w /captures/tcp-$(date +%Y%m%d-%H%M%S).pcap tcp port 8585
    volumes:
      - ./tcp-captures:/captures
```

Analyze with Wireshark:
```bash
wireshark tcp-captures/*.pcap
```

---

## Summary

| Feature | Processor | TCP Mock | Use Case |
|---------|-----------|----------|----------|
| Payload logging | ✅ | ✅ | See message content |
| Hex dumps | ❌ | ✅ | Binary protocol debug |
| Latency tracking | ✅ | ❌ | Performance analysis |
| Connection lifecycle | ❌ | ✅ | Socket troubleshooting |
| Production-safe | ✅ | ⚠️ | Processor only |

**Quick Start**: Enable processor DEBUG logging for 90% of TCP debugging needs.
