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

# List local branches merged into BASE_BRANCH, excluding HEAD/BASE_BRANCH/current branch.
# Notes:
# - `git branch` prefixes branches checked out in another worktree with `+` (we skip those).
# - We treat branch names as line-based data (avoid word-splitting issues).
merged_branches=$(
  git branch --merged "${BASE_BRANCH}" | while IFS= read -r line; do
    trimmed="$(printf '%s' "${line}" | sed 's/^[[:space:]]*//')"
    [ -z "${trimmed}" ] && continue

    case "${trimmed}" in
      +*)
        # Checked out in another worktree; never try to delete.
        continue
        ;;
      \**)
        # Current branch marker.
        branch="${trimmed#\*}"
        branch="$(printf '%s' "${branch}" | sed 's/^[[:space:]]*//')"
        ;;
      *)
        branch="${trimmed}"
        ;;
    esac

    if [ "${branch}" = "HEAD" ] || [ "${branch}" = "${BASE_BRANCH}" ] || [ "${branch}" = "${current_branch}" ]; then
      continue
    fi

    printf '%s\n' "${branch}"
  done
)

if [ -z "${merged_branches}" ]; then
  echo "No local branches to delete; nothing to do."
  exit 0
fi

echo "The following local branches are merged into '${BASE_BRANCH}' and will be deleted:"
printf '%s\n' "${merged_branches}" | while IFS= read -r b; do
  [ -z "${b}" ] && continue
  echo "  - ${b}"
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

printf '%s\n' "${merged_branches}" | while IFS= read -r b; do
  [ -z "${b}" ] && continue
  echo "Deleting local branch: ${b}"
  git branch -d "${b}" || true
done

echo
echo "Cleanup complete."
