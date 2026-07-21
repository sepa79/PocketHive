# Managed Dataset packages

This directory is the source layout for standalone Managed Dataset packages.
Each child directory owns exactly one portable Dataset definition:

```text
scenarios/managed-datasets/<datasetPackageId>/
├── dataset.yaml
├── schema/
│   └── record.yaml
├── contracts/
├── mappings/
├── projections/
├── policies/
├── sources/
└── assets/
```

`dataset.yaml` is the only package entry point. It explicitly references the
artifacts used by that package; tools must not discover behavior by recursively
loading arbitrary files.

The record schema defines this Dataset's complete canonical field set. Each
package-local `DatasetContract/v1` selects one guaranteed field subset for one
use. A package may contain many contracts. Contracts are part of the Dataset
package version and are not shared live objects across Dataset packages.

A package declares its required storage capabilities and explicitly supported
profiles. It does not contain a deployment `datasetSpaceId`, alias, backend
settings or credentials. A deployment-scoped Dataset registration selects one
active Dataset Space, alias, adapter, settings reference and compatible profile.
No adapter is implicit:

```yaml
schemaVersion: pockethive.dataset-package/v1
packageId: reusable-records
version: 1
recordSchemaPath: schema/record.yaml
contractPaths:
  - contracts/traffic.yaml
requiredStorageCapabilities: [SNAPSHOT_READ, SHARED_SELECTION]
supportedStorageProfiles: [MANAGED_RECORDS_V1, REDIS_COLLECTION_V1]
```

For example:

```yaml
# contracts/traffic.yaml
schemaVersion: pockethive.dataset-contract/v1
contractId: traffic
fields: [recordKey, accountId, cardId, expiryDate]
```

A Scenario Binding explicitly selects one contract from the selected Dataset
package. Scenario-specific `requiredFields` and `bindingSlotsRef` remain in the
scenario bundle because consumer needs and HTTP/TCP/template wiring are not
part of the reusable Dataset definition.

The deployment registration is separate from the package:

```yaml
schemaVersion: pockethive.dataset-registration/v1
registrationId: performance-test-reusable-records
datasetSpaceId: performance-test
datasetPackageRef: reusable-records@1
datasetAlias: reusable-records
storage:
  adapter: POSTGRESQL
  settingsRef: datasets-postgres-primary
  capabilityProfile: MANAGED_RECORDS_V1
```

The registration lives only in the target deployment. Updating or retiring a
Dataset Space may block registrations, but it never rewrites this package.

Known adapter profiles are:

- `POSTGRESQL` with the core `MANAGED_RECORDS_V1` lifecycle profile;
- `REDIS` with the deferred, separately qualified `REDIS_COLLECTION_V1`
  profile.

PostgreSQL is recommended, but Dataset registration fails when
`storage.adapter`, `storage.settingsRef` or `storage.capabilityProfile` is
absent or incompatible with the package and Space. Tools never switch adapters
or emulate unsupported capabilities. Until the Redis profile is implemented
and qualified, a Redis registration fails explicitly as capability unavailable.

Packages contain definitions and non-secret assets only. Credentials, live
records, backend connection values and runtime state are forbidden. Authored
paths are normalized package-relative paths and cannot escape the package.

Scenario-specific Dataset requirements do not live here. They live only at:

```text
scenarios/bundles/<scenarioId>/datasets/requirements.yaml
```

A scenario-local `datasets/` directory must not copy a Dataset definition.
Scenario Binding maps each local requirement to one published Dataset version.

Scenario Manager owns the lifecycle `DRAFT -> PUBLISHED -> RETIRED`.
Published versions are immutable. The Operator UI and PocketHive MCP use the
same Scenario Manager application services to validate and manage packages;
agent mutations are governed through HiveGate.

These contracts are proposed and are not implemented in the current runtime.
