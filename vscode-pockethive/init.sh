#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

usage() {
  cat <<'EOF'
PocketHive VS Code extension bootstrap

Usage:
  ./init.sh [--clean] [--package] [--install]

Options:
  --clean     Remove build outputs (out/, node_modules/, *.vsix) first
  --package   Create a .vsix (runs "npm run package")
  --install   Install the generated .vsix into VS Code via "code --install-extension"
              (implies --package)
EOF
}

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "ERROR: Missing required command: $1" >&2
    exit 1
  fi
}

run() {
  echo "+ $*"
  "$@"
}

CLEAN=false
PACKAGE=false
INSTALL=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --clean) CLEAN=true ;;
    --package) PACKAGE=true ;;
    --install) INSTALL=true; PACKAGE=true ;;
    -h|--help) usage; exit 0 ;;
    *)
      echo "ERROR: Unknown argument: $1" >&2
      usage
      exit 2
      ;;
  esac
  shift
done

need_cmd node
need_cmd npm

echo "Node: $(node -v)"
echo "npm:  $(npm -v)"

if [[ "$CLEAN" == "true" ]]; then
  echo "Cleaning build outputs..."
  rm -rf out node_modules
  rm -f ./*.vsix
fi

run npm ci
run npm run -s build

if [[ "$PACKAGE" == "true" ]]; then
  run npm run -s package

  VSIX_PATH="$(ls -1t pockethive-vscode-*.vsix 2>/dev/null | head -n 1 || true)"
  if [[ -z "$VSIX_PATH" ]]; then
    echo "ERROR: Expected a .vsix file, but none was found." >&2
    exit 1
  fi

  echo "Built VSIX: $VSIX_PATH"

  if [[ "$INSTALL" == "true" ]]; then
    if command -v code >/dev/null 2>&1; then
      run code --install-extension "$VSIX_PATH" --force
      echo "Installed into VS Code."
    else
      echo "WARN: VS Code CLI 'code' not found; skipping install." >&2
      echo "      You can install manually: Extensions -> 'Install from VSIX...'" >&2
    fi
  fi
fi

cat <<'EOF'

Next steps:
  - Debug (recommended): open this folder in VS Code and press F5 ("Run PocketHive Extension")
  - Or install: ./init.sh --install

Tip:
  The extension activates on PocketHive commands/views/custom editor. In the Extension Development Host,
  open the PocketHive activity-bar view or run a "pockethive.*" command to trigger activation.
EOF

