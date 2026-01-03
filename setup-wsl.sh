#!/usr/bin/env bash
# Bootstraps Docker, Portainer, Java 21, and Maven inside a fresh WSL Ubuntu environment.
# Re-runnable and safe to execute after a distro reinstall.

set -euo pipefail

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

echo "==> Preparing VS Code APT repository"
if [ ! -f /etc/apt/keyrings/microsoft.asc ]; then
  curl -fsSL https://packages.microsoft.com/keys/microsoft.asc | gpg --dearmor | sudo tee /etc/apt/keyrings/microsoft.asc >/dev/null
  sudo chmod a+r /etc/apt/keyrings/microsoft.asc
fi
VSCODE_LIST="/etc/apt/sources.list.d/vscode.list"
VSCODE_ENTRY="deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/microsoft.asc] https://packages.microsoft.com/repos/code stable main"
if [ ! -f "${VSCODE_LIST}" ] || ! grep -Fxq "${VSCODE_ENTRY}" "${VSCODE_LIST}"; then
  echo "${VSCODE_ENTRY}" | sudo tee "${VSCODE_LIST}" >/dev/null
fi

echo "==> Refreshing apt indexes after adding repositories"
sudo apt-get update -y

echo "==> Installing Docker Engine (CE) and plugins"
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

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

echo "==> Installing Node.js, npm, VS Code, and Midnight Commander"
sudo apt-get install -y nodejs npm code mc

echo "==> Installing OpenJDK 21 and Maven"
sudo apt-get install -y openjdk-21-jdk maven

echo "==> Setup complete."
echo "NOTE: log out and back in (or run 'newgrp docker') for docker group membership to take effect."
