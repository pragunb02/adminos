#!/usr/bin/env bash
# Merge a PR only after every check on its head commit is explicitly `success` (CLAUDE.md working rules)
# AND both reviews passed on that same commit: commit statuses review/code and review/security (AOS-013, D-101).
# Usage (always run main's copy, never a PR branch's, so a PR can't change its own gate):
#   f=$(mktemp) && git fetch origin && git show origin/main:scripts/merge-pr.sh > "$f" && bash "$f" <pr-number> [timeout-seconds]; rm -f "$f"
# It merged only if it prints "MERGED #<pr-number>". MERGE_PR_POLL_SECONDS (default 5) sets the poll interval.
# Exits non-zero WITHOUT merging on failure, cancellation, neutral, a missing conclusion, a missing or failed
# review, a push during the wait, or timeout.
# Required checks (AOS-622): every name in main's .github/required-checks.txt must have reported `success` on the
# head commit. Until all of them exist the PR keeps waiting, so a fast check (GitGuardian) finishing before CI's
# jobs are even created can't trigger a merge. The list is read from main through the API, never from the PR.
set -euo pipefail
repo="pragunb02/adminos"
owner="pragunb02"   # the only account whose review statuses count
[[ $# -ge 1 && "$1" =~ ^[0-9]+$ ]] || { echo "usage: $0 <pr-number> [timeout-seconds]" >&2; exit 2; }
pr="$1"; timeout="${2:-600}"
[[ "$timeout" =~ ^[0-9]+$ ]] || { echo "timeout must be a number of seconds" >&2; exit 2; }
poll="${MERGE_PR_POLL_SECONDS:-5}"
[[ "$poll" =~ ^[1-9][0-9]{0,2}$ ]] || { echo "MERGE_PR_POLL_SECONDS must be 1..999" >&2; exit 2; }

# The SHA everything below is judged on. The merge is pinned to it (--match-head-commit), so a push during
# the wait can't be merged unreviewed: GitHub refuses the merge if the head moved.
sha=$(gh pr view "$pr" -R "$repo" --json headRefOid -q .headRefOid)
if ! required_file=$(gh api -H "Accept: application/vnd.github.raw" "repos/$repo/contents/.github/required-checks.txt?ref=main"); then
  echo "NOT MERGED: could not read .github/required-checks.txt from main"
  exit 1
fi
required=$(sed -e 's/#.*//' -e 's/[[:space:]]*$//' -e 's/^[[:space:]]*//' <<<"$required_file" | grep -v '^$' || true)
if [[ -z "$required" ]]; then echo "NOT MERGED: main's .github/required-checks.txt lists no checks"; exit 1; fi
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
  # A required check that hasn't reported yet counts as pending.
  missing=$(jq -r --arg req "$required" '($req | split("\n")) - [.check_runs[].name] | join(", ")' <<<"$runs")
  if [[ -n "$missing" ]]; then pending=$(( pending + 1 )); fi
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
  if [[ $(date +%s) -ge $deadline ]]; then
    echo "NOT MERGED: checks still pending after ${timeout}s${missing:+ (required checks not reported: $missing)}"
    exit 1
  fi
  sleep "$poll"
done
