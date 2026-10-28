#!/bin/sh
# Pack an arm64 rootfs into the gzip image the app unpacks on first launch.
#
# Usage: scripts/pack-runtime.sh <rootfs-dir> [output]
#   <rootfs-dir>  directory containing the finished Ubuntu arm64 tree
#   [output]      defaults to app/src/main/assets/rootfs.bin
#
# The tree must already contain Node and the harness:
#   /opt/nodejs/bin/node, /usr/local/bin/dsh, /root/.dsh/profiles/web
#
# --hard-dereference is required: Android forbids hard links, so every link is
# materialised as a regular file before the image reaches the device.
set -eu

rootfs=${1:?usage: pack-runtime.sh <rootfs-dir> [output]}
output=${2:-app/src/main/assets/rootfs.bin}

test -d "$rootfs" || { echo "not a directory: $rootfs" >&2; exit 1; }

tar --hard-dereference -cf - -C "$rootfs" . | gzip -1 > "$output"
ls -l "$output"
