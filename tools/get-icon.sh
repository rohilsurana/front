#!/usr/bin/env bash
# get-icon.sh — Download a Material Symbols icon and convert to Android vector drawable
#
# Usage:
#   bash tools/get-icon.sh <icon_name> [style]
#
# Styles:
#   rounded   (default) — soft, friendly
#   outlined  — clean lines, no fill
#   sharp     — angular, geometric
#
# Output:
#   app/src/main/res/drawable/ic_<icon_name>.xml
#
# Browse icons at: https://fonts.google.com/icons
# Requires: curl, npx (svg2vectordrawable — no install needed, npx pulls it)
#
# Examples:
#   bash tools/get-icon.sh alarm
#   bash tools/get-icon.sh bar_chart rounded
#   bash tools/get-icon.sh sync outlined

set -e

ICON_NAME="${1}"
STYLE="${2:-rounded}"

if [ -z "$ICON_NAME" ]; then
  echo "Usage: bash tools/get-icon.sh <icon_name> [rounded|outlined|sharp]"
  echo ""
  echo "Examples:"
  echo "  bash tools/get-icon.sh alarm"
  echo "  bash tools/get-icon.sh bar_chart rounded"
  echo "  bash tools/get-icon.sh sync outlined"
  echo ""
  echo "Browse icons: https://fonts.google.com/icons"
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
OUTPUT_DIR="${PROJECT_DIR}/app/src/main/res/drawable"
TMP_SVG="/tmp/ic_${ICON_NAME}_${STYLE}.svg"
OUTPUT_XML="${OUTPUT_DIR}/ic_${ICON_NAME}.xml"

CDN_URL="https://fonts.gstatic.com/s/i/short-term/release/materialsymbols${STYLE}/${ICON_NAME}/default/24px.svg"

echo "⬇  Downloading '${ICON_NAME}' (${STYLE})..."
if ! curl -sf "$CDN_URL" -o "$TMP_SVG"; then
  echo ""
  echo "❌ Download failed — icon '${ICON_NAME}' not found in style '${STYLE}'"
  echo "   Check the exact name at: https://fonts.google.com/icons"
  echo "   Names are lowercase with underscores (e.g. bar_chart, alarm_on, check_circle)"
  exit 1
fi

echo "🔄 Converting SVG → Android vector drawable..."
npx svg2vectordrawable -i "$TMP_SVG" -o "$OUTPUT_XML" 2>/dev/null

# svg2vectordrawable omits fillColor — Android renders paths as transparent without it.
# Inject android:fillColor="#000000" into every <path element so tinting works correctly.
sed -i 's|<path |<path\n        android:fillColor="#000000"\n        |g' "$OUTPUT_XML"

rm -f "$TMP_SVG"

echo ""
echo "✅ Done: app/src/main/res/drawable/ic_${ICON_NAME}.xml"
echo ""
echo "   Use in XML layout:"
echo "     <ImageView"
echo "         android:layout_width=\"24dp\""
echo "         android:layout_height=\"24dp\""
echo "         android:src=\"@drawable/ic_${ICON_NAME}\""
echo "         android:tint=\"@color/colorAccent\" />"
