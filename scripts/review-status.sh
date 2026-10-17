#!/usr/bin/env bash
# Record a reviewer's verdict on the exact commit it reviewed (AOS-013, D-101).
# Usage: scripts/review-status.sh <pr-number> <code|security> <success|failure> <reviewed-sha> <summary-file>
# Refuses if the PR's head is no longer <reviewed-sha> (a push landed after the review: review again).
# Posts the summary as a PR comment, then sets the commit status `review/<kind>` on <reviewed-sha>.
# scripts/merge-pr.sh merges only when both review statuses are `success` on the head commit.
set -euo pipefail
repo="pragunb02/adminos"
[[ $# -eq 5 ]] || { echo "usage: $0 <pr> <code|security> <success|failure> <reviewed-sha> <summary-file>" >&2; exit 2; }
pr="$1"; kind="$2"; state="$3"; reviewed="$4"; summary="$5"
[[ "$pr" =~ ^[0-9]+$ ]] || { echo "PR must be a number" >&2; exit 2; }
[[ "$kind" == "code" || "$kind" == "security" ]] || { echo "kind must be code or security" >&2; exit 2; }
[[ "$state" == "success" || "$state" == "failure" ]] || { echo "state must be success or failure" >&2; exit 2; }
[[ "$reviewed" =~ ^[0-9a-f]{40}$ ]] || { echo "reviewed-sha must be a full 40-character commit SHA" >&2; exit 2; }
[[ -s "$summary" ]] || { echo "summary file is missing or empty" >&2; exit 2; }

head=$(gh pr view "$pr" -R "$repo" --json headRefOid -q .headRefOid)
if [[ "$head" != "$reviewed" ]]; then
  echo "REFUSED: PR #$pr head is $head, not the reviewed $reviewed; review the new commit" >&2
  exit 1
fi

verdict=$([[ "$state" == "success" ]] && echo "passed" || echo "blocked")
post=$(mktemp); trap 'rm -f "$post"' EXIT
{ printf '## %s review: %s\n\nReviewed commit `%s`.\n\n' "$kind" "$verdict" "$reviewed"; cat "$summary"; } > "$post"
gh pr comment "$pr" -R "$repo" --body-file "$post" >/dev/null || { echo "ERROR: could not post the review comment; no status set" >&2; exit 1; }
if ! gh api -X POST "repos/$repo/statuses/$reviewed" \
  -f state="$state" -f context="review/$kind" \
  -f description="$kind review $verdict (AOS-013)" >/dev/null; then
  echo "ERROR: comment posted but the review/$kind status was NOT set; the PR can't merge until it is" >&2
  exit 1
fi
echo "review/$kind=$state on $reviewed (PR #$pr)"
