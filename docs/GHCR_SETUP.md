# GitHub Container Registry Setup

This guide explains how to use PocketHive images from GitHub Container Registry (GHCR).

## Automatic Publishing

Images are automatically built and published to GHCR on:
- Every push to `main` branch (tagged as `latest` and version from `VERSION` file)
- Every version tag push (e.g., `v0.13.3`)
- Manual workflow dispatch

## Using Published Images

### 1. Pull from GHCR

Create a `.env` file:

```bash
DOCKER_REGISTRY=ghcr.io/yourorg/pockethive/
POCKETHIVE_VERSION=0.13.3
```

Start PocketHive:

```bash
./start-hive.sh start
```

### 2. Authentication (for private repositories)

Login to GHCR:

```bash
echo $GITHUB_TOKEN | docker login ghcr.io -u USERNAME --password-stdin
```

Or create a Personal Access Token (PAT) with `read:packages` scope:

```bash
echo $PAT | docker login ghcr.io -u USERNAME --password-stdin
```

### 3. Available Images

All images are published under `ghcr.io/yourorg/pockethive/`:

- `rabbitmq`
- `orchestrator`
- `swarm-controller`
- `generator`
- `moderator`
- `processor`
- `postprocessor`
- `trigger`
- `scenario-manager`
- `log-aggregator`
- `ui`

## Publishing Your Own Images

### 1. Fork the Repository

Fork PocketHive to your GitHub organization/account.

### 2. Enable GitHub Actions

Ensure GitHub Actions are enabled in your repository settings.

### 3. Configure Package Permissions

Go to repository Settings → Actions → General → Workflow permissions:
- Enable "Read and write permissions"

### 4. Trigger Build

Push to `main` or create a tag:

```bash
git tag v0.13.3
git push origin v0.13.3
```

### 5. Verify Publication

Check packages at: `https://github.com/orgs/YOURORG/packages`

## Local Build and Push

Build and push manually:

```bash
export DOCKER_REGISTRY=ghcr.io/yourorg/pockethive/
export POCKETHIVE_VERSION=0.13.3

# Login
echo $GITHUB_TOKEN | docker login ghcr.io -u USERNAME --password-stdin

# Build and push
./start-hive.sh build-core build-bees push
```

## Troubleshooting

**Permission denied:**
- Ensure your GitHub token has `write:packages` scope
- Check repository package settings allow write access

**Image not found:**
- Verify the workflow completed successfully
- Check package visibility (public vs private)
- Ensure you're authenticated if packages are private
