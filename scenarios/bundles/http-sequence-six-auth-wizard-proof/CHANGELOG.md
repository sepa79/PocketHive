# Changelog

## 2026-05-15 - Wizard generated

- Created bundle http-sequence-six-auth-wizard-proof from wizard intent.
- Pattern: sequence.
- Auth: oauth2_client_credentials.
- Data source: SCHEDULER.

### Historical retained evidence — not current-candidate qualification

The retained JSON has no source-tree identity, uses proof-only runtime patches,
and ends with the swarm in `STOPPED` state rather than Remove plus verified
absence. It supports only the token, six-request, and unmatched-request
assertions below; it does not qualify the current customer candidate or prove
cleanup.

- MCP wizard proof run `mcp-wizard-proof-1778868788` passed.
- Evidence file: `evidence/mcp-wizard-proof-1778868788.json`.
- Result: 1 OAuth token request, 6 authenticated business requests, 0 unmatched `/api/wizard-proof/*` requests.
- Note: the first runtime attempt failed explicitly because `WIZARD_PROOF_CLIENT_SECRET` was not present; the passing proof injected a local dummy value into the deployed Scenario Manager copy via MCP before rerunning.
