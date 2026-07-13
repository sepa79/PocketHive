# TCP Mock Server — Start Here

This directory is the single source for TCP Mock Server documentation. Maven packages these files into `docs/` on the runtime classpath, and Docker also copies them to `/app/docs`.

## Choose a starting point

- Developers: [README.md](README.md) and [CAPABILITIES.md](CAPABILITIES.md)
- Operators: [DEPLOYMENT-CHECKLIST.md](DEPLOYMENT-CHECKLIST.md)
- Scenario authors: [SCENARIO-SETUP.md](SCENARIO-SETUP.md)
- WireMock migration: [MIGRATION-GUIDE.md](MIGRATION-GUIDE.md) and [WIREMOCK-PARITY.md](WIREMOCK-PARITY.md)
- UI users: [UI-USER-GUIDE.md](UI-USER-GUIDE.md)
- Short command/API reference: [QUICK-REFERENCE.md](QUICK-REFERENCE.md)
- Full documentation map: [DOCUMENTATION-INDEX-FINAL.md](DOCUMENTATION-INDEX-FINAL.md)

## Local verification

Build and test the module from the repository root:

```bash
./mvnw -pl tcp-mock-server -am test
```

For supported PocketHive runtime commands and ingress paths, use `docs/USAGE.md` from the repository root.

## Source-of-truth rule

Edit Markdown only in `tcp-mock-server/docs/`. Do not create maintained copies under `src/main/resources`; the build packages the canonical files automatically.
