#!/usr/bin/env bash
# Merge a PR only after every check on its head commit is explicitly `success` (CLAUDE.md working rules)
# AND both reviews passed on that same commit: commit statuses review/code and review/security (AOS-013, D-101).
# Usage (always run main's copy, never a PR branch's, so a PR can't change its own gate):
#   git fetch origin && bash <(git show origin/main:scripts/merge-pr.sh) <pr-number> [timeout-seconds]
# Exits non-zero WITHOUT merging on failure, cancellation, neutral, a missing conclusion, a missing or failed
# review, a push during the wait, or timeout.
set -euo pipefail
repo="pragunb02/adminos"
owner="pragunb02"   # the only account whose review statuses count
[[ $# -ge 1 && "$1" =~ ^[0-9]+$ ]] || { echo "usage: $0 <pr-number> [timeout-seconds]" >&2; exit 2; }
pr="$1"; timeout="${2:-600}"
[[ "$timeout" =~ ^[0-9]+$ ]] || { echo "timeout must be a number of seconds" >&2; exit 2; }

# The SHA everything below is judged on. The merge is pinned to it (--match-head-commit), so a push during
# the wait can't be merged unreviewed: GitHub refuses the merge if the head moved.
sha=$(gh pr view "$pr" -R "$repo" --json headRefOid -q .headRefOid)
deadline=$(( $(date +%s) + timeout ))
while :; do
  runs=$(gh api "repos/$repo/commits/$sha/check-runs?per_page=100")
  total=$(jq '.check_runs | length' <<<"$runs")
  reported=$(jq '.total_count // (.check_runs | length)' <<<"$runs")
  if [[ "$reported" -ne "$total" ]]; then
    echo "NOT MERGED: $reported check runs on $sha but only $total were listed (more than one page); check them by hand"
    exit 1
  fi
  pending=$(jq '[.check_runs[] | select(.status != "completed")] | length' <<<"$runs")
  succeeded=$(jq '[.check_runs[] | select(.status == "completed" and .conclusion == "success")] | length' <<<"$runs")
  if [[ "$total" -gt 0 && "$pending" -eq 0 ]]; then
    if [[ "$succeeded" -ne "$total" ]]; then
      echo "NOT MERGED: $succeeded of $total checks succeeded on $sha"
      jq -r '.check_runs[] | "\(.name): \(.conclusion) — \(.output.title)"' <<<"$runs"
      exit 1
    fi
    # Reviews are commit statuses on this exact SHA, listed newest first. For each context the NEWEST status
    # counts (a passing re-review replaces an earlier failure), and it must have been created by the owner's
    # account: a status posted by anything else (e.g. a GitHub Actions workflow on a branch) is refused.
    statuses=$(gh api "repos/$repo/commits/$sha/statuses?per_page=100")
    for review in review/code review/security; do
      latest=$(jq -c --arg c "$review" '[.[] | select(.context == $c)][0] // {}' <<<"$statuses")
      state=$(jq -r '.state // "missing"' <<<"$latest")
      creator=$(jq -r '.creator.login // "nobody"' <<<"$latest")
      if [[ "$state" != "success" ]]; then
        echo "NOT MERGED: $review is $state on $sha (run the reviewer: docs/ENGINEERING.md §4b)"
        exit 1
      fi
      if [[ "$creator" != "$owner" ]]; then
        echo "NOT MERGED: $review on $sha was set by $creator, not $owner"
        exit 1
      fi
    done
    summary="$(jq -r '[.check_runs[] | "\(.name)=\(.conclusion)"] | join(", ")' <<<"$runs"), review/code=success, review/security=success"
    if ! gh pr merge "$pr" -R "$repo" --merge --delete-branch --match-head-commit "$sha" >/dev/null; then
      echo "NOT MERGED: GitHub refused the merge of $sha (the head moved, or branch protection blocked it)"
      exit 1
    fi
    echo "MERGED #$pr at $sha after: $summary"
    exit 0
  fi
  if [[ $(date +%s) -ge $deadline ]]; then echo "NOT MERGED: checks still pending after ${timeout}s"; exit 1; fi
  sleep 5
done
