#!/usr/bin/env bash
# Fetch the Margin of Victory web edition at the pinned commit and build its
# bundle into ./dist, using the Vite mode for the requested distribution
# channel. Never vendored: the web repo stays the source of truth.
#
# Usage: ./scripts/fetch-web.sh <channel>
#   channel = web | desktop-direct | steam | android | ios
set -euo pipefail

channel="${1:-desktop-direct}"
case "$channel" in
  web|desktop-direct|steam|android|ios) ;;
  *) echo "unknown channel: $channel" >&2; exit 64 ;;
esac

root="$(cd "$(dirname "$0")/.." && pwd)"
pin="$(tr -d '[:space:]' < "$root/web.pin")"
web="$root/web"

if [ ! -d "$web/.git" ]; then
  git clone --quiet https://github.com/Egg3901/MOVGame.git "$web"
fi
git -C "$web" fetch --quiet origin
git -C "$web" checkout --quiet "$pin"

head_sha="$(git -C "$web" rev-parse HEAD)"
if [ "$head_sha" != "$pin" ]; then
  echo "web.pin ($pin) does not match checked-out web commit ($head_sha) — refusing to build" >&2
  exit 65
fi

if [ "$channel" = "web" ]; then
  ( cd "$web" && npm ci && npm run build )
else
  ( cd "$web" && npm ci && npm run build -- --mode "$channel" )
fi

rm -rf "$root/dist"
cp -R "$web/dist" "$root/dist"
echo "web bundle $pin (mode=$channel) -> $root/dist"
