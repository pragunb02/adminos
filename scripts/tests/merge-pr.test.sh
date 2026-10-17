#!/usr/bin/env bash
# Tests for scripts/merge-pr.sh (AOS-013, D-101): it merges only when every check run AND both reviews
# succeeded on the PR's head commit, and pins the merge to that commit. `gh` is a fake that serves canned
# JSON, records every call, and fails loudly on anything unexpected, so a refusal must be the RIGHT refusal.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd); script="$here/../merge-pr.sh"
sha=0123456789abcdef0123456789abcdef01234567
pass=0; fail=0

# name, check-runs json, status json, expected exit code, expected output substring, [timeout]
run_case() {
  local name="$1" runs="$2" status="$3" want_code="$4" want_out="$5" timeout="${6:-5}" dir code
  dir=$(mktemp -d)
  printf '%s' "$runs" > "$dir/runs.json"; printf '%s' "$status" > "$dir/status.json"
  cat > "$dir/gh" <<FAKE
#!/usr/bin/env bash
echo "\$*" >> "$dir/calls"
case "\$*" in
  "pr view"*) echo "$sha" ;;
  *"/commits/$sha/check-runs?per_page=100") cat "$dir/runs.json" ;;
  *"/commits/$sha/statuses?per_page=100") cat "$dir/status.json" ;;
  "pr merge 7 -R pragunb02/adminos --merge --delete-branch --match-head-commit $sha") touch "$dir/merged" ;;
  *) echo "unexpected gh \$*" >&2; exit 9 ;;
esac
FAKE
  chmod +x "$dir/gh"
  set +e; PATH="$dir:$PATH" bash "$script" 7 "$timeout" > "$dir/out" 2>&1; code=$?; set -e
  local merged=no; [[ -f "$dir/merged" ]] && merged=yes
  local want_merged=no; [[ "$want_code" -eq 0 ]] && want_merged=yes
  if [[ "$code" -eq "$want_code" && "$merged" == "$want_merged" ]] && grep -qF -- "$want_out" "$dir/out"; then
    echo "ok   - $name"; pass=$((pass+1))
  else
    echo "FAIL - $name (exit $code, wanted $want_code with '$want_out')"; sed 's/^/       /' "$dir/out"; fail=$((fail+1))
  fi
  rm -rf "$dir"
}
check() { printf '{"name":"%s","status":"%s","conclusion":%s,"output":{"title":"t"}}' "$1" "$2" "$3"; }
runs() { local IFS=,; printf '{"total_count":%s,"check_runs":[%s]}' "$1" "${*:2}"; }
ok_runs=$(runs 1 "$(check GitGuardian completed '"success"')")
bad_runs=$(runs 1 "$(check GitGuardian completed '"failure"')")
pending_runs=$(runs 1 "$(check GitGuardian in_progress null)")
both='[{"context":"review/code","state":"success","creator":{"login":"pragunb02"}},{"context":"review/security","state":"success","creator":{"login":"pragunb02"}}]'
code_only='[{"context":"review/code","state":"success","creator":{"login":"pragunb02"}}]'
sec_only='[{"context":"review/security","state":"success","creator":{"login":"pragunb02"}}]'
sec_failed='[{"context":"review/code","state":"success","creator":{"login":"pragunb02"}},{"context":"review/security","state":"failure","creator":{"login":"pragunb02"}}]'
sec_pending='[{"context":"review/code","state":"success","creator":{"login":"pragunb02"}},{"context":"review/security","state":"pending","creator":{"login":"pragunb02"}}]'
forged='[{"context":"review/code","state":"success","creator":{"login":"pragunb02"}},{"context":"review/security","state":"success","creator":{"login":"github-actions[bot]"}}]'
rereviewed='[{"context":"review/security","state":"success","creator":{"login":"pragunb02"}},{"context":"review/code","state":"success","creator":{"login":"pragunb02"}},{"context":"review/security","state":"failure","creator":{"login":"pragunb02"}}]'
regressed='[{"context":"review/security","state":"failure","creator":{"login":"pragunb02"}},{"context":"review/code","state":"success","creator":{"login":"pragunb02"}},{"context":"review/security","state":"success","creator":{"login":"pragunb02"}}]'

run_case "checks and both reviews pass: merged, pinned to the reviewed SHA" "$ok_runs" "$both" 0 "MERGED #7 at $sha"
run_case "no reviews: refused for the missing code review" "$ok_runs" '[]' 1 "review/code is missing"
run_case "a review status forged by another account (an Actions workflow): refused" "$ok_runs" "$forged" 1 "set by github-actions[bot], not pragunb02"
run_case "a passing re-review after a failure (newest first): merged" "$ok_runs" "$rereviewed" 0 "MERGED #7"
run_case "a failure after an earlier pass (newest first): refused" "$ok_runs" "$regressed" 1 "review/security is failure"
run_case "only the code review: refused for the missing security review" "$ok_runs" "$code_only" 1 "review/security is missing"
run_case "only the security review: refused for the missing code review" "$ok_runs" "$sec_only" 1 "review/code is missing"
run_case "security review failed: refused" "$ok_runs" "$sec_failed" 1 "review/security is failure"
run_case "security review pending: refused" "$ok_runs" "$sec_pending" 1 "review/security is pending"
run_case "a failed check with passing reviews: refused" "$bad_runs" "$both" 1 "0 of 1 checks succeeded"
run_case "a check still running at the deadline: refused on timeout" "$pending_runs" "$both" 1 "checks still pending after 1s" 1
run_case "no check runs at all: refused on timeout" '{"total_count":0,"check_runs":[]}' "$both" 1 "checks still pending after 1s" 1
run_case "more check runs than one page: refused" "$(runs 101 "$(check GitGuardian completed '"success"')")" "$both" 1 "more than one page"

# Bad arguments never reach gh.
for args in "" "abc" "7 soon"; do
  set +e; out=$(PATH="/nonexistent:$PATH" bash "$script" $args 2>&1); code=$?; set -e
  if [[ "$code" -eq 2 ]]; then echo "ok   - bad arguments '$args' exit 2"; pass=$((pass+1)); else echo "FAIL - bad arguments '$args' exit $code: $out"; fail=$((fail+1)); fi
done

echo "# $pass passed, $fail failed"
[[ $fail -eq 0 ]]
