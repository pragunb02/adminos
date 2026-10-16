#!/usr/bin/env bash
# Stops and removes the AdminOS SMS reader. Keeps the token and state unless --purge.
set -euo pipefail
LABEL="dev.adminos.sms-reader"
SUPPORT="$HOME/Library/Application Support/AdminOS"
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
rm -f "$HOME/Library/LaunchAgents/$LABEL.plist" "$SUPPORT/bin/sms-reader"
if [[ "${1:-}" == "--purge" ]]; then rm -f "$SUPPORT/device-token" "$SUPPORT/sms-reader.state"; fi
echo "SMS reader removed. Also revoke its token in AdminOS → Settings → Connected devices,"
echo "and remove it from System Settings → Privacy & Security → Full Disk Access."
