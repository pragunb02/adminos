#!/usr/bin/env bash
# Merge a PR only after every check on its head commit is explicitly `success` (CLAUDE.md working rules).
# Usage: scripts/merge-pr.sh <pr-number> [timeout-seconds]
# Exits non-zero WITHOUT merging on failure, cancellation, neutral, a missing conclusion, or timeout.
set -euo pipefail
pr="$1"; timeout="${2:-600}"
sha=$(gh pr view "$pr" --json headRefOid -q .headRefOid)
deadline=$(( $(date +%s) + timeout ))
while :; do
  runs=$(gh api "repos/pragunb02/adminos/commits/$sha/check-runs")
  total=$(jq '.check_runs | length' <<<"$runs")
  pending=$(jq '[.check_runs[] | select(.status != "completed")] | length' <<<"$runs")
  succeeded=$(jq '[.check_runs[] | select(.status == "completed" and .conclusion == "success")] | length' <<<"$runs")
  if [[ "$total" -gt 0 && "$pending" -eq 0 ]]; then
    if [[ "$succeeded" -ne "$total" ]]; then
      echo "NOT MERGED: $succeeded of $total checks succeeded on $sha"
      jq -r '.check_runs[] | "\(.name): \(.conclusion) — \(.output.title)"' <<<"$runs"
      exit 1
    fi
    summary=$(jq -r '[.check_runs[] | "\(.name)=\(.conclusion)"] | join(", ")' <<<"$runs")
    gh pr merge "$pr" --merge --delete-branch >/dev/null
    echo "MERGED #$pr after: $summary"
    exit 0
  fi
  if [[ $(date +%s) -ge $deadline ]]; then echo "NOT MERGED: checks still pending after ${timeout}s"; exit 1; fi
  sleep 5
done
