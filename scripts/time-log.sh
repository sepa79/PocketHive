#!/usr/bin/env bash
set -euo pipefail

# Simple time logger: appends an entry to TIMELOG.md and updates TOTAL_TIME_MINUTES.
# Usage:
#   scripts/time-log.sh -m <minutes> -d "what changed" [-v <version>]

minutes=""
desc=""
version=""
while getopts ":m:d:v:" opt; do
  case "$opt" in
    m) minutes="$OPTARG" ;;
    d) desc="$OPTARG" ;;
    v) version="$OPTARG" ;;
    *) echo "Usage: $0 -m <minutes> -d <description> [-v <version>]" >&2; exit 2 ;;
  esac
done

if [[ -z "$minutes" || -z "$desc" ]]; then
  echo "Usage: $0 -m <minutes> -d <description> [-v <version>]" >&2
  exit 2
fi

ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

total_file="TOTAL_TIME_MINUTES"
if [[ ! -f "$total_file" ]]; then echo 0 > "$total_file"; fi
total_prev=$(cat "$total_file" 2>/dev/null || echo 0)
if ! [[ "$total_prev" =~ ^[0-9]+$ ]]; then total_prev=0; fi
if ! [[ "$minutes" =~ ^[0-9]+$ ]]; then echo "Minutes must be integer" >&2; exit 2; fi
total_now=$(( total_prev + minutes ))
echo "$total_now" > "$total_file"

{
  echo "- $ts | +${minutes}m | ${version:-unversioned} | $desc"
} >> TIMELOG.md

echo "Logged $minutes minutes. Total: $total_now minutes."

