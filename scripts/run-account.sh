#!/usr/bin/env bash
#
# Launch the Microbot debug client logged in as a specific Jagex account,
# without going through the Microbot Launcher / Hub.
#
# How it works: the launcher selects a Jagex account by writing that
# character's OAuth session into ~/.runelite/credentials.properties. This
# script does the same thing by copying a saved per-account credentials file
# into place, then starts the debug client (Gradle `runDebug` task, which runs
# net.runelite.client.Microbot with the plugins from Microbot.java registered).
#
# Per-account credential files live OUTSIDE this repo (they contain live
# session tokens - never commit them):
#     ~/.microbot/credentials/<AccountDisplayName>.properties
#
# Regenerate/refresh them from the launcher's account list with:
#     scripts/refresh-account-credentials.sh
#
# NOTE: Jagex session IDs expire. When login stops working, open the launcher
# once to re-authenticate, then re-run refresh-account-credentials.sh.
#
# Usage: scripts/run-account.sh <AccountDisplayName>
set -euo pipefail

ACCOUNT="${1:-}"
if [[ -z "$ACCOUNT" ]]; then
    echo "Usage: $0 <AccountDisplayName> [extra gradle args...]" >&2
    echo "  e.g. $0 WeMinus -PgcLog   # enable GC/safepoint freeze logging" >&2
    exit 1
fi
shift  # remaining args are forwarded to Gradle (e.g. -PgcLog)

CRED_SRC="$HOME/.microbot/credentials/${ACCOUNT}.properties"
CRED_DST="$HOME/.runelite/credentials.properties"

if [[ ! -f "$CRED_SRC" ]]; then
    echo "No saved credentials for '$ACCOUNT' at $CRED_SRC" >&2
    echo "Run scripts/refresh-account-credentials.sh first (accounts come from the launcher)." >&2
    exit 1
fi

mkdir -p "$(dirname "$CRED_DST")"
cp "$CRED_SRC" "$CRED_DST"
echo "Selected account: $ACCOUNT (credentials.properties updated)"

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"
exec ./gradlew runDebug --args='--debug' "$@"
