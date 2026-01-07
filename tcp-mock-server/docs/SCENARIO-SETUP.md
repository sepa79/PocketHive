# TCP Scenario Setup Guide

## Current Setup (Zero Configuration Required)

### What's Provided Globally

1. **SUT Environments** (`scenario-manager-service/sut/sut-environments.yaml`)
   - `tcp-mock-local` with endpoints: `tcp-server`, `tcps-server`
   - No per-scenario SUT files needed

2. **TCP Mock Server Mappings** (`tcp-mock-server/mappings/*.json`)
   - Pre-configured patterns for common protocols
   - Auto-loaded on server startup
   - Covers: ECHO, JSON, STX/ETX, ISO-8583, secure messages

3. **Default Delimiters**
   - Newline (`\n`) for most protocols
   - Empty string for binary/framed protocols
   - Configurable per mapping

### What Scenarios Must Provide

1. **Templates** (`templates/<serviceId>/<callId>.yaml`)
   ```yaml
   serviceId: default
   callId: tcp-echo
   protocol: TCP
   behavior: ECHO
   transport: socket
   endTag: "\n"              # Optional: override delimiter
   bodyTemplate: "..."
   headersTemplate: {}
   ```

2. **SUT Selection** (in scenario deployment, NOT in scenario bundle)
   - Select `tcp-mock-local` when deploying
   - Uses central SUT configuration

## Future Scenarios: Zero Setup Required

### For Standard Protocols (ECHO, JSON, STX/ETX, ISO-8583)
✅ **No setup needed** - Mappings already exist

### For Custom Protocols
Add ONE mapping file to `tcp-mock-server/mappings/`:

```json
{
  "id": "my-custom-protocol",
  "requestPattern": "^CUSTOM:.*",
  "responseTemplate": "RESPONSE:{{data}}",
  "responseDelimiter": "\n",
  "priority": 15,
  "enabled": true
}
```

That's it. No per-scenario configuration.

## Anti-Patterns (DO NOT DO)

❌ **Per-scenario `sut.yaml`** - Use central `sut-environments.yaml`
❌ **Hardcoded endpoints in scenarios** - Use `{{ sut.endpoints['tcp-server'].baseUrl }}`
❌ **Custom mock server per scenario** - Use shared `tcp-mock-server`

## Migration: Remove Existing sut.yaml Files

```bash
# Remove from all TCP scenarios
find scenarios/bundles/tcp-* -name "sut.yaml" -delete
```

All scenarios will use central `tcp-mock-local` SUT environment.
