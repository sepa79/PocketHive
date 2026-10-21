# GitHub Container Registry Setup

This guide explains how to publish and consume PocketHive application images
from GitHub Container Registry (GHCR).

## Automatic Publishing

The `Publish Docker Images` workflow publishes images to GHCR on:

- version tag pushes matching `v*`, for example `v0.15.36`
- manual workflow dispatch

The workflow does not publish on every `main` push.

Version tags must match the root Maven `revision` value. For example, tag
`v0.15.36` is accepted only when `pom.xml` resolves `revision` to `0.15.36`.

## Published Tags

For each application image and the JVM base, the workflow publishes:

- the exact project version, for example `0.15.36`;
- the version plus short commit SHA, for example `0.15.36-01e80a4`;
- the minor line, for example `0.15`; and
- `latest` for every successful image build, regardless of minor-version parity.

The current workflow does not create `stable` or `experimental` image tags.
Choose the explicit release or revision-qualified image tag for installation;
publication of a mutable tag is not a source-to-image identity check.

## Published Images

PocketHive application images are published under:

```text
ghcr.io/<owner>/pockethive/<image>:<tag>
```

The image inventory is owned by
[`tools/docker/image-manifest.sh`](https://github.com/sepa79/PocketHive/blob/main/tools/docker/image-manifest.sh); the
[publishing workflow](https://github.com/sepa79/PocketHive/blob/main/.github/workflows/publish-images.yml) builds that
application set, including `pockethive-mcp`, `db-query` and the release-matched
`artemis` image. Do not maintain a second image list here.
Third-party infrastructure images such as RabbitMQ, Redis, Postgres, Grafana,
ClickHouse, WireMock and Toxiproxy are not published by this workflow.

For HiveForge installation, use the
[complete profile configuration and MCP procedure](HIVEFORGE.md).
The Compose consumption example below is for the local Compose path.

## Using Published Images

Export the explicit registry prefix and version into the shell that will run
Compose:

```bash
export DOCKER_REGISTRY=ghcr.io/yourorg/pockethive/
export POCKETHIVE_VERSION=0.15.36
```

Alternatively, put the following literal content in `.env` next to
`docker-compose.yml`. The file uses assignments without `export`:

```text
DOCKER_REGISTRY=ghcr.io/yourorg/pockethive/
POCKETHIVE_VERSION=0.15.36
```

Then pull and start the Compose stack:

```bash
docker compose pull
docker compose up -d
```

Use `./build-hive.sh --quick` when you want a local rebuild/redeploy cycle. It
builds local images and is not the pure "consume published GHCR images" path.

## Authentication

For private packages, log in to GHCR:

```bash
echo "$GITHUB_TOKEN" | docker login ghcr.io -u USERNAME --password-stdin
```

For non-GitHub Actions clients, use a Personal Access Token with `read:packages`
scope:

```bash
echo "$PAT" | docker login ghcr.io -u USERNAME --password-stdin
```

## Publishing From Your Fork

1. Fork PocketHive to your GitHub organization or account.
2. Enable GitHub Actions.
3. In repository Settings -> Actions -> General, enable workflow read/write
   permissions so the workflow can publish packages.
4. Push a version tag that matches `pom.xml` `revision`, or run the workflow
   manually.

Example:

```bash
git tag v0.15.36
git push origin v0.15.36
```

Check packages at:

```text
https://github.com/orgs/YOURORG/packages
```

## Release publication checklist

Before publishing a PocketHive release:

- [ ] Verify the selected Git tag matches the root Maven `revision` and the
  application's published image tags; retain the publishing workflow result.
- [ ] Compare deployment inputs with the previous released manifest, including
  required fields, allowed values, profile-specific roots and platform inputs.
- [ ] Whenever an input becomes mandatory or changes meaning, update
  [the current installation guide](HIVEFORGE.md) and all its complete profile
  examples, add the exact affected version boundary to
  [Upgrading PocketHive](UPGRADING.md), and link that guidance from `CHANGELOG.md`.
  A commit title or image/version bump is not operator migration guidance.
- [ ] Check both `deploy` and `update`, both Swarm profiles, HTTPS and explicitly
  allowed HTTP, and both WORK selections against the canonical render contract.
  Keep full-only roots conditional; distinguish manifest presence checks from
  playbook validation and live storage/access verification.
- [ ] Confirm every required application image completed publication before
  deployment. Installation/runtime evidence must identify the selected source,
  image references, profile and public identity.

## Local Registry Build And Push

GHCR is optional. For local test deployments, build and push the same image set
to an explicit registry using the repo tooling:

```bash
tools/docker/remote-images.sh \
  --registry 192.168.88.54:5000 \
  --namespace pockethive \
  --tag dev-YYYYMMDD-HHMM-g<sha> \
  --push
```

`tools/docker/remote-images.sh` uses `tools/docker/image-manifest.sh` as the
image source of truth and rejects `--tag latest`.

For a HiveForge test deploy, pass the resulting registry and tag explicitly:

```text
imageRepository.project=192.168.88.54:5000/pockethive
release.imageTag=dev-YYYYMMDD-HHMM-g<sha>
```

## Troubleshooting

**Permission denied**

- Ensure your GitHub token has `write:packages` for publishing or
  `read:packages` for pulling private images.
- Check repository package settings allow the intended access.

**Image not found**

- Verify the workflow completed successfully.
- Check package visibility.
- Ensure `DOCKER_REGISTRY` ends with `/pockethive/`.
- Ensure `POCKETHIVE_VERSION` is an explicit tag that the workflow published.
