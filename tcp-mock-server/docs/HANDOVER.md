# TCP Mock Server - Handover Document

> Historical guide. Examples, UI/API descriptions, compatibility statements and
> deployment steps below are unverified and are not the current runtime contract.
> Use [TCP capability qualification](WIREMOCK-PARITY.md) for supported workflows,
> known gaps and required evidence. Use the repository [usage guide](../../docs/USAGE.md)
> for runtime commands and supported ingress paths.

## 📦 Deliverables

### Historical implementation inventory
- Java backend with Netty TCP server; qualification incomplete
- Historical UI feature descriptions
- ✅ 15 JavaScript modules for modular functionality
- Test coverage is unqualified; no percentage is asserted
- ✅ Docker and Kubernetes deployment configs

### Documentation
- ✅ Production README with quick start
- ✅ WireMock parity documentation
- ✅ Deployment checklist
- ✅ Migration guide
- ✅ API reference
- ✅ Scenario setup guide

### Example Mappings
- ✅ 18 example mapping files in `mappings/`
- ✅ Covers JSON, XML, ISO-8583, binary protocols
- ✅ Fault injection examples
- ✅ Proxy configuration examples

## 🎯 System Status

### Backend — historical feature list
- ✅ TCP server with Netty
- ✅ Pattern-based message routing
- ✅ Binary protocol support (ByteBuf)
- ✅ Advanced request matching (JSONPath/XPath)
- ✅ Fault injection (4 types)
- ✅ TCP proxying
- ✅ Stateful scenarios
- ✅ Recording mode
- ✅ Template engine with variables
- ✅ Request storage (1000 max)
- ✅ Metrics collection

### Frontend — historical feature list
- ✅ Request journal with filtering
- ✅ Pagination (50 per page)
- ✅ Export (JSON/CSV)
- ✅ Mapping CRUD with bulk operations
- ✅ Priority conflict detection
- ✅ Visual diff viewer
- ✅ Recording controls
- ✅ Scenario management
- ✅ Verification framework
- ✅ Test console
- ✅ Global settings
- ✅ Keyboard shortcuts (8)
- ✅ Undo/redo (50 actions)
- ✅ Import/export with drag-drop
- ✅ Template library (9 templates)
- ✅ Real-time validation
- ✅ Favorites system
- ✅ Toast notifications
- ✅ Dark mode

## 🔧 Technical Stack

### Backend
- **Framework**: Spring Boot 3.2.0
- **TCP Server**: Netty 4.1.100
- **Metrics**: Micrometer
- **Logging**: SLF4J + Logback
- **Build**: Maven 3.9+
- **Java**: 17+

### Frontend
- **UI Framework**: Tailwind CSS 3.x
- **Icons**: Font Awesome 6.4
- **Architecture**: Modular JavaScript (ES6)
- **Storage**: localStorage for settings
- **No Dependencies**: Pure JavaScript, no npm/webpack

## 📁 Project Structure

```
tcp-mock-server/
├── src/main/java/io/pockethive/tcpmock/
│   ├── controller/          # REST controllers
│   ├── handler/             # TCP request handlers
│   ├── model/               # Data models
│   ├── service/             # Business logic
│   └── util/                # Utilities
├── src/main/resources/
│   ├── static/              # UI files
│   │   ├── index-complete.html  # Production UI
│   │   ├── app-ultimate.js      # Main controller
│   │   └── *.js                 # 14 modules
│   └── application.yml      # Configuration
├── mappings/                # Example mappings
├── docs/                    # Documentation
└── pom.xml                  # Maven config
```

## 🚀 Deployment Steps

### 1. Build
```bash
mvn clean package
```

### 2. Run Locally
```bash
java -jar target/tcp-mock-server-1.0.0.jar
```

### 3. Docker
```bash
docker build -t tcp-mock-server .
docker run -p 8080:8080 tcp-mock-server
```

### 4. Kubernetes
```bash
kubectl apply -f k8s/deployment.yaml
```

## 🧪 Testing

### Unit Tests
```bash
mvn test
```

### Integration Tests
```bash
mvn verify
```

### Manual Testing
1. Start server
2. Open http://localhost:8080
3. Send test: `echo "ECHO test" | nc localhost 8080`
4. Verify in UI Requests tab

## Performance qualification

Throughput, latency, memory use, request-history capacity and concurrent
connections are unqualified. Earlier numerical claims had no supporting
measurement attached. Use [TCP capability qualification](WIREMOCK-PARITY.md)
for the required evidence.

## 🔒 Security Considerations

### Current State
- ❌ No authentication (internal use)
- ✅ Input validation on all endpoints
- ✅ CORS configurable
- ✅ No SQL injection risk (no database)
- ✅ XSS protection in UI

### Production Recommendations
1. Add reverse proxy with authentication
2. Enable HTTPS/TLS
3. Configure rate limiting
4. Set up monitoring/alerting
5. Regular security audits

## Known gaps

See [TCP capability qualification](WIREMOCK-PARITY.md). The historical feature
lists above do not establish that those workflows work on the TCP listener.

## 📞 Support Contacts

### Development Team
- **Architecture**: Senior Software Architect
- **UX**: Senior UX Developer
- **Backend**: Java/Spring Boot team
- **Frontend**: JavaScript team

### Escalation
1. Check logs: `logs/tcp-mock-server.log`
2. Review metrics: `http://localhost:8080/actuator/metrics`
3. Check health: `http://localhost:8080/actuator/health`
4. Contact development team

## 📚 Training Materials

### For Developers
- Review `src/main/java/io/pockethive/tcpmock/` for backend
- Review `src/main/resources/static/` for frontend
- Read `WIREMOCK-PARITY.md` for features
- Check example mappings in `mappings/`

### For QA/Testers
- Read `QUICK-REFERENCE.md` for API usage
- Review `SCENARIO-SETUP.md` for test scenarios
- Use UI for manual testing
- Check `DEPLOYMENT-CHECKLIST.md` for validation

### For Operations
- Read `DEPLOYMENT-CHECKLIST.md`
- Review Docker/K8s configs
- Set up monitoring dashboards
- Configure alerting rules

## Acceptance

[TCP capability qualification](WIREMOCK-PARITY.md) owns the current acceptance
requirements and gaps. This document does not record completed qualification,
test coverage, performance validation or security sign-off.

## 🎓 Knowledge Transfer

### Sessions Completed
1. ✅ Architecture overview
2. ✅ Backend deep dive
3. ✅ Frontend architecture
4. ✅ Deployment walkthrough
5. ✅ Troubleshooting guide

### Artifacts Provided
- ✅ Source code with comments
- ✅ Architecture diagrams
- ✅ API documentation
- ✅ Deployment guides
- ✅ Example configurations
- ✅ Test scenarios

## Sign-off

Qualification is incomplete. No runtime tests or performance measurements were
run for this documentation update. Record actual evidence against
[TCP capability qualification](WIREMOCK-PARITY.md) before issuing a new sign-off.
