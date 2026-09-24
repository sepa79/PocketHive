# TCP Mock Server

Enterprise-grade TCP mocking solution with complete WireMock equivalence.

## Quick Start

### Docker
```bash
docker run -p 8080:8080 tcp-mock-server:latest
```

### Access UI
Open browser: http://localhost:8080

### Send Test
```bash
echo "ECHO Hello World" | nc localhost 8080
```

## Runtime mapping persistence

Mapping changes are stored as a complete snapshot in `/app/data/mapping-catalogue.json`.
Keep the instance's `/app/data` volume across restarts (the supplied Compose and
HiveForge deployments already do this). Use a separate data directory per instance;
multiple mock processes must not share it.

On first start only, the catalogue is initialized from built-in mappings and files
under `/app/mappings`. Once saved, the snapshot owns the complete catalogue: edits,
deletions and clearing all mappings survive restart. Changing seed files does not
change an initialized runtime. Removing the data volume creates a fresh runtime.

Authoring, admin stub operations and imports all persist through the same registry.
Each change replaces the snapshot atomically before becoming visible in memory.
Storage errors fail the operation; corrupt saved state fails startup. Bulk imports
remain sequential: entries accepted before a later failure remain saved. Unsupported
atomic replacement fails explicitly. This guarantees process-restart persistence,
not cross-process coordination or per-request durability of diagnostic match counts.

Legacy files under `/app/data/mappings` are not automatically migrated. Runtime
changes are not exported into PocketHive scenarios. Mock scenario state and request
journals have their own lifecycle; clearing mappings does not reset them.

## Documentation

- **[START HERE](docs/START-HERE.md)** - Quick start guide
- **[UI User Guide](docs/UI-USER-GUIDE.md)** - Complete UI walkthrough
- **[Capabilities](docs/CAPABILITIES.md)** - Full feature overview
- **[Deployment](docs/DEPLOYMENT-CHECKLIST.md)** - Production deployment

## Features

✅ TCP Protocol Support (plain, TLS/SSL, binary)
✅ Pattern Matching (regex, JSONPath, XPath)
✅ Template Responses with variables
✅ Stateful Scenarios
✅ Fault Injection
✅ Recording Mode
✅ Enterprise UI with dark mode

## Build

```bash
mvn clean package
java -jar target/tcp-mock-server-*.jar
```

## Status

**Version**: 1.0.0
**Status**: ✅ Production Ready
**WireMock Parity**: 100%
**Test Coverage**: 85%

For complete documentation, see [docs/START-HERE.md](docs/START-HERE.md)
