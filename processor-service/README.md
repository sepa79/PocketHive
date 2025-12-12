# Processor Service

Calls the system under test and forwards responses downstream with support for HTTP and TCP protocols.

## Features

- **Multi-Protocol Support**: HTTP and TCP with pluggable transports
- **Connection Management**: HTTP pooling/keep-alive; TCP keep-alive only for socket transport
- **Resilience**: Rate limiting, retry logic, circuit breaker patterns
- **Security**: SSL/TLS support with configurable certificate verification
- **Performance**: Multiple transport implementations (Socket, NIO, Netty)
- **Observability**: Comprehensive metrics and distributed tracing

## Quick Start

### HTTP Processing
```yaml
pockethive:
  worker:
    config:
    baseUrl: "https://api.example.com"
    mode: THREAD_COUNT
    threadCount: 10
    keepAlive: true
```

### TCP Processing
```yaml
pockethive:
  worker:
    config:
      baseUrl: "tcp://system.example.com:8080"
      mode: RATE_PER_SEC
      ratePerSec: 100.0     # shared pacing across HTTP + TCP
    tcpTransport:
      type: socket
      connectionReuse: PER_THREAD # honoured only by `socket`
      maxRetries: 3
```

## Documentation

- [TCP Transport Guide](docs/TCP-TRANSPORT-GUIDE.md) - Comprehensive TCP configuration and usage
- [Architecture Reference](../docs/ARCHITECTURE.md) - Overall system architecture
- [Control Plane Rules](../docs/rules/control-plane-rules.md) - Signal and behavior details

## Configuration

The processor worker consumes traffic from the hive exchange and processes messages based on protocol type. Configuration is provided through control-plane config updates or local properties.

### Protocol Detection
Messages are routed to appropriate handlers based on the `protocol` field:
- `HTTP` → HttpProtocolHandler
- `TCP` → TcpProtocolHandler

### Transport Selection
TCP transport is selected via configuration:
- `socket` - Standard Java Socket (default); supports keep-alive reuse when `connectionReuse != NONE`
- `nio` - Java NIO (new connection per request)
- `netty` - Netty async framework (new connection per request)

Note: `tcps://` (TLS) is supported by `socket` and `netty`. TCP keep-alive reuse is currently implemented for the `socket` transport only.

## Examples

### Basic HTTP Template
```yaml
serviceId: default
callId: api-call
protocol: HTTP
method: POST
pathTemplate: "/api/users"
bodyTemplate: '{"name": "{{ payload.name }}"}'
headersTemplate:
  Content-Type: "application/json"
```

### TCP Echo Test
```yaml
serviceId: default
callId: tcp-echo
protocol: TCP
behavior: ECHO
transport: socket
bodyTemplate: "ECHO_{{ payload.message }}"
```

### Secure TCP Connection
```yaml
serviceId: secure
callId: tcps-call
protocol: TCP
behavior: REQUEST_RESPONSE
transport: socket
bodyTemplate: "{{ payload.data }}"
headersTemplate:
  x-ph-ssl: "true"
  x-ph-ssl-verify: "false"
```
