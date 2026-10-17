#!/usr/bin/env bash
# Tests for scripts/review-status.sh (AOS-013, D-101): the verdict lands only on the commit that was reviewed.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd); script="$here/../review-status.sh"
reviewed=0123456789abcdef0123456789abcdef01234567
moved=fedcba9876543210fedcba9876543210fedcba98
pass=0; fail=0
ok() { echo "ok   - $1"; pass=$((pass+1)); }
bad() { echo "FAIL - $1"; fail=$((fail+1)); }

# Runs the script against a fake gh whose PR head is $1; the remaining args go to the script.
run() {
  local head="$1"; shift
  dir=$(mktemp -d)
  cat > "$dir/gh" <<FAKE
#!/usr/bin/env bash
echo "\$*" >> "$dir/calls"
case "\$*" in
  "pr view"*) echo "$head" ;;
  "pr comment"*) shift 4; while [[ \$# -gt 0 ]]; do [[ "\$1" == "--body-file" ]] && cp "\$2" "$dir/comment"; shift; done ;;
  "api -X POST repos/pragunb02/adminos/statuses/"*) : ;;
  *) echo "unexpected gh \$*" >&2; exit 9 ;;
esac
FAKE
  chmod +x "$dir/gh"
  set +e; PATH="$dir:$PATH" bash "$script" "$@" > "$dir/out" 2>&1; code=$?; set -e
}
summary=$(mktemp); echo "No blocking findings." > "$summary"

run "$reviewed" 9 security success "$reviewed" "$summary"
if [[ $code -eq 0 ]] && grep -q "api -X POST repos/pragunb02/adminos/statuses/$reviewed -f state=success -f context=review/security" "$dir/calls" \
   && grep -q "security review: passed" "$dir/comment" && grep -q "Reviewed commit \`$reviewed\`" "$dir/comment" && grep -q "No blocking findings." "$dir/comment"; then
  ok "a pass posts the comment and sets review/security=success on the reviewed SHA"
else bad "pass path"; cat "$dir/out"; fi
rm -rf "$dir"

run "$reviewed" 9 code failure "$reviewed" "$summary"
if [[ $code -eq 0 ]] && grep -q "statuses/$reviewed -f state=failure -f context=review/code" "$dir/calls" && grep -q "code review: blocked" "$dir/comment"; then
  ok "a block sets review/code=failure"
else bad "failure path"; cat "$dir/out"; fi
rm -rf "$dir"

run "$moved" 9 code success "$reviewed" "$summary"
if [[ $code -eq 1 ]] && grep -q "REFUSED" "$dir/out" && ! grep -q "statuses/" "$dir/calls" && ! grep -q "pr comment" "$dir/calls"; then
  ok "a push after the review: refused, no comment and no status"
else bad "head moved"; cat "$dir/out"; fi
rm -rf "$dir"

empty=$(mktemp)
for args in "x code success $reviewed $summary" "9 style success $reviewed $summary" "9 code maybe $reviewed $summary" \
            "9 code success abc123 $summary" "9 code success $reviewed $empty" "9 code success $reviewed"; do
  run "$reviewed" $args
  if [[ $code -eq 2 ]] && [[ ! -f "$dir/calls" ]]; then ok "bad arguments ($args) exit 2 before calling gh"; else bad "bad arguments ($args): exit $code"; fi
  rm -rf "$dir"
done
rm -f "$summary" "$empty"
echo "# $pass passed, $fail failed"
[[ $fail -eq 0 ]]
