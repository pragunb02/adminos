#!/usr/bin/env bash
# Tests for scripts/merge-pr.sh (AOS-013): it merges only when every check run AND both reviews succeeded
# on the PR's head commit. `gh` is replaced by a fake that serves canned JSON and records a merge.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd); script="$here/../merge-pr.sh"
pass=0; fail=0
run_case() { # name, check-runs json, status json, expect (merged|refused)
  local name="$1" runs="$2" status="$3" expect="$4" dir; dir=$(mktemp -d)
  printf '%s' "$runs" > "$dir/runs.json"; printf '%s' "$status" > "$dir/status.json"
  cat > "$dir/gh" <<FAKE
#!/usr/bin/env bash
case "\$*" in
  "pr view"*) echo "abc123" ;;
  *check-runs*) cat "$dir/runs.json" ;;
  */status*) cat "$dir/status.json" ;;
  "pr merge"*) touch "$dir/merged" ;;
  *) echo "unexpected gh \$*" >&2; exit 9 ;;
esac
FAKE
  chmod +x "$dir/gh"
  PATH="$dir:$PATH" bash "$script" 7 5 > "$dir/out" 2>&1 || true
  local got; [[ -f "$dir/merged" ]] && got=merged || got=refused
  if [[ "$got" == "$expect" ]]; then echo "ok   - $name"; pass=$((pass+1)); else echo "FAIL - $name (got $got)"; cat "$dir/out"; fail=$((fail+1)); fi
  rm -rf "$dir"
}
ok_runs='{"check_runs":[{"name":"GitGuardian","status":"completed","conclusion":"success","output":{"title":"ok"}}]}'
bad_runs='{"check_runs":[{"name":"GitGuardian","status":"completed","conclusion":"failure","output":{"title":"secret"}}]}'
both='{"statuses":[{"context":"review/code","state":"success"},{"context":"review/security","state":"success"}]}'
run_case "checks and both reviews pass → merged" "$ok_runs" "$both" merged
run_case "no reviews → refused" "$ok_runs" '{"statuses":[]}' refused
run_case "only the code review → refused" "$ok_runs" '{"statuses":[{"context":"review/code","state":"success"}]}' refused
run_case "security review failed → refused" "$ok_runs" '{"statuses":[{"context":"review/code","state":"success"},{"context":"review/security","state":"failure"}]}' refused
run_case "security review pending → refused" "$ok_runs" '{"statuses":[{"context":"review/code","state":"success"},{"context":"review/security","state":"pending"}]}' refused
run_case "a failed check with passing reviews → refused" "$bad_runs" "$both" refused
run_case "no check runs at all → refused (times out)" '{"check_runs":[]}' "$both" refused
echo "# $pass passed, $fail failed"
[[ $fail -eq 0 ]]
