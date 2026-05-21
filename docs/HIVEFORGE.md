# HiveForge Deployment

PocketHive declares a HiveForge POC manifest at the repository root:
`hiveforge.yaml`.

The current HiveForge implementation is repository/ref-driven and runs declared
component actions through Ansible. PocketHive's target HiveForge model should be
release-driven, with registry-qualified image artifacts and no image build in
the deploy path. HiveForge documents that target contract, but it is not the
implemented path yet.

## Current Supported Profile

- `single-full` - deploys the existing local Docker Compose stack on a single
  Docker host by running `./build-hive.sh --quick`.
- `swarm-reduced` - prepares the Docker Swarm runtime with runtime config and
  state under the shared HiveForge project root.
- `swarm-full` - prepares the Docker Swarm runtime with Postgres and ClickHouse
  data under explicit service-owned roots and placement labels; the remaining
  runtime config and state stays under the shared HiveForge project root.

The `single-full` profile intentionally maps to the existing canonical local
PocketHive entrypoint instead of inventing a second compose orchestration path.
The Ansible playbooks fail unless HiveForge passes `HIVEFORGE_PROFILE=single-full`.

## Component

- `stack` - the whole local PocketHive stack.

Actions:

- `deploy` - runs `./build-hive.sh --quick`
- `update` - runs `./build-hive.sh --quick`
- `remove` - runs `./build-hive.sh --clean`

## Example HiveForge Registry

HiveForge keeps project registry and environment policy outside the project
repository. For a local development registration, use a registry entry shaped
like:

```yaml
projects:
  - id: pockethive
    name: PocketHive
    source: github
    repository: https://github.com/sepa79/PocketHive.git
    approvedRefs:
      - main
```

The matching local environment policy must explicitly allow the project,
profile, and action:

```yaml
current: local
environments:
  - id: local
    name: Local Docker
    kind: local-docker
    capabilities:
      runtime:
        - docker-single
      managedRoot:
        shared: false
        nodes:
          - local-docker
    policy:
      projects:
        - id: pockethive
          profiles:
            - single-full
          actions:
            - deploy
            - remove
            - update
```

## Known Gap

PocketHive now declares explicit release/image-tag runtime artifacts and managed
runtime files for HiveForge. The remaining gap is on the HiveForge action side:
executing the prepared release stack for each runtime profile is still evolving
outside this repository.
