#!/usr/bin/env sh
# Renders every Graphviz source in this folder into the PNG the documentation shows, in the style of the site's other
# diagrams (library/pipeline.png): grey boxes, the source in yellow, Arial (Liberation Sans on Linux). The PNGs of the
# MCV2 chapter were made with Graphviz 14.1.2; set DOT to use another dot. The charts are made by charts.py.
set -eu
DOT="${DOT:-dot}"
cd "$(dirname "$0")"
for source in *.dot; do
  "$DOT" -Gdpi=96 -Tpng "$source" -o "${source%.dot}.png"
done
