#!/usr/bin/env bash
# Bootstraps Docker, Portainer, Java 21, and Maven inside a fresh WSL Ubuntu environment.
# Re-runnable and safe to execute after a distro reinstall.

set -euo pipefail

DOCKER_DEFAULT_POOL_BASE="${DOCKER_DEFAULT_POOL_BASE:-10.200.0.0/16}"
DOCKER_DEFAULT_POOL_SIZE="${DOCKER_DEFAULT_POOL_SIZE:-24}"

echo "==> Updating apt package index"
sudo apt-get update -y

echo "==> Installing base packages (ca-certificates, curl, gnupg, lsb-release)"
sudo apt-get install -y ca-certificates curl gnupg lsb-release

echo "==> Preparing Docker APT repository"
sudo install -m 0755 -d /etc/apt/keyrings
if [ ! -f /etc/apt/keyrings/docker.asc ]; then
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo tee /etc/apt/keyrings/docker.asc >/dev/null
  sudo chmod a+r /etc/apt/keyrings/docker.asc
fi

UBUNTU_CODENAME="$(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")"
DOCKER_LIST="/etc/apt/sources.list.d/docker.list"
DOCKER_ENTRY="deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${UBUNTU_CODENAME} stable"
if [ ! -f "${DOCKER_LIST}" ] || ! grep -Fxq "${DOCKER_ENTRY}" "${DOCKER_LIST}"; then
  echo "${DOCKER_ENTRY}" | sudo tee "${DOCKER_LIST}" >/dev/null
fi

echo "==> Refreshing apt indexes after adding repositories"
sudo apt-get update -y

echo "==> Installing Docker Engine (CE) and plugins"
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

echo "==> Configuring Docker default address pools (${DOCKER_DEFAULT_POOL_BASE} size /${DOCKER_DEFAULT_POOL_SIZE})"
sudo install -m 0755 -d /etc/docker
sudo python3 - <<PY
import json
from pathlib import Path

pool_base = "${DOCKER_DEFAULT_POOL_BASE}"
pool_size = int("${DOCKER_DEFAULT_POOL_SIZE}")

p = Path("/etc/docker/daemon.json")
data = {}
if p.exists():
    raw = p.read_text(encoding="utf-8").strip()
    if raw:
        try:
            data = json.loads(raw)
        except Exception as e:
            raise SystemExit(f"ERROR: /etc/docker/daemon.json is not valid JSON: {e}")

if "default-address-pools" not in data:
    data["default-address-pools"] = [{"base": pool_base, "size": pool_size}]

p.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
print("OK: wrote /etc/docker/daemon.json")
PY

if command -v systemctl >/dev/null 2>&1; then
  echo "==> Enabling and starting Docker service via systemd"
  sudo systemctl enable docker >/dev/null 2>&1 || true
  sudo systemctl start docker >/dev/null 2>&1 || true
else
  echo "==> Starting Docker service"
  sudo service docker start >/dev/null 2>&1 || true
fi

echo "==> Ensuring current user (${USER}) is in the docker group"
sudo usermod -aG docker "${USER}"

echo "==> Verifying Docker socket access for current user"
if ! docker info >/dev/null 2>&1; then
  echo "WARNING: Docker socket access is not active yet for ${USER}."
  echo "To avoid permission errors like 'permission denied while trying to connect to the Docker daemon socket',"
  echo "run: newgrp docker"
  echo "If that does not work, log out and back in (or restart WSL)."
fi

echo "==> Installing Portainer CE (containerized UI)"
if ! sudo docker volume inspect portainer_data >/dev/null 2>&1; then
  sudo docker volume create portainer_data >/dev/null
fi
if ! sudo docker ps -a --format '{{.Names}}' | grep -Fxq "portainer"; then
  sudo docker run -d \
    -p 9443:9443 \
    --name portainer \
    --restart=always \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v portainer_data:/data \
    cr.portainer.io/portainer/portainer-ce:latest >/dev/null
else
  echo "   Portainer container already exists; skipping docker run."
fi

echo "==> Installing Node.js, npm, and Midnight Commander"
sudo apt-get install -y nodejs npm mc

echo "==> Installing OpenJDK 21 and Maven"
sudo apt-get install -y openjdk-21-jdk maven

echo "==> Setup complete."
echo "NOTE: log out and back in (or run 'newgrp docker') for docker group membership to take effect."
echo "TIP: if containers time out talking to each other after a restart, try: docker compose down --remove-orphans && docker compose up -d"
