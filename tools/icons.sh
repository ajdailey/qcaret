#!/bin/sh
# Renders the icon set from qcaret-dist/src/icons/qcaret.svg and qcaret-light.svg.
# Needs ImageMagick 6 (convert) and Python 3 with Pillow; the build uses the
# committed outputs and needs neither.
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
icons="$root/qcaret-dist/src/icons"
png="$root/qcaret-ui-swt/src/main/resources/io/github/ajdailey/qcaret/ui/swt/icons"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

render() {
    # -strip and the excluded chunks keep dates and tool names out of the PNG.
    convert -background none -density 384 "$1" -resize "$2x$2" -strip \
        -define png:exclude-chunks=date,time,tEXt,zTXt,iTXt "PNG32:$3"
}

for size in 16 24 32 48 64 128 256; do
    render "$icons/qcaret.svg" "$size" "$png/qcaret-$size.png"
    render "$icons/qcaret-light.svg" "$size" "$png/qcaret-light-$size.png"
done

convert "$png/qcaret-16.png" "$png/qcaret-24.png" "$png/qcaret-32.png" \
    "$png/qcaret-48.png" "$png/qcaret-64.png" "$png/qcaret-128.png" \
    "$png/qcaret-256.png" "$icons/qcaret.ico"

render "$icons/qcaret.svg" 1024 "$work/qcaret-1024.png"
python3 -I - "$work/qcaret-1024.png" "$icons/QCaret.icns" <<'PY'
import sys
from PIL import Image
Image.open(sys.argv[1]).save(sys.argv[2], format="ICNS")
PY
