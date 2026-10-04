#!/usr/bin/env bash
#
# Hot-reload a custom plugin into the running Microbot debug client WITHOUT
# restarting the client or logging back in.
#
# How it works: the Agent Server plugin (running in your debug client on
# 127.0.0.1:8081) exposes a dynamic plugin loader. This script compiles the
# plugin's source in-process against the live client classpath, loads it in a
# fresh classloader, injects it via Guice, and starts it as a plugin.
#   - First call  -> POST /scripts/deploy   (compile + load + start)
#   - Later calls -> POST /scripts/deploy/reload (recompile in place + restart)
#
# IMPORTANT: the dynamic loader REFUSES any plugin whose class name or
# @PluginDescriptor name is already registered natively. So the plugin you
# reload must NOT be listed in debugPlugins in
#   src/test/java/net/runelite/client/Microbot.java
# Remove it from that array (keep AgentServerPlugin), start the client, log in
# once, then drive it entirely through this script.
#
# CAVEAT: only the plugin's own package dir is recompiled. Shared classes
# (custom/actions/, PluginConstants) resolve from the live classpath but are
# NOT hot-reloaded -- editing those still needs a client restart.
#
# Usage:
#   scripts/reload-plugin.sh <pluginDir>            # e.g. fletching
#   scripts/reload-plugin.sh <pluginDir> --name foo # override deployment name
#   scripts/reload-plugin.sh /abs/path/to/srcdir    # arbitrary source dir
#   scripts/reload-plugin.sh <pluginDir> --undeploy # stop & unload
#
set -euo pipefail

HOST="${MICROBOT_HOST:-127.0.0.1}"
PORT="${MICROBOT_PORT:-8081}"
BASE="http://${HOST}:${PORT}"

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CUSTOM_DIR="${PROJECT_DIR}/src/main/java/net/runelite/client/plugins/custom"

# ── Agent token (same resolution as microbot-cli) ─────────────────────────
TOKEN_FILE="${MICROBOT_TOKEN_FILE:-${HOME}/.runelite/.agent-token}"
LEGACY_TOKEN_FILES=(
    "${HOME}/.runelite/microbot/agent-token"
    "${HOME}/.microbot/agent-token"
)
AGENT_TOKEN="${MICROBOT_TOKEN:-}"
if [[ -z "$AGENT_TOKEN" ]] && [[ -r "$TOKEN_FILE" ]]; then
    AGENT_TOKEN="$(tr -d '\r\n' < "$TOKEN_FILE" 2>/dev/null || true)"
fi
if [[ -z "$AGENT_TOKEN" ]]; then
    for legacy in "${LEGACY_TOKEN_FILES[@]}"; do
        if [[ -r "$legacy" ]]; then
            AGENT_TOKEN="$(tr -d '\r\n' < "$legacy" 2>/dev/null || true)"
            [[ -n "$AGENT_TOKEN" ]] && break
        fi
    done
fi
AUTH=()
[[ -n "$AGENT_TOKEN" ]] && AUTH=(-H "X-Agent-Token: $AGENT_TOKEN")

usage() {
    sed -n '2,33p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 1
}

[[ $# -lt 1 ]] && usage

TARGET="$1"; shift
NAME=""
UNDEPLOY=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --name) NAME="$2"; shift 2 ;;
        --undeploy) UNDEPLOY=1; shift ;;
        -h|--help) usage ;;
        *) echo "Unknown option: $1" >&2; usage ;;
    esac
done

# Resolve source dir: absolute/relative path if it exists, else under custom/.
if [[ -d "$TARGET" ]]; then
    SRC_DIR="$(cd "$TARGET" && pwd)"
elif [[ -d "${CUSTOM_DIR}/${TARGET}" ]]; then
    SRC_DIR="${CUSTOM_DIR}/${TARGET}"
else
    echo "Source dir not found: '$TARGET'" >&2
    echo "Looked for '$TARGET' and '${CUSTOM_DIR}/${TARGET}'." >&2
    echo "Available custom plugins:" >&2
    ls "$CUSTOM_DIR" 2>/dev/null | sed 's/^/  /' >&2
    exit 1
fi

# Deployment name defaults to the leaf dir name, sanitized to [A-Za-z0-9_-].
if [[ -z "$NAME" ]]; then
    NAME="$(basename "$SRC_DIR")"
    NAME="${NAME//[^a-zA-Z0-9_-]/-}"
fi

post() { # $1=endpoint $2=json-body
    curl -s --fail-with-body "${AUTH[@]}" -X POST \
        -H "Content-Type: application/json" -d "$2" "${BASE}$1"
}

check_server() {
    curl -s --max-time 3 "${AUTH[@]}" "${BASE}/scripts/deploy" 2>/dev/null || {
        echo "Cannot reach agent server at ${BASE}." >&2
        echo "Is the debug client running with AgentServerPlugin enabled?" >&2
        exit 1
    }
}

is_deployed() { # grep the deployment list for "name": "<NAME>"
    curl -s "${AUTH[@]}" "${BASE}/scripts/deploy" 2>/dev/null \
        | grep -q "\"name\"[[:space:]]*:[[:space:]]*\"${NAME}\""
}

LIST_JSON="$(check_server)"

if [[ "$UNDEPLOY" -eq 1 ]]; then
    echo "Undeploying '${NAME}'..."
    post /scripts/deploy/undeploy "{\"name\":\"${NAME}\"}"
    echo
    exit 0
fi

if is_deployed; then
    echo "Reloading '${NAME}' from ${SRC_DIR} ..."
    post /scripts/deploy/reload "{\"name\":\"${NAME}\"}"
else
    echo "Deploying '${NAME}' from ${SRC_DIR} (registered, not started — enable it in the plugin list) ..."
    post /scripts/deploy "{\"name\":\"${NAME}\",\"sourcePath\":\"${SRC_DIR}\",\"start\":false}"
fi
echo
