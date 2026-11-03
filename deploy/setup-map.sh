#!/usr/bin/env bash
# Downloads everything the self-hosted map needs into ./map:
#   - vector tiles for your region (OpenStreetMap data via Protomaps), as one PMTiles file
#   - fonts and icons used by the dark map style
#
# Set BBOX to your own area (min_lon,min_lat,max_lon,max_lat; find one at https://boundingbox.klokantech.com,
# "CSV" format). Without it you get a small example area (central Portland, Oregon).
#
# Usage:  BBOX="-124.6,41.9,-116.4,46.3" ./setup-map.sh  # e.g. Oregon
#         BBOX="-125,24,-66,50" ./setup-map.sh          # continental US (much larger)
#         MAXZOOM=14 ./setup-map.sh            # smaller file, slightly less street detail
#
# Re-run any time to refresh the map data (e.g. every few months).
set -euo pipefail
cd "$(dirname "$0")"

BBOX="${BBOX:--122.76,45.46,-122.56,45.57}"   # min_lon,min_lat,max_lon,max_lat (example: central Portland, OR)
MAXZOOM="${MAXZOOM:-15}"
PMTILES_IMAGE="protomaps/go-pmtiles:v1.31.2"
MAP_DIR="$(pwd)/map"

need() { command -v "$1" >/dev/null 2>&1 || { echo "Missing required tool: $1" >&2; exit 1; }; }
need docker; need git; need curl

mkdir -p "$MAP_DIR"

echo "==> Fonts and sprites"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
git clone -q --depth 1 --filter=blob:none --sparse https://github.com/protomaps/basemaps-assets.git "$tmp/assets"
git -C "$tmp/assets" sparse-checkout set "fonts/Noto Sans Regular" "fonts/Noto Sans Medium" "fonts/Noto Sans Italic" sprites/v4
rm -rf "$MAP_DIR/fonts" "$MAP_DIR/sprites"
mkdir -p "$MAP_DIR/fonts" "$MAP_DIR/sprites"
cp -r "$tmp/assets/fonts/"* "$MAP_DIR/fonts/"
cp "$tmp/assets/sprites/v4/dark"* "$MAP_DIR/sprites/"

echo "==> Finding latest Protomaps build"
BUILD="${BUILD:-$(curl -fsSL https://build-metadata.protomaps.dev/builds.json | grep -o '"key": *"[0-9]\{8\}\.pmtiles"' | grep -o '[0-9]\{8\}\.pmtiles' | sort | tail -1)}"
if [ -z "$BUILD" ]; then
  echo "Could not determine the latest build. Set BUILD=YYYYMMDD.pmtiles (see https://maps.protomaps.com/builds/)" >&2
  exit 1
fi
echo "    using $BUILD, bbox $BBOX, maxzoom $MAXZOOM"

echo "==> Extracting tiles (this downloads only your region; can take several minutes)"
docker run --rm -v "$MAP_DIR:/out" "$PMTILES_IMAGE" extract \
  "https://build.protomaps.com/$BUILD" /out/tiles.pmtiles.new \
  --bbox="$BBOX" --maxzoom="$MAXZOOM" --download-threads=8
mv "$MAP_DIR/tiles.pmtiles.new" "$MAP_DIR/tiles.pmtiles"

echo "==> Done. Map size: $(du -sh "$MAP_DIR" | cut -f1)"
echo "    Restart not required; the server reads ./map directly."
