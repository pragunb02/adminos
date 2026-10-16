#!/usr/bin/env bash
# Installs the AdminOS SMS reader as a login item (launchd user agent). Run it yourself:
#   tools/sms-reader/scripts/install.sh [--backfill-days N]
# It builds the macOS binary (in Docker), signs it with a stable identifier (so the Full Disk Access
# grant sticks), installs it under ~/Library/Application Support/AdminOS, and starts it at login.
set -euo pipefail

BACKFILL=0
if [[ "${1:-}" == "--backfill-days" ]]; then BACKFILL="${2:?number of days}"; fi

HERE="$(cd "$(dirname "$0")/.." && pwd)"
SUPPORT="$HOME/Library/Application Support/AdminOS"
BIN="$SUPPORT/bin/sms-reader"
TOKEN="$SUPPORT/device-token"
LOGS="$HOME/Library/Logs/AdminOS"
LABEL="dev.adminos.sms-reader"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"

[[ "$(uname -s)" == "Darwin" ]] || { echo "macOS only"; exit 1; }
[[ -f "$TOKEN" ]] || { echo "Missing $TOKEN. Create a device token in AdminOS → Settings → Connected devices, then:"; echo "  mkdir -p \"$SUPPORT\" && pbpaste > \"$TOKEN\" && chmod 600 \"$TOKEN\""; exit 1; }
chmod 600 "$TOKEN"

echo "Building sms-reader for macOS ($(uname -m))…"
ARCH="$(uname -m)"; [[ "$ARCH" == "x86_64" ]] && ARCH=amd64
docker run --rm -e GOPROXY="https://proxy.golang.org|direct" -e GOFLAGS=-buildvcs=false -e CGO_ENABLED=0 \
  -e GOOS=darwin -e GOARCH="$ARCH" -v "$HERE":/src -v aos-gomod:/go/pkg/mod -w /src golang:1.27 \
  go build -trimpath -ldflags="-s -w" -o bin/sms-reader .

mkdir -p "$SUPPORT/bin" "$LOGS"
install -m 0755 "$HERE/bin/sms-reader" "$BIN"
codesign --force --sign - --identifier "$LABEL" "$BIN"

if [[ "$BACKFILL" != "0" && ! -f "$SUPPORT/sms-reader.state" ]]; then
  echo "First run will import the last $BACKFILL days of bank SMS."
fi

cat > "$PLIST" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>$BIN</string>
    <string>-api</string><string>http://127.0.0.1:8080</string>
    <string>-backfill-days</string><string>$BACKFILL</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
  <key>ThrottleInterval</key><integer>60</integer>
  <key>StandardOutPath</key><string>$LOGS/sms-reader.log</string>
  <key>StandardErrorPath</key><string>$LOGS/sms-reader.log</string>
</dict>
</plist>
PLIST

launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"

cat <<MSG

Installed and started: $BIN
Logs: $LOGS/sms-reader.log  (counts only, never message text)

ONE MANUAL STEP: grant Full Disk Access to the reader (macOS requires you to do this):
  System Settings → Privacy & Security → Full Disk Access → "+" →
  press Cmd+Shift+G, paste:  $SUPPORT/bin  → select "sms-reader" → enable it.
Then restart it:  launchctl kickstart -k gui/$(id -u)/$LABEL
(Re-running this script re-signs the binary; macOS may ask you to re-enable the toggle.)
MSG
