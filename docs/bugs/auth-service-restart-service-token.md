# Orchestrator service token goes stale after auth-service restart

## Summary
If `auth-service` is restarted while `orchestrator-service` remains up, PocketHive create flows can fail with `500 Internal Server Error`.

## Symptom
`POST /orchestrator/api/swarms/{id}/create` returns `500`.

## Observed cause
Orchestrator keeps a cached service-principal bearer token for internal calls to `scenario-manager`.
After `auth-service` restart, that token is no longer resolvable, so `ScenarioManagerClient.fetchScenarioTemplate(...)` gets `401`.
The create path then fails with `IllegalStateException: Failed to fetch template metadata ...`.

## Reproduction
1. Start stack with auth always-on.
2. Restart only `auth-service`.
3. Without restarting `orchestrator-service`, call swarm create.
4. Observe `500` and orchestrator log line showing template metadata fetch `401` from `scenario-manager`.
5. Restart `orchestrator-service`; the same request then succeeds again.

## Expected
Orchestrator should refresh or re-login its service token on auth `401`, instead of requiring a process restart.
