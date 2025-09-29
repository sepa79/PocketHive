
# Test Plan

## Contract tests
- Schema rejects extra/missing fields.
- Blocks overlap detection.

## API tests
- CRUD, pagination, sorting, filters.
- Apply by id vs inline.

## Integration
- Manager->Orchestrator handoff (mock Orchestrator).
- SSE stream formatting and reconnection.
