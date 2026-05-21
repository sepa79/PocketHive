# HiveForge Deployment

PocketHive declares a HiveForge POC manifest at the repository root:
`hiveforge.yaml`.

The current HiveForge implementation is repository/ref-driven and runs declared
component actions through Ansible. PocketHive's target HiveForge model should be
release-driven, with registry-qualified image artifacts and no image build in
the deploy path. HiveForge documents that target contract, but it is not the
implemented path yet.

## Current Supported Profile

- `local-full` - deploys the existing local Docker Compose stack on a single
  Docker host by running `./build-hive.sh --quick`.

The profile intentionally maps to the existing canonical local PocketHive
entrypoint instead of inventing a second compose orchestration path. The Ansible
playbooks fail unless HiveForge passes `HIVEFORGE_PROFILE=local-full`.

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
            - local-full
          actions:
            - deploy
            - remove
            - update
```

## Known Gap

This is not yet the target release-driven PocketHive deployment contract. A
production-ready HiveForge deployment still needs explicit release/image-tag
inputs, registry-qualified image rendering, and runtime stack artifacts that do
not build images during deploy.
