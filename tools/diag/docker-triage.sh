#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

echo "==> Docker / Compose triage"
echo "Date: $(date -Is)"
echo "Kernel: $(uname -r)"
echo

echo "==> docker info (network bits)"
docker info 2>/dev/null | sed -n '1,140p' || true
echo

echo "==> docker ps -a"
docker ps -a --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}' | sed -n '1,200p'
echo

echo "==> docker networks"
docker network ls
echo

echo "==> compose ps"
(docker compose ps 2>/dev/null || docker-compose ps 2>/dev/null || true)
echo

scenario_manager="$(docker ps -a --format '{{.Names}}' | rg -i 'scenario-manager' | head -n 1 || true)"
orchestrator="$(docker ps -a --format '{{.Names}}' | rg -i 'orchestrator' | head -n 1 || true)"

if [ -n "${scenario_manager}" ]; then
  echo "==> logs: ${scenario_manager} (tail 200)"
  docker logs --tail 200 "${scenario_manager}" 2>&1 || true
  echo
fi

if [ -n "${orchestrator}" ]; then
  echo "==> logs: ${orchestrator} (tail 200)"
  docker logs --tail 200 "${orchestrator}" 2>&1 || true
  echo
fi

echo "==> connectivity checks (if containers exist)"
if [ -n "${orchestrator}" ]; then
  echo "- ${orchestrator} -> scenario-manager /actuator/health"
  docker exec "${orchestrator}" sh -lc 'wget -qO- http://scenario-manager:8080/actuator/health 2>/dev/null | head -c 200; echo' || true
  echo
  echo "- ${orchestrator} -> rabbitmq:5672"
  docker exec "${orchestrator}" sh -lc 'nc -zvw2 rabbitmq 5672 && echo ok' || true
  echo
fi

if [ -n "${scenario_manager}" ]; then
  echo "- ${scenario_manager} -> rabbitmq:5672"
  docker exec "${scenario_manager}" sh -lc 'nc -zvw2 rabbitmq 5672 && echo ok' || true
  echo
fi

echo "==> done"
