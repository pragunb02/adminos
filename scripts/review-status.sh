#!/usr/bin/env bash
# Record a reviewer's verdict on a PR's current head commit (AOS-013, D-101).
# Usage: scripts/review-status.sh <pr-number> <code|security> <success|failure> <summary-file>
# Posts the summary file as a PR comment and sets the commit status `review/<kind>` on the head SHA.
# A new push changes the SHA, so it needs a fresh review before scripts/merge-pr.sh will merge.
set -euo pipefail
repo="pragunb02/adminos"
[[ $# -eq 4 ]] || { echo "usage: $0 <pr> <code|security> <success|failure> <summary-file>" >&2; exit 2; }
pr="$1"; kind="$2"; state="$3"; summary="$4"
[[ "$pr" =~ ^[0-9]+$ ]] || { echo "PR must be a number" >&2; exit 2; }
[[ "$kind" == "code" || "$kind" == "security" ]] || { echo "kind must be code or security" >&2; exit 2; }
[[ "$state" == "success" || "$state" == "failure" ]] || { echo "state must be success or failure" >&2; exit 2; }
[[ -s "$summary" ]] || { echo "summary file is missing or empty" >&2; exit 2; }

sha=$(gh pr view "$pr" --json headRefOid -q .headRefOid)
verdict=$([[ "$state" == "success" ]] && echo "passed" || echo "blocked")
{ printf '## %s review: %s\n\nReviewed commit `%s`.\n\n' "$kind" "$verdict" "$sha"; cat "$summary"; } > "${summary}.post"
gh pr comment "$pr" --body-file "${summary}.post" >/dev/null
rm -f "${summary}.post"
gh api -X POST "repos/$repo/statuses/$sha" \
  -f state="$state" -f context="review/$kind" \
  -f description="$kind review $verdict (AOS-013)" >/dev/null
echo "review/$kind=$state on $sha (PR #$pr)"
