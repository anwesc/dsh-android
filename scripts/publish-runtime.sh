#!/bin/sh
# Publish a runtime image as a GitHub Release asset (runtime-vN).
#
# Usage:
#   GITHUB_TOKEN=... scripts/publish-runtime.sh [image] [options]
#
# Options:
#   --tag <runtime-vN>     release tag, default runtime-v<LAYOUT_VERSION>
#   --repo <owner/name>    default: from the origin remote
#   --update-workflow      rewrite RUNTIME_TAG / RUNTIME_SHA256 in release.yml
#   --draft                create the release as a draft
#   --dry-run              print the plan without calling the API
#
# The image must be the gzip produced by scripts/pack-runtime.sh.
set -eu

image=app/src/main/assets/rootfs.bin
tag=
repo=
update_workflow=0
draft=false
dry=0

while [ $# -gt 0 ]; do
  case "$1" in
    --tag) tag=$2; shift 2 ;;
    --repo) repo=$2; shift 2 ;;
    --update-workflow) update_workflow=1; shift ;;
    --draft) draft=true; shift ;;
    --dry-run) dry=1; shift ;;
    --*) echo "unknown option: $1" >&2; exit 2 ;;
    *) image=$1; shift ;;
  esac
done

test -f "$image" || { echo "missing image: $image" >&2; exit 1; }
gzip -t "$image" 2>/dev/null || { echo "not a gzip archive: $image" >&2; exit 1; }

if [ -z "$tag" ]; then
  version=$(sed -n 's/.*LAYOUT_VERSION = "\([0-9]*\)".*/\1/p' app/src/main/java/dev/dsh/android/RuntimeInstaller.java | head -1)
  [ -n "$version" ] || { echo "cannot read LAYOUT_VERSION" >&2; exit 1; }
  tag="runtime-v$version"
fi

if [ -z "$repo" ]; then
  origin=$(git remote get-url origin 2>/dev/null || true)
  repo=$(printf '%s' "$origin" | sed -n 's#.*github.com[:/]\([^/]*/[^/.]*\).*#\1#p')
  [ -n "$repo" ] || { echo "cannot derive repo; pass --repo owner/name" >&2; exit 1; }
fi

if command -v sha256sum >/dev/null 2>&1; then
  sha=$(sha256sum "$image" | cut -d' ' -f1)
else
  sha=$(shasum -a 256 "$image" | cut -d' ' -f1)
fi
size=$(wc -c < "$image" | tr -d ' ')

echo "repo:   $repo"
echo "tag:    $tag"
echo "image:  $image ($size bytes)"
echo "sha256: $sha"

if [ "$dry" = 1 ]; then
  echo "dry run: would create/reuse release $tag and upload rootfs.bin"
  exit 0
fi

: "${GITHUB_TOKEN:?set GITHUB_TOKEN to a token with repo scope}"
api=https://api.github.com/repos/$repo

release_id=$(curl -fsS -H "Authorization: token $GITHUB_TOKEN" "$api/releases/tags/$tag" 2>/dev/null | sed -n 's/.*"id": \([0-9]*\).*/\1/p' | head -1 || true)

if [ -z "$release_id" ]; then
  echo "creating release $tag"
  body=$(printf '{"tag_name":"%s","name":"Runtime image %s","draft":%s,"prerelease":true,"body":"arm64 rootfs used by the Android build. Regenerate with scripts/pack-runtime.sh."}' "$tag" "$tag" "$draft")
  release_id=$(curl -fsS -X POST "$api/releases" \
    -H "Authorization: token $GITHUB_TOKEN" \
    -H 'Accept: application/vnd.github+json' \
    -H 'Content-Type: application/json' \
    --data-binary "$body" \
    | sed -n 's/.*"id": \([0-9]*\).*/\1/p' | head -1)
  [ -n "$release_id" ] || { echo "create release failed" >&2; exit 1; }
fi

echo "uploading $size bytes to release $release_id"
curl -fS --progress-bar -X POST \
  "https://uploads.github.com/repos/$repo/releases/$release_id/assets?name=rootfs.bin" \
  -H "Authorization: token $GITHUB_TOKEN" \
  -H 'Content-Type: application/octet-stream' \
  --data-binary @"$image" >/dev/null

echo "uploaded: https://github.com/$repo/releases/download/$tag/rootfs.bin"

if [ "$update_workflow" = 1 ]; then
  workflow=.github/workflows/release.yml
  sed -i.bak "s#^  RUNTIME_TAG: .*#  RUNTIME_TAG: $tag#" "$workflow"
  sed -i.bak "s#^  RUNTIME_SHA256: .*#  RUNTIME_SHA256: $sha#" "$workflow"
  rm -f "$workflow.bak"
  echo "updated $workflow"
fi
