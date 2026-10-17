#!/usr/bin/env bash
# Merge a PR only after every check on its head commit is explicitly `success` (CLAUDE.md working rules)
# AND both reviews passed on that same commit: commit statuses review/code and review/security (AOS-013, D-101).
# Usage: scripts/merge-pr.sh <pr-number> [timeout-seconds]
# Exits non-zero WITHOUT merging on failure, cancellation, neutral, a missing conclusion, a missing or failed
# review, or timeout.
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
    # Reviews are commit statuses on this exact SHA: a push after the review has no status yet, so it can't merge.
    statuses=$(gh api "repos/pragunb02/adminos/commits/$sha/status")
    for review in review/code review/security; do
      state=$(jq -r --arg c "$review" '[.statuses[] | select(.context == $c)][0].state // "missing"' <<<"$statuses")
      if [[ "$state" != "success" ]]; then
        echo "NOT MERGED: $review is $state on $sha (run the $review reviewer: docs/ENGINEERING.md §4b)"
        exit 1
      fi
    done
    summary="$(jq -r '[.check_runs[] | "\(.name)=\(.conclusion)"] | join(", ")' <<<"$runs"), review/code=success, review/security=success"
    gh pr merge "$pr" --merge --delete-branch >/dev/null
    echo "MERGED #$pr after: $summary"
    exit 0
  fi
  if [[ $(date +%s) -ge $deadline ]]; then echo "NOT MERGED: checks still pending after ${timeout}s"; exit 1; fi
  sleep 5
done
