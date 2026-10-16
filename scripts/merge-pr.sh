#!/usr/bin/env bash
# Merge a PR only after the security check reports success (CLAUDE.md working rules).
# Usage: scripts/merge-pr.sh <pr-number> [timeout-seconds]
# Exits non-zero WITHOUT merging on failure, cancellation, neutral, or timeout.
set -euo pipefail
pr="$1"; timeout="${2:-600}"
sha=$(gh pr view "$pr" --json headRefOid -q .headRefOid)
deadline=$(( $(date +%s) + timeout ))
while :; do
  conclusions=$(gh api "repos/pragunb02/adminos/commits/$sha/check-runs" -q '[.check_runs[] | select(.status=="completed") | .conclusion] | join(" ")')
  pending=$(gh api "repos/pragunb02/adminos/commits/$sha/check-runs" -q '[.check_runs[] | select(.status!="completed")] | length')
  total=$(gh api "repos/pragunb02/adminos/commits/$sha/check-runs" -q '.check_runs | length')
  if [[ "$total" -gt 0 && "$pending" -eq 0 ]]; then
    for c in $conclusions; do
      if [[ "$c" != "success" ]]; then
        echo "NOT MERGED: check concluded '$c' on $sha"; gh api "repos/pragunb02/adminos/commits/$sha/check-runs" -q '.check_runs[] | "\(.name): \(.conclusion) — \(.output.title)"'; exit 1
      fi
    done
    gh pr merge "$pr" --merge --delete-branch >/dev/null
    echo "MERGED #$pr after: $(gh api "repos/pragunb02/adminos/commits/$sha/check-runs" -q '[.check_runs[] | "\(.name)=\(.conclusion)"] | join(", ")')"
    exit 0
  fi
  if [[ $(date +%s) -ge $deadline ]]; then echo "NOT MERGED: checks still pending after ${timeout}s"; exit 1; fi
  sleep 5
done
