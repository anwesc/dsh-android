#!/bin/sh
# Print the keystore as a single base64 line for the KEYSTORE_BASE64 secret.
# Usage: scripts/keystore-base64.sh [dsh-release.jks]
set -eu
key=${1:-dsh-release.jks}
test -f "$key" || { echo "missing $key" >&2; exit 1; }
base64 -w0 "$key"
echo