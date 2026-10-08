#!/usr/bin/env sh
# Renders every Graphviz source in this folder into the PNG mcav-docs/mcv2.md shows, in mcav-docs/images/mcv2. The style is the
# one of draw.io's default palette: yellow for data, blue for the server, green for the client, red for decisions,
# black Helvetica text (Liberation Sans on Linux) on white. Made with Graphviz 14.1.2; set DOT to use another dot. The
# charts are drawn by charts.py.
set -eu
DOT="${DOT:-dot}"
cd "$(dirname "$0")"
for source in *.dot; do
  "$DOT" -Gdpi=110 -Tpng "$source" -o "../../../mcav-docs/images/mcv2/${source%.dot}.png"
done
