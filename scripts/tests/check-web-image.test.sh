#!/usr/bin/env bash
# Tests for scripts/check-web-image.sh (AOS-632). `docker` is a fake that serves canned probe answers
# ("<status> <content-type> <sha256>") per path and records every call, so each refusal is the RIGHT one
# and the container (and a built image) is always removed. The real image check is the script itself:
#   scripts/check-web-image.sh
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; script="$here/../check-web-image.sh"
pass=0; fail=0

sha() { shasum -a 256 "$1" 2>/dev/null | cut -d' ' -f1 || sha256sum "$1" | cut -d' ' -f1; }

# run_case <name> <expected exit> <expected output> <answers: "path=status type sha|…" or "-" for good> [args…]
run_case() {
  local name="$1" want_code="$2" want_out="$3" answers="$4"; shift 4
  local dir code; dir="$(mktemp -d)"
  mkdir -p "$dir/public" "$dir/resp"
  printf 'self.addEventListener("push", () => {});\n' > "$dir/public/sw.js"
  printf 'png-192' > "$dir/public/icon-192.png"; printf 'png-512' > "$dir/public/icon-512.png"
  local good="/sw.js=200 application/javascript $(sha "$dir/public/sw.js")|/manifest.webmanifest=200 application/manifest+json 00|/icon-192.png=200 image/png $(sha "$dir/public/icon-192.png")|/icon-512.png=200 image/png $(sha "$dir/public/icon-512.png")|/apple-icon.png=200 image/png 00"
  local entry all="$good"; [[ "$answers" != "-" ]] && all="$good|$answers"
  IFS='|' read -r -a entries <<<"$all"
  for entry in "${entries[@]}"; do
    [[ "${entry#*=}" == "missing" ]] && { rm -f "$dir/resp/$(tr / _ <<<"${entry%%=*}")"; continue; }
    printf '%s\n' "${entry#*=}" > "$dir/resp/$(tr / _ <<<"${entry%%=*}")"
  done
  cat > "$dir/docker" <<FAKE
#!/usr/bin/env bash
echo "\$*" >> "$dir/calls"
case "\$1" in
  build) [[ -f "$dir/fail-build" ]] && exit 1; exit 0 ;;
  run) [[ -f "$dir/fail-run" ]] && exit 125; echo cid ;;
  exec) f="$dir/resp/\$(tr / _ <<<"\${@: -1}")"; if [[ -f "\$f" ]]; then cat "\$f"; else echo "000 - -"; fi ;;
  rm|logs) exit 0 ;;
  image) exit 0 ;;
  *) echo "unexpected docker \$*" >&2; exit 9 ;;
esac
FAKE
  chmod +x "$dir/docker"
  [[ "$name" == *"docker run fails"* ]] && touch "$dir/fail-run"
  [[ "$name" == *"build fails"* ]] && touch "$dir/fail-build"
  set +e
  PATH="$dir:$PATH" CHECK_WEB_PUBLIC_DIR="$dir/public" CHECK_WEB_READY_TIMEOUT=1 bash "$script" "$@" > "$dir/out" 2>&1
  code=$?; set -e
  local ok=yes
  [[ "$code" -eq "$want_code" ]] || ok=no
  grep -qF -- "$want_out" "$dir/out" || ok=no
  if [[ "$name" == *"nothing run"* ]] && grep -q '^run ' "$dir/calls" 2>/dev/null; then ok=no; echo "       a container was started"; fi
  # every started container is removed, whatever the outcome
  if grep -q '^run ' "$dir/calls" 2>/dev/null && ! grep -q '^rm -f adminos-web-check-' "$dir/calls"; then ok=no; echo "       container not removed"; fi
  # a run with no image argument builds a throwaway image and removes it; a given image is never removed
  if [[ $# -eq 0 ]] && grep -q '^build ' "$dir/calls" && ! grep -q '^image rm -f adminos-web-check:' "$dir/calls"; then ok=no; echo "       built image not removed"; fi
  if [[ $# -gt 0 ]] && grep -q '^image rm' "$dir/calls" 2>/dev/null; then ok=no; echo "       a given image was removed"; fi
  # nothing is published, the container has no network, and no env file reaches it
  if grep '^run ' "$dir/calls" 2>/dev/null | grep -qE -- ' -p | --publish|--env-file| -e '; then ok=no; echo "       container published a port or got env"; fi
  if grep -q '^run ' "$dir/calls" 2>/dev/null && ! grep '^run ' "$dir/calls" | grep -qF -- '--network none'; then ok=no; echo "       container has a network"; fi
  if [[ "$ok" == yes ]]; then
    echo "ok   - $name"; pass=$((pass + 1))
  else
    echo "FAIL - $name (exit $code, wanted $want_code with '$want_out')"; sed 's/^/       /' "$dir/out"; fail=$((fail + 1))
  fi
  rm -rf "$dir"
}

run_case "every file served with the repo's bytes: passes" 0 "serves the service worker" - my-image
run_case "no image argument: builds, checks and removes a throwaway image" 0 "serves the service worker" -
run_case "the build fails: fails, nothing run" 1 "FAIL could not build apps/web" -
run_case "sw.js missing from the image (404): fails" 1 "FAIL /sw.js: status 404" "/sw.js=404 text/html 00" my-image
run_case "sw.js redirected to the login page: fails (redirects not followed)" 1 "FAIL /sw.js: status 307" "/sw.js=307 - 00" my-image
run_case "sw.js with different bytes than the repo: fails" 1 "FAIL /sw.js: the body differs" "/sw.js=200 application/javascript deadbeef" my-image
run_case "sw.js served as HTML: fails" 1 "FAIL /sw.js: content-type text/html" "/sw.js=200 text/html 00" my-image
run_case "the manifest missing: fails" 1 "FAIL /manifest.webmanifest: status 404" "/manifest.webmanifest=404 text/html 00" my-image
run_case "the manifest with the wrong content type: fails" 1 "FAIL /manifest.webmanifest: content-type text/plain" "/manifest.webmanifest=200 text/plain 00" my-image
run_case "an icon missing: fails and counts it" 1 "1 file(s) not served" "/icon-512.png=404 text/html 00" my-image
run_case "the server never answers: fails after the timeout" 1 "did not answer within 1s" "/sw.js=missing" my-image
run_case "docker run fails: fails" 1 "FAIL could not start a container" - my-image
run_case "two arguments: usage error" 2 "usage:" - one two

echo "$pass passed, $fail failed"
[[ "$fail" -eq 0 ]]
