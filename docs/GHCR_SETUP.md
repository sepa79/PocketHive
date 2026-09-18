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
- the floating `latest` tag

The workflow updates `latest` on every successful image build; it does not
publish `stable` or `experimental` channel tags. Use the exact version or the
version-plus-SHA tag for immutable release, test, and HiveForge deployment
paths.

## Published Images

PocketHive application images are published under:

```text
ghcr.io/<owner>/pockethive/<image>:<tag>
```

The release image set is defined by the matrix in
`.github/workflows/publish-images.yml`. Repository build/push tooling mirrors
the application-image inventory in `tools/docker/image-manifest.sh`. The
publish workflow currently includes:

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
- `db-query`
- `moderator`
- `processor`
- `postprocessor`
- `clearing-export`
- `trigger`

Third-party infrastructure images such as RabbitMQ, Redis, Postgres, Grafana,
ClickHouse, WireMock, and Toxiproxy are not published by this workflow.

## Using Published Images From A Source Checkout

Use these commands only from a complete source checkout. The exact tested
Linux/macOS and Windows archives are incomplete; if this page was copied into
one of those archives, do not run its generated `DEPLOY.md`, start script,
`docker compose pull`, or `docker compose up`. Return to the same release
source checkout and preserve the failed artifact-audit evidence described in
[Deployment Package](../DEPLOYMENT_PACKAGE.md#audit-the-archive-on-a-target-host).

Create a `.env` file with an explicit registry prefix and version:

```text
DOCKER_REGISTRY=ghcr.io/yourorg/pockethive/
POCKETHIVE_VERSION=<release-version>
```

Linux/macOS, Git Bash, or WSL:

```bash
if [ -e .env ]; then
  echo ".env already exists; review and edit it instead of overwriting it." >&2
  exit 1
fi
cp .env.example .env
```

Windows PowerShell:

```powershell
if (Test-Path -LiteralPath .env) {
  throw '.env already exists; review and edit it instead of overwriting it.'
}
Copy-Item .env.example .env
```

Edit `.env` with the explicit values above, then resolve, pull, and start the
source checkout's Compose stack:

```bash
docker compose config
docker compose pull
docker compose up -d
curl -fsS http://localhost:8088/healthz
```

On Windows PowerShell, use the same Docker commands and check ingress with:

```powershell
$Health = Invoke-RestMethod http://localhost:8088/healthz
if ($Health -ne 'ok') { throw "Expected health body 'ok'; got '$Health'." }
$Health
```

Use `./build-hive.sh --quick` when you want a local rebuild/redeploy cycle. It
builds local images and is not the pure "consume published GHCR images" path.

This proves image resolution and source-checkout startup only. It does not
qualify the incomplete archive, HiveForge execution, or the current UI
lifecycle, which remains blocked at Connectivity.

## Authentication

For private packages, log in to GHCR:

Linux/macOS, Git Bash, or WSL:

```bash
echo "$GITHUB_TOKEN" | docker login ghcr.io -u USERNAME --password-stdin
```

Windows PowerShell:

```powershell
$env:GITHUB_TOKEN | docker login ghcr.io -u USERNAME --password-stdin
```

For non-GitHub Actions clients, use a Personal Access Token with `read:packages`
scope:

Linux/macOS, Git Bash, or WSL:

```bash
echo "$PAT" | docker login ghcr.io -u USERNAME --password-stdin
```

Windows PowerShell:

```powershell
$env:PAT | docker login ghcr.io -u USERNAME --password-stdin
```

## Publishing From Your Fork

1. Fork PocketHive to your GitHub organization or account.
2. Enable GitHub Actions.
3. In repository Settings -> Actions -> General, enable workflow read/write
   permissions so the workflow can publish packages.
4. Push a version tag that matches `pom.xml` `revision`, or run the workflow
   manually.

Linux/macOS, Git Bash, or WSL example:

```bash
RELEASE_VERSION="REPLACE_WITH_RELEASE_VERSION"
if [ "$RELEASE_VERSION" = "REPLACE_WITH_RELEASE_VERSION" ]; then
  echo "Set RELEASE_VERSION to the pom.xml revision before tagging." >&2
  exit 1
fi
git tag "v${RELEASE_VERSION}"
git push origin "v${RELEASE_VERSION}"
```

Windows PowerShell example:

```powershell
$ReleaseVersion = 'REPLACE_WITH_RELEASE_VERSION'
if ($ReleaseVersion -eq 'REPLACE_WITH_RELEASE_VERSION') {
  throw 'Set ReleaseVersion to the pom.xml revision before tagging.'
}
git tag "v$ReleaseVersion"
git push origin "v$ReleaseVersion"
```

To find the published packages for either a personal account or an
organization, open the repository on GitHub and use its **Packages** link, or
open the owning account's **Packages** tab.

## Local Registry Build And Push

GHCR is optional. For local test deployments, build and push the same image set
to an explicit registry using the repo tooling from Linux/macOS, Git Bash, or
WSL:

```bash
IMAGE_TAG="REPLACE_WITH_IMMUTABLE_TAG"
if [ "$IMAGE_TAG" = "REPLACE_WITH_IMMUTABLE_TAG" ]; then
  echo "Set IMAGE_TAG to an immutable development tag before publishing." >&2
  exit 1
fi
tools/docker/remote-images.sh \
  --registry 192.168.88.54:5000 \
  --namespace pockethive \
  --tag "$IMAGE_TAG" \
  --push
```

`tools/docker/remote-images.sh` uses `tools/docker/image-manifest.sh` as the
image source of truth and rejects `--tag latest`.

For a HiveForge test deploy, pass the resulting registry and tag explicitly:

```text
imageRepository.project=192.168.88.54:5000/pockethive
release.imageTag=REPLACE_WITH_THE_EXACT_IMAGE_TAG_ABOVE
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
