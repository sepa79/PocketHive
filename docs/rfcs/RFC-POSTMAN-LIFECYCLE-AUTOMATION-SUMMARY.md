# RFC: Postman Script Execution & Swarm Lifecycle Automation

**Status**: Proposed  
**Target Release**: PocketHive 0.17.x (experimental)  
**Estimated Effort**: 7-9 days  
**Risk Level**: Low

---

## 📋 Executive Summary

Automate swarm setup/teardown by executing Postman collections and PocketHive templates during lifecycle events. This eliminates 15-20 minutes of manual work per test run and prevents "forgot to seed data" failures.

**Value Proposition**: Save 12 minutes per test × 50 tests/week = **10 hours/week** saved across QA team.

---

## 🎯 Problem & Solution

### Current State (Manual)
```mermaid
sequenceDiagram
    participant QA as QA Engineer
    participant CLI as Command Line
    participant SUT as System Under Test
    participant UI as PocketHive UI
    
    QA->>CLI: curl POST /accounts (5 min)
    CLI->>SUT: Create 100 accounts
    QA->>CLI: curl POST /wiremock (5 min)
    CLI->>SUT: Configure mocks
    QA->>CLI: curl GET /health (2 min)
    CLI->>SUT: Verify readiness
    QA->>UI: Start swarm (1 min)
    Note over QA: Total: 13 minutes
```

### Proposed State (Automated)
```mermaid
sequenceDiagram
    participant QA as QA Engineer
    participant UI as PocketHive UI
    participant SC as Swarm Controller
    participant SUT as System Under Test
    
    QA->>UI: Start swarm (1 min)
    UI->>SC: SwarmStartEvent
    SC->>SC: Execute setup scripts
    SC->>SUT: Run Postman collections
    SC->>SUT: Run PH templates
    SC->>SC: Launch workers
    SC->>UI: Swarm Ready
    Note over QA: Total: 1 minute
```

---

## 🏗️ Architecture

### Module Dependencies
```mermaid
graph TD
    subgraph "Control Plane"
        SC[swarm-controller-service]
        MSDK[common/manager-sdk]
    end
    
    subgraph "Data Plane"
        PROC[processor-service]
        WSDK[common/worker-sdk]
    end
    
    subgraph "Shared Libraries"
        PC[common/protocol-client<br/>NEW MODULE]
        SM[common/swarm-model]
    end
    
    SC --> MSDK
    SC --> PC
    SC --> SM
    
    PROC --> WSDK
    PROC -.keeps own copy.-> PC_COPY[HTTP/TCP Client Code]
    
    WSDK --> SM
    
    PC -.copied from.-> PC_COPY
    
    style PC fill:#90EE90
    style PC_COPY fill:#FFB6C1
    style SC fill:#87CEEB
    style PROC fill:#87CEEB
```

**Key Decision**: Create `common/protocol-client` by **copying** HTTP/TCP code from processor-service. Processor-service remains unchanged (zero risk).

### Lifecycle Flow
```mermaid
stateDiagram-v2
    [*] --> SwarmStartRequested
    SwarmStartRequested --> ExecutingSetup: Run setup scripts
    ExecutingSetup --> SetupFailed: Script fails
    ExecutingSetup --> LaunchingWorkers: All scripts pass
    SetupFailed --> [*]: Block swarm start
    LaunchingWorkers --> SwarmRunning
    SwarmRunning --> SwarmStopRequested
    SwarmStopRequested --> ExecutingTeardown: Run teardown scripts
    ExecutingTeardown --> SwarmStopped: Log failures, continue
    SwarmStopped --> [*]
```

---

## 📝 Configuration

### Scenario Configuration
```yaml
id: example-load-test
name: Example Load Test

template:
  image: swarm-controller:latest
  
  lifecycle:
    scriptTimeoutSeconds: 600
    failOnSetupError: true
    environment:
      apiKey: ${EXAMPLE_API_KEY}
      accountCount: 1000
      environment: staging
  
  bees:
    - role: generator
      # ... worker config
```

### Script Structure
```
my-scenario/
  setup-scripts/
    00-health-check.postman.json      # Verify SUT ready
    01-create-accounts.postman.json   # Seed test data
    02-configure-mocks.template.yaml  # Setup WireMock
    setup-env.yaml                    # Variables
  teardown-scripts/
    99-cleanup.postman.json           # Remove test data
```

---

## ✅ Acceptance Criteria (Summary)

### Functional
- ✅ Execute Postman collections via Newman CLI
- ✅ Execute PH templates via shared protocol-client (HTTP + TCP)
- ✅ Auto-detect script type (`.postman.json`, `.template.yaml`)
- ✅ Execute scripts in alphabetical order
- ✅ Block swarm start on setup failure
- ✅ Log teardown failures but continue

### Non-Functional
- ✅ < 5 seconds overhead for empty folder
- ✅ Structured logs + Micrometer metrics
- ✅ SSL verification enabled by default
- ✅ 88% code coverage

---

## 🧪 Testing Strategy

```mermaid
pie title Test Distribution
    "Unit Tests" : 75
    "Integration Tests" : 20
    "E2E Tests" : 5
```

**Coverage Goals**:
- ScriptTypeDetector: 100%
- SetupScriptExecutor: 95%
- NewmanRunner: 90%
- PhTemplateExecutor: 90%
- SwarmLifecycleManager: 85%

---

## 📅 Implementation Plan

```mermaid
gantt
    title 9-Day Implementation Timeline
    dateFormat  YYYY-MM-DD
    section Phase 1
    Create protocol-client module    :2024-01-01, 1d
    Script detection + Newman setup   :2024-01-02, 1d
    section Phase 2
    Execution engine                  :2024-01-03, 2d
    section Phase 3
    Lifecycle integration             :2024-01-05, 2d
    section Phase 4
    Documentation + E2E               :2024-01-07, 2d
    section Phase 5
    Polish + Release                  :2024-01-09, 1d
```

### Phase Breakdown
1. **Days 1-2**: Foundation (protocol-client, script detection, Newman)
2. **Days 3-4**: Execution engine (NewmanRunner, PhTemplateExecutor)
3. **Days 5-6**: Lifecycle integration (event listeners, tests)
4. **Days 7-8**: Documentation + E2E tests
5. **Day 9**: Polish + release prep

---

## ⚠️ Risk Assessment

### High Priority Mitigations
| Risk | Mitigation |
|------|------------|
| Newman not available | Fail-fast on startup + health check |
| Secrets in logs | Sanitize patterns (apiKey, password, token) |
| Script folders not mounted | Validate on startup, clear error message |

### Medium Priority
| Risk | Mitigation |
|------|------------|
| Setup takes too long | Document timeout expectations, recommend parallel requests |
| Teams misuse for data-plane | Clear docs: "Lifecycle only, not workload processing" |

---

## 🎯 Success Metrics (3 months post-release)

```mermaid
graph LR
    A[Adoption] --> B[50% scenarios use scripts]
    A --> C[80% QA created scripts]
    D[Performance] --> E[95% complete < 30s]
    D --> F[99.9% success rate]
    G[Business] --> H[12 min saved per run]
    G --> I[20% more test frequency]
```

---

## 🔄 Alternatives Considered

| Option | Verdict | Reason |
|--------|---------|--------|
| Newman + PH Templates | ✅ **Selected** | Best balance: low effort, high value, familiar tools |
| Separate Worker Service | ❌ Rejected | Violates separation of concerns |
| Native Java Parser | ❌ Rejected | 5-7 days extra dev, reinventing wheel |
| Custom DSL | ❌ Rejected | Months of dev, team retraining |
| Bash Scripts | ❌ Rejected | No validation, platform-dependent |
| Ansible | ❌ Rejected | Too heavy, steep learning curve |

---

## 📚 Quick Reference

### Example: Postman Collection
```json
{
  "info": { "name": "Create Test Accounts" },
  "item": [{
    "name": "Create Account",
    "request": {
      "method": "POST",
      "url": "{{baseUrl}}/accounts",
      "body": {
        "raw": "{\"id\": \"{{accountId}}\", \"balance\": 1000}"
      }
    }
  }]
}
```

### Example: PH Template
```yaml
serviceId: default
callId: ConfigureWireMock
method: POST
pathTemplate: /__admin/mappings
bodyTemplate: |
  {
    "request": {"method": "POST", "urlPath": "/payments"},
    "response": {"status": 200, "body": "{\"status\":\"approved\"}"}
  }
```

### Example: Environment Template
```yaml
# SUT endpoints (from SwarmPlan)
baseUrl: "{{ sut.endpoints['default'].baseUrl }}"

# Lifecycle environment (Postman-style)
apiKey: "{{ pm.environment.apiKey }}"
accountCount: "{{ pm.environment.accountCount }}"

# Swarm context
swarmId: "{{ swarmId }}"
timestamp: "{{ eval('#nowIso') }}"
```

---

## 🚀 Recommendation

✅ **Approve for implementation in PocketHive 0.17.x (experimental)**

**Rationale**:
- Low risk (no processor-service changes, battle-tested Newman)
- High value (12 min saved per test run)
- Quick delivery (7-9 days)
- Familiar tools (teams already use Postman)
- Clean architecture (lifecycle in controller, not data plane)

---

**For complete technical details, see the full RFC document.**
