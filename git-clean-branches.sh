#!/usr/bin/env sh
set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${SCRIPT_DIR}"

BASE_BRANCH="${1:-main}"

usage() {
  cat <<USAGE
Usage: $(basename "$0") [base-branch]

Cleans up local and remote-tracking branches that are already merged.

Actions:
  1) Fetch and prune remote-tracking branches for all remotes.
  2) Delete local branches fully merged into the base branch (default: main).

Notes:
  - Only local branches that are already merged into the base branch are deleted.
  - The current branch and the base branch are never deleted.

Examples:
  $(basename "$0")          # prune remotes, clean branches merged into main
  $(basename "$0") develop  # prune remotes, clean branches merged into develop
USAGE
}

if [ "$#" -gt 0 ] && { [ "$1" = "-h" ] || [ "$1" = "--help" ]; }; then
  usage
  exit 0
fi

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "This script must be run inside a Git repository." >&2
  exit 1
fi

if ! git show-ref --verify --quiet "refs/heads/${BASE_BRANCH}"; then
  echo "Base branch '${BASE_BRANCH}' does not exist locally." >&2
  echo "Usage: $(basename "$0") [base-branch]" >&2
  exit 1
fi

echo "Using base branch: ${BASE_BRANCH}"
echo

echo "Fetching and pruning remote-tracking branches..."
git fetch --all --prune

for remote in $(git remote); do
  echo "Pruning remote '${remote}'..."
  git remote prune "${remote}" || true
done

echo
echo "Finding local branches fully merged into '${BASE_BRANCH}'..."
current_branch="$(git rev-parse --abbrev-ref HEAD)"

# List local branches merged into BASE_BRANCH, excluding HEAD/BASE_BRANCH.
merged_branches=$(
  git branch --merged "${BASE_BRANCH}" \
    | sed 's/^[ *]*//' \
    | grep -v -E "^(HEAD|${BASE_BRANCH})$" || true
)

if [ -z "${merged_branches}" ]; then
  echo "No local branches to delete; nothing to do."
  exit 0
fi

# Never delete the current branch, even if merged.
filtered_branches=""
for b in ${merged_branches}; do
  if [ "$b" != "$current_branch" ]; then
    filtered_branches="${filtered_branches} ${b}"
  fi
done

# Trim leading space for display/looping.
filtered_branches=$(printf '%s\n' "${filtered_branches}" | sed 's/^ *//')

if [ -z "${filtered_branches}" ]; then
  echo "All merged branches are either the base branch or the current branch; nothing to delete."
  exit 0
fi

echo "The following local branches are merged into '${BASE_BRANCH}' and will be deleted:"
for b in ${filtered_branches}; do
  echo "  - $b"
done

echo
printf "Delete these branches locally? [y/N] "
IFS= read -r answer
case "$answer" in
  y|Y|yes|YES)
    ;;
  *)
    echo "Aborting; no branches deleted."
    exit 0
    ;;
esac

for b in ${filtered_branches}; do
  echo "Deleting local branch: $b"
  git branch -d "$b" || true
done

echo
echo "Cleanup complete."
