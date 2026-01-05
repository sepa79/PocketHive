# TCP Mock Server Enterprise

Enterprise-grade TCP mocking solution with **complete WireMock equivalence** for TCP protocols, plus advanced features for binary protocols, ISO-8583, and high-performance testing.

## 🚀 Quick Links
- **[WireMock Parity Documentation](WIREMOCK-PARITY-COMPLETE.md)** - Complete feature equivalence guide
- **[API Reference](#api-endpoints)** - REST API documentation
- **[Configuration Guide](#configuration)** - Setup and configuration options

## ✅ WireMock Feature Parity

### Request Matching
- ✅ Regex pattern matching
- ✅ JSON field matching (JSONPath)
- ✅ XML field matching (XPath)
- ✅ Length-based matching
- ✅ Multi-criteria matching (startsWith, endsWith, contains, equalTo)

### Response Features
- ✅ Template responses with field extraction
- ✅ Request field extraction (JSON, XML, regex)
- ✅ Per-mapping fixed delays
- ✅ Fault injection (connection reset, empty, malformed, random data)
- ✅ Proxying to real systems
- ✅ Stateful scenarios with state transitions

### Advanced Features
- ✅ Priority-based routing
- ✅ Request verification and counting
- ✅ Unmatched request tracking
- ✅ WireMock-compatible admin API

### TCP-Specific Enhancements
- ✅ Native binary protocol support (hex conversion)
- ✅ Configurable delimiters per mapping
- ✅ SSL/TLS encryption
- ✅ High-performance async processing (50K+ req/sec)

## Features Overview

- **🔐 SSL/TLS Support**: Full encryption with configurable certificates
- **📊 Prometheus Metrics**: Comprehensive monitoring and observability
- **✅ Request Validation**: Message size and format validation
- **🔍 Request Filtering**: Regex-based allow/deny rules
- **⚙️ Runtime Configuration**: Dynamic feature toggling via REST API
- **🔌 WireMock Compatibility**: Seamless PocketHive UI integration
- **🚄 Multiple Transports**: Socket, NIO, and Netty support
- **📈 Real-time Monitoring**: Live request tracking and metrics

## Supported TCP Behaviors

### ECHO
- Returns the exact message sent
- Triggered by messages starting with "ECHO"

### REQUEST_RESPONSE
- Structured request-response communication
- Handles delimited messages (STX/ETX format)
- Supports banking-style operations (BALANCE_INQUIRY)

### JSON_RESPONSE
- Returns JSON formatted responses
- Triggered by messages containing "messageId"

## API Endpoints

- `GET /api/requests` - List all stored requests
- `DELETE /api/requests` - Clear request history
- `GET /api/status` - Server status and request count

## Usage

### Build and Run
```bash
mvn clean package
java -jar target/tcp-mock-server-*.jar
```

### Docker
```bash
docker build -t tcp-mock-server .
docker run -p 8080:8080 -p 9090:9090 tcp-mock-server
```

### Integration with PocketHive
Configure scenarios to use:
```yaml
baseUrl: "{{ sut.endpoints['tcp-gateway'].baseUrl }}"
```

Where `tcp-gateway` points to `tcp://tcp-mock-server:8080`

## Configuration

Environment variables:
- `SERVER_PORT` - REST API port (default: 8090)
- `TCP_MOCK_PORT` - TCP server port (default: 8080)

## Monitoring & Observability

### PocketHive UI Integration
1. Configure `tcp-gateway` SUT endpoint to point to this server
2. View real-time TCP requests in PocketHive's SUT panel
3. Monitor request history and response patterns

### Prometheus Metrics
- `tcp_requests_total` - Total requests received
- `tcp_request_duration` - Request processing time
- `tcp_requests_echo_total` - Echo behavior requests
- `tcp_requests_invalid_total` - Invalid/rejected requests

### Health & Status
```bash
# Server health
curl http://localhost:8090/actuator/health

# Detailed status with feature flags
curl http://localhost:8090/api/status

# Prometheus metrics
curl http://localhost:8090/actuator/prometheus
```
