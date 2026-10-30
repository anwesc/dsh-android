#!/usr/bin/env bash
# Refresh the plugin copy that ships inside the APK.
#
# qqbot-guard lives in its own repository (https://github.com/anwesc/dsh-qqbot-guard) and is
# published to npm; this repo only carries the build-time seed. The pinned version below is the
# single thing that decides what the APK ships — bump it, re-run this script, commit the asset.
#
# app/src/main/assets/plugin-seed/dsh-qqbot-guard/ is generated: PluginSeed.java copies it into
# the harness profile on first boot / when it is missing or older. Never edit the asset copy by
# hand, otherwise it drifts from the published package.
#
# Usage: scripts/sync-plugin-seed.sh   (also accepts `--check` to fail when assets are stale)
#
# Sources, in order:
#   1. $DSH_QQBOT_GUARD_SRC — path to a local checkout of the plugin repo (offline builds)
#   2. npm registry        — `npm pack dsh-qqbot-guard@<pinned>`
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
asset_dir="$repo/app/src/main/assets/plugin-seed/dsh-qqbot-guard"
version_file="$repo/scripts/plugin-seed.version"
# Only what the loader needs at runtime; tests and README stay in the plugin repo.
files=(index.js package.json cordis.patch.yml)

check_only=false
if [[ "${1:-}" == "--check" ]]; then
  check_only=true
fi

spec="$(tr -d '[:space:]' < "$version_file")"
if [[ ! "$spec" =~ ^dsh-qqbot-guard@[0-9]+\.[0-9]+\.[0-9]+ ]]; then
  echo "scripts/plugin-seed.version must hold a pinned package spec like dsh-qqbot-guard@1.0.0 (got: '$spec')" >&2
  exit 1
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

if [[ -n "${DSH_QQBOT_GUARD_SRC:-}" ]]; then
  source_dir="$DSH_QQBOT_GUARD_SRC"
  if [[ ! -f "$source_dir/index.js" ]]; then
    echo "DSH_QQBOT_GUARD_SRC does not look like the plugin repo: $source_dir" >&2
    exit 1
  fi
  echo "source: local checkout $source_dir"
else
  echo "source: npm $spec"
  if ! npm pack "$spec" --pack-destination "$tmp" --silent >/dev/null 2>&1; then
    echo "npm pack $spec failed (not published yet, or offline); set DSH_QQBOT_GUARD_SRC to a plugin checkout" >&2
    exit 1
  fi
  mkdir -p "$tmp/unpacked"
  tar -xzf "$tmp"/dsh-qqbot-guard-*.tgz -C "$tmp/unpacked"
  source_dir="$tmp/unpacked/package"
fi

if $check_only; then
  stale=0
  for file in "${files[@]}"; do
    if ! cmp -s "$source_dir/$file" "$asset_dir/$file"; then
      echo "stale or missing: app/src/main/assets/plugin-seed/dsh-qqbot-guard/$file (pinned $spec)" >&2
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

echo "plugin seed ready: $asset_dir ($spec)"
