
# Test Plan (MVP)

## Unit
- Block validators (per operation, boundary cases).
- Non-overlap scheduling check.
- JSON serialization matches exact schema (no extras).

## Integration
- Save/Load via Scenario Manager (create, update, fetch).
- Apply flow happy path (mock Orchestrator endpoint).

## E2E (Cypress/Playwright)
- Create scenario with 2 tracks, save, export, apply stub.
- Error: overlap blocks -> save disabled with message.
