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
prune=${PRUNE:-1}

test -d "$rootfs" || { echo "not a directory: $rootfs" >&2; exit 1; }

# Lossless prune: nothing here is read at runtime, but it removes ~15% of the
# unpacked tree (type declarations, source maps, docs, tests, other platforms).
if [ "$prune" = 1 ]; then
  echo "pruning $rootfs"
  find "$rootfs" -type f \( -name '*.d.ts' -o -name '*.map' \
    -o -name '*.pdb' \) -delete
  find "$rootfs" -depth -type d \( -name test -o -name tests \
    -o -name __tests__ -o -name docs \) -exec rm -rf {} +
  find "$rootfs" -depth -type d \( -name 'win32-*' -o -name 'darwin-*' \) \
    -exec rm -rf {} +
fi

# gzip -9 costs a minute at build time and saves ~15% of the download.
tar --hard-dereference -cf - -C "$rootfs" . | gzip -9 > "$output"
ls -l "$output"
