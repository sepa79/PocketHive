# DB Query Test Postgres

This helper starts a Postgres container for `db-query-postgres-smoke`.
It expects the PocketHive stack network `pockethive_default` to exist and be
attachable. `up.sh` starts the container and reapplies the seed SQL every time.

```bash
./up.sh
./reset.sh
./down.sh
```

The scenario profiles use:

```text
jdbc:postgresql://dbquery-test-postgres:5432/dbquery
```

Seeded rows:

```text
PH-DB-SMOKE
PH-DB-UPDATE
```
