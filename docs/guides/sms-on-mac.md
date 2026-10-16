# Bank SMS → AdminOS (iPhone + Mac)

Your iPhone copies SMS to your Mac. A small reader on the Mac picks up **bank** SMS and sends them to
AdminOS running on the same Mac. Nothing is exposed to the network, personal messages never leave the
Mac, and SMS that arrive while the Mac is asleep are picked up when it wakes (decision D-35).

Time: about 10 minutes.

## 1. iPhone: forward SMS to your Mac
1. On the Mac, open **Messages** and sign in with the **same Apple ID** as your iPhone.
2. On the iPhone: **Settings → Apps → Messages → Text Message Forwarding** and turn on your Mac.
   On older iOS versions it's **Settings → Messages → Text Message Forwarding**.
3. Check it works: the next bank SMS should also appear in Messages on the Mac.

## 2. AdminOS: create a device token
1. Start AdminOS (`docker compose --profile full up -d`) and sign in at <http://localhost:3000>.
2. Go to **Settings → Connected devices**, keep the name "My Mac – SMS reader", click **Connect device**, then **Copy token**.
   The token can only *submit* SMS; it can't read your data. It's shown once.
3. In Terminal, save it (paste from the clipboard; the file is readable only by you):
   ```bash
   mkdir -p ~/Library/Application\ Support/AdminOS
   pbpaste > ~/Library/Application\ Support/AdminOS/device-token
   chmod 600 ~/Library/Application\ Support/AdminOS/device-token
   ```

## 3. Install the reader
From the repo root:
```bash
tools/sms-reader/scripts/install.sh                     # only new SMS from now on
# or, to also import past SMS once (e.g. the last 6 months):
tools/sms-reader/scripts/install.sh --backfill-days 180
```
This builds the reader, installs it under `~/Library/Application Support/AdminOS/bin/`, and starts it at login.

## 4. Grant Full Disk Access (macOS requires you to do this yourself)
The Messages database is protected, so the reader needs permission to read it (read-only).
1. **System Settings → Privacy & Security → Full Disk Access → "+"**
2. Press **⌘⇧G**, paste `~/Library/Application Support/AdminOS/bin`, select **sms-reader**, and turn it on.
3. Restart the reader: `launchctl kickstart -k gui/$(id -u)/dev.adminos.sms-reader`

The permission applies to this one small program only, not to Terminal or anything else.

## 5. Check it's working
```bash
tail -f ~/Library/Logs/AdminOS/sms-reader.log
```
You should see `sms-reader started` and, after the next bank SMS, `forwarded 1 SMS: 1 new …`.
The log only ever contains counts, never message text. The transaction appears in AdminOS within a few seconds.

## What it does and doesn't do
- **Reads only:** incoming SMS and RCS from **business senders** (headers like `AX-HDFCBK-S`). Phone numbers, iMessages and your own messages are skipped inside the reader.
- **Opens the database read-only**, and only talks to `127.0.0.1`. It refuses any other address.
- **The server** parses the text in memory and keeps only amount, direction, merchant, account last 4 digits and payment method. The text is never stored or logged.
- **Ignored by the parser:** OTPs, promotions, declined payments, payment requests, reminders and balance-only alerts.

## Troubleshooting
| Log says | Fix |
|---|---|
| `find starting point (does this binary have Full Disk Access?)` | Step 4, then restart |
| `device token … readable by other users` | `chmod 600 ~/Library/Application\ Support/AdminOS/device-token` |
| `API rejected the device token` | The token was revoked or expired. Create a new one (step 2) and restart |
| `post to API: … connection refused` | AdminOS isn't running. The reader retries; nothing is lost |

After re-running `install.sh`, macOS may ask you to re-enable the Full Disk Access toggle (the binary changed).

## Remove
```bash
tools/sms-reader/scripts/uninstall.sh            # keeps token + position
tools/sms-reader/scripts/uninstall.sh --purge    # also deletes them
```
Then revoke the device in **Settings → Connected devices** and remove it from Full Disk Access.

> The parsers were built against **sample** formats from HDFC, SBI, ICICI, Axis and Kotak. If a real bank SMS
> shows up as *ignored* or with a wrong merchant, note the bank. Don't share the message text; we'll fix it locally (AOS-211).
