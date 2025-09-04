# PocketHive MVP Roadmap
## From Current State to JMeter-like Load Testing Framework

### Current State (v0.8.0)
- Single swarm with basic REST support
- Manual configuration via UI
- WireMock integration for testing
- Basic metrics and observability

### MVP Target
- Multiple dynamic swarms (REST + SOAP)
- External scenario configuration
- Redis data source integration
- Production-ready load testing framework

---

## Architecture Overview

### Current Architecture
```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  Generator  │───▶│  Moderator  │───▶│  Processor  │───▶│Postprocessor│
└─────────────┘    └─────────────┘    └─────────────┘    └─────────────┘
       │                   │                   │                   │
       └───────────────────┼───────────────────┼───────────────────┘
                           │                   │
                    ┌─────────────┐    ┌─────────────┐
                    │ RabbitMQ    │    │   WireMock  │
                    │ (Queues)    │    │    (SUT)    │
                    └─────────────┘    └─────────────┘
```

### Target MVP Architecture
```
                    ┌─────────────────────────────────────────────────┐
                    │                 QueenBee                        │
                    │           (Swarm Orchestrator)                  │
                    └─────────────────┬───────────────────────────────┘
                                      │
        ┌─────────────────────────────┼─────────────────────────────────┐
        │                             │                                 │
┌───────▼────────┐            ┌───────▼────────┐            ┌───────▼────────┐
│  REST Swarm 1  │            │  REST Swarm 2  │            │  SOAP Swarm 1  │
│ ┌─────┐ ┌─────┐│            │ ┌─────┐ ┌─────┐│            │ ┌─────┐ ┌─────┐│
│ │ Gen │▶│Proc ││            │ │ Gen │▶│Proc ││            │ │ Gen │▶│Proc ││
│ └─────┘ └─────┘│            │ └─────┘ └─────┘│            │ └─────┘ └─────┘│
└────────────────┘            └────────────────┘            └────────────────┘
        │                             │                             │
        └─────────────────────────────┼─────────────────────────────┘
                                      │
        ┌─────────────────────────────▼─────────────────────────────────┐
        │                    Shared Infrastructure                      │
        │  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────────────┐  │
        │  │RabbitMQ │  │  Redis  │  │Scenario │  │   Production    │  │
        │  │(Queues) │  │ (Data)  │  │Manager  │  │    Systems      │  │
        │  └─────────┘  └─────────┘  └─────────┘  └─────────────────┘  │
        └───────────────────────────────────────────────────────────────┘
```

---

## Implementation Phases

### Phase 1: QueenBee Foundation (Week 1-2)
**Goal**: Enable dynamic swarm creation and management

#### 1.1 QueenBee Service Creation
- [ ] Create `queenbee-service` module
- [ ] Add Docker API client dependency
- [ ] Implement basic container lifecycle management
- [ ] Add swarm registry and status tracking

#### 1.2 Control Plane Extensions
- [ ] Add swarm-specific routing keys: `sig.swarm-create.<swarmId>`
- [ ] Extend status events with `swarmId` field
- [ ] Update observability module with swarm context

#### 1.3 Docker Integration
- [ ] Add QueenBee to docker-compose.yml
- [ ] Mount Docker socket for container management
- [ ] Create swarm template configurations

**Deliverable**: Single REST swarm can be created dynamically via QueenBee

### Phase 2: Multi-Swarm Support (Week 3-4)
**Goal**: Run multiple isolated swarms simultaneously

#### 2.1 Queue Isolation
- [ ] Implement swarm-specific queue naming: `ph.gen.<swarmId>`
- [ ] Update all services to use swarm-aware queue bindings
- [ ] Add queue cleanup on swarm destruction

#### 2.2 Service Modifications
- [ ] Add `PH_SWARM_ID` environment variable to all services
- [ ] Update bee name generation to include swarm ID
- [ ] Modify metrics to include swarm labels

#### 2.3 UI Enhancements
- [ ] Add "Create Swarm" button and form
- [ ] Display swarms as grouped components
- [ ] Add swarm-level start/stop controls

**Deliverable**: Multiple REST swarms running independently

### Phase 3: External Configuration (Week 5-6)
**Goal**: Load test scenarios from external sources

#### 3.1 Scenario Manager Service
- [ ] Create `scenario-manager-service` module
- [ ] Implement REST API for scenario CRUD operations
- [ ] Add scenario validation and templating

#### 3.2 Scenario Schema Design
```json
{
  "scenarioId": "payment-load-test",
  "swarmType": "REST",
  "duration": 3600,
  "baseUrl": "https://api.production.com",
  "requests": [
    {
      "name": "login",
      "method": "POST",
      "path": "/auth/login",
      "headers": {"Content-Type": "application/json"},
      "body": {"username": "${redis:usernames}", "password": "test123"}
    }
  ],
  "loadPattern": {
    "type": "ramp-up",
    "startTPS": 0,
    "targetTPS": 100,
    "duration": 300
  }
}
```

#### 3.3 Generator Enhancements
- [ ] Add scenario loading from external API
- [ ] Implement request templating with variables
- [ ] Add support for request sequences and dependencies

**Deliverable**: Scenarios loaded from external configuration

### Phase 4: Data Sources Integration (Week 7-8)
**Goal**: Use Redis for dynamic test data

#### 4.1 Redis Integration
- [ ] Add Redis to docker-compose.yml
- [ ] Create Redis client in observability module
- [ ] Implement data source abstraction layer

#### 4.2 Template Engine
- [ ] Add variable substitution: `${redis:account_numbers}`
- [ ] Support for random selection from Redis lists/sets
- [ ] Implement data cycling and distribution strategies

#### 4.3 Data Management
- [ ] Create Redis data loading utilities
- [ ] Add data validation and error handling
- [ ] Implement data refresh mechanisms

**Deliverable**: Generators use Redis data for realistic test scenarios

### Phase 5: SOAP Support (Week 9-10)
**Goal**: Support SOAP web services testing

#### 5.1 SOAP Generator
- [ ] Create SOAP-specific request templates
- [ ] Add XML envelope generation
- [ ] Implement SOAP action header handling

#### 5.2 SOAP Processor
- [ ] Add XML request/response parsing
- [ ] Implement SOAP fault handling
- [ ] Add WSDL-based validation (optional)

#### 5.3 SOAP Swarm Templates
- [ ] Create SOAP swarm configuration templates
- [ ] Add SOAP-specific metrics and monitoring
- [ ] Test with real SOAP services

**Deliverable**: SOAP swarms running alongside REST swarms

### Phase 6: Scenario Editor UI (Week 11-12)
**Goal**: Visual scenario creation and editing

#### 6.1 Timeline Editor Component
```
Timeline View:
┌─────────────────────────────────────────────────────────────────┐
│ REST API Load Test                                    [Save] [Run]│
├─────────────────────────────────────────────────────────────────┤
│ 0min    10min    20min    30min    40min    50min    60min      │
│ ├────────┼────────┼────────┼────────┼────────┼────────┼────────┤ │
│ │        │        │        │        │        │        │        │ │
│ │ ┌──────────────────────┐                                     │ │ REST
│ │ │   Ramp Up 0→100 TPS  │                                     │ │ Swarm 1
│ │ └──────────────────────┘                                     │ │
│ │                        ┌─────────────────┐                   │ │
│ │                        │ Steady 100 TPS  │                   │ │
│ │                        └─────────────────┘                   │ │
│ ├────────┼────────┼────────┼────────┼────────┼────────┼────────┤ │
│ │                                                              │ │ SOAP
│ │     ┌─────────────────────────────────────────────────────┐  │ │ Swarm 1
│ │     │              Steady 50 TPS                          │  │ │
│ │     └─────────────────────────────────────────────────────┘  │ │
└─────────────────────────────────────────────────────────────────┘
```

#### 6.2 Scenario Builder
- [ ] Drag-and-drop load pattern blocks
- [ ] Request template editor with syntax highlighting
- [ ] Data source configuration UI
- [ ] Real-time scenario validation

#### 6.3 Execution Dashboard
- [ ] Live scenario progress tracking
- [ ] Multi-swarm metrics visualization
- [ ] Error rate and response time monitoring
- [ ] Scenario pause/resume/stop controls

**Deliverable**: Complete visual scenario editor

---

## Technical Implementation Details

### New Services Architecture

```
queenbee-service/
├── src/main/java/io/pockethive/queenbee/
│   ├── QueenBeeApplication.java
│   ├── controller/
│   │   └── SwarmController.java
│   ├── service/
│   │   ├── SwarmOrchestrator.java
│   │   ├── DockerService.java
│   │   └── SwarmRegistry.java
│   └── model/
│       ├── SwarmTemplate.java
│       └── SwarmStatus.java
├── Dockerfile
└── pom.xml

scenario-manager-service/
├── src/main/java/io/pockethive/scenario/
│   ├── ScenarioManagerApplication.java
│   ├── controller/
│   │   └── ScenarioController.java
│   ├── service/
│   │   ├── ScenarioService.java
│   │   └── TemplateEngine.java
│   └── model/
│       ├── Scenario.java
│       └── LoadPattern.java
├── Dockerfile
└── pom.xml
```

### Docker Compose Extensions

```yaml
# Additional services for MVP
queenbee:
  build: ./queenbee-service
  volumes:
    - /var/run/docker.sock:/var/run/docker.sock
  environment:
    - RABBITMQ_HOST=rabbitmq
    - SCENARIO_MANAGER_URL=http://scenario-manager:8080

scenario-manager:
  build: ./scenario-manager-service
  ports:
    - "8090:8080"
  environment:
    - RABBITMQ_HOST=rabbitmq
    - REDIS_URL=redis://redis:6379

redis:
  image: redis:7-alpine
  ports:
    - "6379:6379"
  volumes:
    - redis_data:/data

volumes:
  redis_data:
```

### UI Component Structure

```
ui/src/pages/
├── scenarios/
│   ├── ScenarioEditor.tsx      # Main timeline editor
│   ├── ScenarioList.tsx        # Scenario management
│   ├── LoadPatternLibrary.tsx  # Reusable patterns
│   └── RequestEditor.tsx       # HTTP/SOAP request builder
├── swarms/
│   ├── SwarmDashboard.tsx      # Multi-swarm overview
│   ├── SwarmCreator.tsx        # New swarm wizard
│   └── SwarmMetrics.tsx        # Per-swarm analytics
└── execution/
    ├── ExecutionDashboard.tsx  # Live test monitoring
    └── ResultsAnalysis.tsx     # Post-test analysis
```

---

## Success Criteria

### Phase 1 Success
- [ ] QueenBee can create/destroy single REST swarm
- [ ] Swarm appears in UI with unique identifier
- [ ] Basic swarm lifecycle management working

### Phase 2 Success
- [ ] 3+ REST swarms running simultaneously
- [ ] Each swarm isolated (separate queues/metrics)
- [ ] UI shows all swarms with individual controls

### Phase 3 Success
- [ ] Scenarios loaded from external JSON/YAML files
- [ ] Generator executes multi-step request sequences
- [ ] Scenario validation and error handling

### Phase 4 Success
- [ ] Redis integration providing dynamic test data
- [ ] Template variables resolved from Redis
- [ ] Data distribution across multiple generators

### Phase 5 Success
- [ ] SOAP swarms running alongside REST swarms
- [ ] XML request/response handling
- [ ] SOAP-specific error handling and metrics

### Phase 6 Success
- [ ] Visual scenario editor fully functional
- [ ] Timeline-based load pattern design
- [ ] Real-time execution monitoring dashboard

### MVP Complete
- [ ] Production load testing against real systems
- [ ] Multiple protocol support (REST + SOAP)
- [ ] External scenario configuration
- [ ] Redis data source integration
- [ ] Visual scenario editor
- [ ] Multi-swarm orchestration
- [ ] Comprehensive metrics and monitoring

---

## Risk Mitigation

### Technical Risks
- **Docker API complexity**: Start with basic container operations, expand gradually
- **Queue isolation**: Test thoroughly with multiple swarms to prevent cross-talk
- **Resource management**: Implement swarm limits and cleanup procedures

### Timeline Risks
- **Scope creep**: Focus on MVP features only, defer advanced capabilities
- **Integration complexity**: Test each phase thoroughly before proceeding
- **UI complexity**: Use existing component patterns, avoid custom implementations

### Operational Risks
- **Production testing**: Start with staging environments, gradual rollout
- **Data management**: Implement Redis backup and recovery procedures
- **Monitoring**: Ensure comprehensive observability before production use

---

## Next Steps

1. **Immediate (This Week)**
   - Create QueenBee service skeleton
   - Set up development environment for new services
   - Begin Phase 1 implementation

2. **Short Term (Next 2 Weeks)**
   - Complete QueenBee basic functionality
   - Test dynamic swarm creation
   - Begin multi-swarm support

3. **Medium Term (Next Month)**
   - External scenario configuration
   - Redis integration
   - SOAP support foundation

4. **Long Term (Next 2 Months)**
   - Complete scenario editor
   - Production testing
   - MVP delivery

The roadmap transforms PocketHive from a single-swarm testing tool into a comprehensive, JMeter-like load testing framework capable of handling complex, multi-protocol production scenarios.