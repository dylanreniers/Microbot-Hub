#!/usr/bin/env bash
#
# Regenerate the per-account credentials files used by scripts/run-account.sh
# from the Microbot Launcher's account list (~/.microbot/accounts.json).
#
# This mirrors what the launcher's overwrite-credential-properties handler
# writes when you click "Play" on an account. Run this after (re)authenticating
# an account in the launcher so the saved session token is fresh.
#
# Output: ~/.microbot/credentials/<DisplayName>.properties (chmod 600, not in repo)
set -euo pipefail

python3 - <<'PY'
import json, os
from datetime import datetime

home = os.path.expanduser('~')
accts_path = os.path.join(home, '.microbot', 'accounts.json')
if not os.path.exists(accts_path):
    raise SystemExit(f"No accounts file at {accts_path} - open the launcher and add accounts first.")

accts = json.load(open(accts_path))
outdir = os.path.join(home, '.microbot', 'credentials')
os.makedirs(outdir, exist_ok=True)

for a in accts:
    name = a.get('displayName')
    if not name or not a.get('accountId') or not a.get('sessionId'):
        print("skipping account with missing fields:", a.get('displayName'))
        continue
    content = ("#Do not share this file with anyone\n"
               f"#{datetime.now().strftime('%a %b %d %H:%M:%S %Z %Y')}\n"
               f"JX_CHARACTER_ID={a['accountId']}\n"
               f"JX_SESSION_ID={a['sessionId']}\n"
               f"JX_REFRESH_TOKEN=\n"
               f"JX_DISPLAY_NAME={name}\n"
               f"JX_ACCESS_TOKEN=\n")
    p = os.path.join(outdir, f"{name}.properties")
    with open(p, 'w') as f:
        f.write(content)
    os.chmod(p, 0o600)
    print("wrote", p)
PY
