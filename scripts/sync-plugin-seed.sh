#!/usr/bin/env bash
# Refresh the plugin copy that ships inside the APK.
#
# app/src/main/assets/plugin-seed/dsh-qqbot-guard/ is generated: PluginSeed.java copies it into
# the harness profile on every boot. Never edit the asset copy by hand — edit plugins/qqbot-guard/
# and re-run this script, otherwise the two drift and the APK ships stale keywords.
#
# Usage: scripts/sync-plugin-seed.sh   (also accepts `--check` to fail when assets are stale)
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_dir="$repo/plugins/qqbot-guard"
asset_dir="$repo/app/src/main/assets/plugin-seed/dsh-qqbot-guard"
# Only what the loader needs at runtime; selfcheck.mjs and README stay in the repo.
files=(index.js package.json cordis.patch.yml)

check_only=false
if [[ "${1:-}" == "--check" ]]; then
  check_only=true
fi

if [[ ! -f "$source_dir/index.js" ]]; then
  echo "missing plugin sources: $source_dir" >&2
  exit 1
fi

if $check_only; then
  stale=0
  for file in "${files[@]}"; do
    if ! cmp -s "$source_dir/$file" "$asset_dir/$file"; then
      echo "stale or missing: app/src/main/assets/plugin-seed/dsh-qqbot-guard/$file" >&2
      stale=1
    fi
  done
  exit "$stale"
fi

mkdir -p "$asset_dir"
for file in "${files[@]}"; do
  cp -f "$source_dir/$file" "$asset_dir/$file"
  echo "synced $file"
done

# Keep the asset tree minimal: anything else would be copied into the profile as well.
comm -13 <(printf '%s\n' "${files[@]}" | sort) \
         <(find "$asset_dir" -maxdepth 1 -type f -printf '%f\n' | sort) |
  while read -r extra; do
    echo "removing unexpected asset: $extra" >&2
    rm -f "$asset_dir/$extra"
  done

echo "plugin seed ready: $asset_dir"
