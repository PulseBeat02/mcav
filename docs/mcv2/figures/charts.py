"""Draws the charts of the MCV2 chapter from the committed measurements in ../data.

    python docs/mcv2/figures/charts.py

codecs.png: rate against VMAF mean for MCV2, H.264, VP9 and AV1 on the 1080p30 proxy and on real gameplay, from
data/codec_curves.json. Needs numpy and matplotlib (docs/requirements.txt).
"""

import json
import logging
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

HERE = Path(__file__).resolve().parent
DATA = HERE.parent / "data" / "codec_curves.json"

# the site's diagrams: grey lines and text on white, Arial (Liberation Sans on Linux)
GREY = "#555555"
LIGHT = "#dddddd"
FONT = ["Liberation Sans", "Arial", "DejaVu Sans"]

# Okabe-Ito colours, which stay apart for colour-blind readers
STYLE = {
    "mcv2_map": dict(color="#D55E00", linestyle="-", marker="o", label="MCV2, map rate (ship search)"),
    "mcv2_zlib": dict(color="#D55E00", linestyle="--", marker="o", markerfacecolor="white",
                      label="MCV2, after the game's zlib"),
    "x264": dict(color="#0072B2", linestyle="-", marker="s", label="H.264 (x264 veryslow)"),
    "vp9": dict(color="#009E73", linestyle="-", marker="^", label="VP9 (libvpx, good, cpu-used 0)"),
    "av1": dict(color="#CC79A7", linestyle="-", marker="D", label="AV1 (libaom, cpu-used 6)"),
}

PANELS = [
    ("proxy30", "1080p30 Minecraft proxy (procedural, 30 frames)"),
    ("gameplay30", "Real Minecraft gameplay (Xiph MINECRAFT, 60 frames at 30 fps)"),
]

MARKED = {65.255994022: "ship", 137.730758207: "low_bandwidth"}


def curve(points, x, y="vmaf_mean"):
    points = sorted(points, key=lambda p: p[x])
    return [p[x] for p in points], [p[y] for p in points]


def draw_codecs(data):
    plt.rcParams.update({"font.family": FONT, "font.size": 10, "axes.edgecolor": GREY, "axes.labelcolor": GREY,
                         "xtick.color": GREY, "ytick.color": GREY, "text.color": "#333333"})
    figure, axes = plt.subplots(1, 2, figsize=(12.5, 5.2), sharey=True)
    for axis, (source, title) in zip(axes, PANELS):
        mcv2 = [p for p in data["mcv2"] if p["source"] == source]
        for key, rate in (("mcv2_map", "map_mbps"), ("mcv2_zlib", "zlib_mbps")):
            xs, ys = curve(mcv2, rate)
            axis.plot(xs, ys, markersize=4, linewidth=1.6, **STYLE[key])
        for codec in ("x264", "vp9", "av1"):
            points = [p for p in data["codecs"] if p["source"] == source and p["codec"] == codec]
            xs, ys = curve(points, "container_mbps")
            axis.plot(xs, ys, markersize=4, linewidth=1.4, **STYLE[codec])
        for point in mcv2:
            name = MARKED.get(point["lambda"])
            if name is None:
                continue
            # the map-rate point is labelled below and to the right, the zlib point above and to the left
            for rate, offset, align in (("map_mbps", (7, -11), "left"), ("zlib_mbps", (-7, 5), "right")):
                axis.plot(point[rate], point["vmaf_mean"], marker="*", markersize=11, color="#D55E00",
                          markeredgecolor="white", linestyle="none")
                axis.annotate(name, (point[rate], point["vmaf_mean"]), textcoords="offset points", xytext=offset,
                              ha=align, fontsize=8, color="#D55E00")
        axis.set_xscale("log")
        axis.set_title(title, fontsize=10.5, color="#333333")
        axis.set_xlabel("Rate (Mbit/s, log scale): MCV2 map or zlib rate, others container rate")
        axis.grid(True, which="both", color=LIGHT, linewidth=0.6)
        axis.set_ylim(20, 101)
        for side in ("top", "right"):
            axis.spines[side].set_visible(False)
    axes[0].set_ylabel("VMAF mean")
    handles, labels = axes[0].get_legend_handles_labels()
    figure.legend(handles, labels, loc="lower center", ncol=5, frameon=False, fontsize=9)
    figure.tight_layout(rect=(0, 0.07, 1, 1))
    figure.savefig(HERE / "codecs.png", dpi=110, facecolor="white")
    plt.close(figure)


def main():
    # the fonts missing on a platform are skipped; say nothing about them
    logging.getLogger("matplotlib.font_manager").setLevel(logging.ERROR)
    data = json.loads(DATA.read_text())
    draw_codecs(data)


if __name__ == "__main__":
    main()
