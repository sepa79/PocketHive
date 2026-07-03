# GitHub Container Registry Setup

This guide explains how to publish and consume PocketHive application images
from GitHub Container Registry (GHCR).

## Automatic Publishing

The `Publish Docker Images` workflow publishes images to GHCR on:

- version tag pushes matching `v*`, for example `v0.15.20`
- manual workflow dispatch

The workflow does not publish on every `main` push.

Version tags must match the root Maven `revision` value. For example, tag
`v0.15.20` is accepted only when `pom.xml` resolves `revision` to `0.15.20`.

## Published Tags

For each image, the workflow publishes:

- the exact project version, for example `0.15.20`
- the version plus short commit SHA, for example `0.15.20-d6819e3`
- the minor line, for example `0.15`
- the release channel, either `stable` or `experimental`

The workflow publishes `latest` only for stable minor versions. Do not use
`latest` for HiveForge release/test deployment paths.

## Published Images

PocketHive application images are published under:

```text
ghcr.io/<owner>/pockethive/<image>:<tag>
```

The image list is defined by `tools/docker/image-manifest.sh` and currently
includes:

- `jvm-base`
- `auth-service`
- `scenario-manager`
- `network-proxy-manager`
- `orchestrator`
- `tcp-mock-server`
- `network-proxy-haproxy`
- `ui`
- `swarm-controller`
- `generator`
- `request-builder`
- `http-sequence`
- `moderator`
- `processor`
- `postprocessor`
- `clearing-export`
- `trigger`

Third-party infrastructure images such as RabbitMQ, Redis, Postgres, Prometheus,
Grafana, ClickHouse, WireMock, and Toxiproxy are not published by this workflow.

## Using Published Images

Create a `.env` file with an explicit registry prefix and version:

```bash
DOCKER_REGISTRY=ghcr.io/yourorg/pockethive/
POCKETHIVE_VERSION=0.15.20
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
git tag v0.15.20
git push origin v0.15.20
```

Check packages at:

```text
https://github.com/orgs/YOURORG/packages
```

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
