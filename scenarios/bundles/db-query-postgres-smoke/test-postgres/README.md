# DB Query Test Postgres

This helper starts a Postgres container for `db-query-postgres-smoke`.
It expects the PocketHive stack network `pockethive_default` to exist and be
attachable. `up.sh` starts the container and reapplies the seed SQL every time.

> **Current candidate stop gate:** do not run these scripts against customer
> candidate `0524165e` or its deployment archive. That stack creates the
> external network `pockethive`, not `pockethive_default`, so this helper cannot
> attach as written. `up.sh` also invokes `reset.sh` and reapplies seed data.
> Treat this directory as an authoring fixture until its Compose network is
> corrected and the helper is requalified.

Future corrected helper only:

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
