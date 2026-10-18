#!/usr/bin/env bash
# Checks that the web Docker image serves its static PWA files (AOS-632): the service worker, the manifest
# and the icons. Without them in the image, Web Push can't register and the Home Screen app has no icon.
#
# Usage:
#   scripts/check-web-image.sh            # builds apps/web into a throwaway tag, checks it, removes it
#   scripts/check-web-image.sh <image>    # checks an image that is already built (left in place)
#
# The container runs with no secrets and no environment from .env, no published port and no network at all
# (`--network none`, only its own loopback), all capabilities dropped. Each file is fetched from inside the
# container with Node's fetch, redirects NOT followed, so a redirect to /login is a failure, not a pass.
# Files from public/ must match the repo's bytes (sha256). Fail closed: any error is a failure.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
public_dir="${CHECK_WEB_PUBLIC_DIR:-$repo_root/apps/web/public}"
ready_timeout="${CHECK_WEB_READY_TIMEOUT:-90}"   # seconds for `next start` to answer

command -v docker >/dev/null || { echo "check-web-image: docker is required" >&2; exit 2; }

built=""
container=""
cleanup() {
  [[ -n "$container" ]] && docker rm -f "$container" >/dev/null 2>&1 || true
  [[ -n "$built" ]] && docker image rm -f "$built" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [[ $# -gt 1 ]]; then echo "usage: $0 [image]" >&2; exit 2; fi
image="${1:-}"
if [[ -z "$image" ]]; then
  built="adminos-web-check:$$"
  image="$built"
  echo "building apps/web as $image"
  docker build -q -t "$image" "$repo_root/apps/web" >/dev/null || { echo "FAIL could not build apps/web"; exit 1; }
fi

container="adminos-web-check-$$"
docker run -d --name "$container" --network none --cap-drop ALL --security-opt no-new-privileges:true \
  "$image" >/dev/null || { echo "FAIL could not start a container from $image"; exit 1; }

# probe <path>: prints "<status> <content-type> <sha256 of body>", or "000 - -" when nothing answers.
probe() {
  docker exec "$container" node -e '
    const crypto = require("node:crypto");
    fetch("http://127.0.0.1:3000" + process.argv[1], { redirect: "manual" })
      .then(async (r) => {
        const body = Buffer.from(await r.arrayBuffer());
        const type = (r.headers.get("content-type") || "-").split(";")[0].trim() || "-";
        console.log(r.status, type, crypto.createHash("sha256").update(body).digest("hex"));
      })
      .catch(() => console.log("000 - -"));
  ' "$1" 2>/dev/null || echo "000 - -"
}

waited=0
until [[ "$(probe /sw.js | cut -d' ' -f1)" != "000" ]]; do
  if (( waited >= ready_timeout )); then
    echo "FAIL the web server did not answer within ${ready_timeout}s"
    docker logs --tail 20 "$container" 2>&1 | sed 's/^/  | /' || true
    exit 1
  fi
  sleep 1; waited=$((waited + 1))
done

sha_of() { shasum -a 256 "$1" 2>/dev/null | cut -d' ' -f1 || sha256sum "$1" | cut -d' ' -f1; }

failures=0
# expect <path> <content-type> [repo file whose bytes the response must match]
expect() {
  local path="$1" want_type="$2" file="${3:-}" status type sha want_sha=""
  read -r status type sha <<<"$(probe "$path")"
  if [[ -n "$file" ]]; then
    if [[ ! -f "$file" ]]; then echo "FAIL $path: the repo file $file is missing"; failures=$((failures + 1)); return; fi
    want_sha="$(sha_of "$file")"
  fi
  if [[ "$status" != "200" ]]; then
    echo "FAIL $path: status $status (wanted 200)"; failures=$((failures + 1))
  elif [[ "$type" != "$want_type" ]]; then
    echo "FAIL $path: content-type $type (wanted $want_type)"; failures=$((failures + 1))
  elif [[ -n "$want_sha" && "$sha" != "$want_sha" ]]; then
    echo "FAIL $path: the body differs from $(basename "$file") in the repo"; failures=$((failures + 1))
  else
    echo "OK   $path: 200 $type"
  fi
}

expect /sw.js application/javascript "$public_dir/sw.js"
expect /manifest.webmanifest application/manifest+json
expect /icon-192.png image/png "$public_dir/icon-192.png"
expect /icon-512.png image/png "$public_dir/icon-512.png"
expect /apple-icon.png image/png   # generated from src/app/apple-icon.png

if (( failures > 0 )); then
  echo "check-web-image: $failures file(s) not served by $image"
  exit 1
fi
echo "check-web-image: $image serves the service worker, the manifest and the icons"
