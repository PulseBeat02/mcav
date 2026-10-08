"""Draws the charts of docs/mcv2.md from the measurements in tools/mcv2/data, and prints the tables the article quotes.

    python tools/mcv2/figures/charts.py            # draws docs/images/mcv2/codecs.png and features.png
    python tools/mcv2/figures/charts.py --tables   # also prints the article's tables in Markdown

Every rate is what crosses the network: MCV2's map packets after Minecraft's zlib (the zlib rate of
tools/mcv2/Mcv2Bench.java), and the encoded file of H.264, VP9 and AV1. codecs.png is rate against VMAF on the two
sources of data/codec_curves.json; features.png is the extra rate MCV2 needs without each of its features, the BD-rate
of each curve of data/ablation.json against its baseline. The tables add what the features MCV2 no longer has were
worth when they were measured (data/ablation.json's "removed"). Needs numpy and matplotlib (docs/requirements.txt).
"""

import argparse
import json
import logging
import math
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.ticker import FixedLocator, FuncFormatter, NullLocator  # noqa: E402

HERE = Path(__file__).resolve().parent
TOOLS = HERE.parent
DATA = TOOLS / "data"
IMAGES = TOOLS.parent.parent / "docs" / "images" / "mcv2"

sys.path.insert(0, str(TOOLS))

from bd_rate import bd_rate  # noqa: E402

# a white card with dark ink, readable on the light docs site and on the dark blog alike
SURFACE = "#ffffff"
INK = "#0b0b0b"
SECONDARY = "#52514e"
GRID = "#e4e3df"
FONT = ["Liberation Sans", "Arial", "Helvetica", "DejaVu Sans"]

# categorical slots 1 to 4 in their fixed order, checked for colour-blind readers; the two lightest are labelled
# directly on the chart as well, so no line is told apart by its colour alone
CODECS = [
    ("mcv2", "MCV2", "#2a78d6", "o"),
    ("x264", "H.264", "#eb6834", "s"),
    ("vp9", "VP9", "#1baf7a", "^"),
    ("av1", "AV1", "#eda100", "D"),
]

SOURCES = [
    ("proxy30", "Minecraft proxy, 1080p30"),
    ("gameplay30", "Minecraft gameplay, 1080p30"),
]

SOURCE_COLOURS = {"proxy30": "#2a78d6", "gameplay30": "#eb6834"}

LEVELS = (70, 75, 80, 85, 90)

TICKS = (0.1, 0.2, 0.5, 1, 2, 5, 10, 20, 50)


def style():
    plt.rcParams.update({
        "font.family": FONT,
        "font.size": 10.5,
        "axes.edgecolor": SECONDARY,
        "axes.labelcolor": INK,
        "xtick.color": SECONDARY,
        "ytick.color": SECONDARY,
        "text.color": INK,
        "axes.facecolor": SURFACE,
        "figure.facecolor": SURFACE,
    })


def curve(data, source, codec):
    """The (rate, VMAF mean) points of one codec on one source, by rate."""
    if codec == "mcv2":
        points = [(p["zlib_mbps"], p["vmaf_mean"]) for p in data["mcv2"] if p["source"] == source]
    else:
        points = [
            (p["container_mbps"], p["vmaf_mean"]) for p in data["codecs"]
            if p["source"] == source and p["codec"] == codec
        ]
    return sorted(points)


def rate_at(points, level):
    """The rate a curve needs for a VMAF mean, log-linear between the measured points; None outside them."""
    by_quality = sorted(points, key=lambda point: point[1])
    for (low_rate, low), (high_rate, high) in zip(by_quality, by_quality[1:]):
        if low <= level <= high and high > low:
            share = (level - low) / (high - low)
            return math.exp(math.log(low_rate) + share * (math.log(high_rate) - math.log(low_rate)))
    return None


def draw_codecs(data):
    # one panel above the other: the chart keeps its size in a page column
    figure, axes = plt.subplots(2, 1, figsize=(8, 9.6), sharex=True)
    for axis, (source, title) in zip(axes, SOURCES):
        for codec, name, colour, marker in CODECS:
            rates, scores = zip(*curve(data, source, codec))
            axis.plot(rates, scores, color=colour, marker=marker, markersize=5.5, linewidth=1.6, label=name,
                      markeredgecolor=SURFACE, markeredgewidth=0.8, zorder=3 if codec == "mcv2" else 2)
            # the name at the curve's cheapest point, where the curves stand apart, in ink: a line is never told by
            # its colour alone
            # VP9 starts next to AV1 and MCV2 next to H.264: their names go right of and below their first points
            offset, align = {"vp9": ((7, -1), "left"), "mcv2": ((0, -13), "center")}.get(codec, ((-7, -1), "right"))
            axis.annotate(name, (rates[0], scores[0]), textcoords="offset points", xytext=offset, fontsize=9.5,
                          color=SECONDARY, ha=align, va="center")
        axis.set_xscale("log")
        axis.xaxis.set_major_locator(FixedLocator(TICKS))
        axis.xaxis.set_major_formatter(FuncFormatter(lambda value, _: f"{value:g}"))
        axis.xaxis.set_minor_locator(NullLocator())
        axis.set_title(title, fontsize=11.5, color=INK, loc="left")
        axis.set_xlabel("Rate on the wire, Mbit/s (log scale)", color=SECONDARY)
        axis.grid(True, color=GRID, linewidth=0.6, zorder=0)
        axis.set_ylim(20, 102)
        axis.margins(x=0.18)
        for side in ("top", "right"):
            axis.spines[side].set_visible(False)
    for axis in axes:
        axis.set_ylabel("VMAF (mean)", color=SECONDARY)
        axis.xaxis.set_tick_params(labelbottom=True)
    handles, labels = axes[0].get_legend_handles_labels()
    figure.legend(handles, labels, loc="lower center", ncol=4, frameon=False, fontsize=10)
    figure.tight_layout(rect=(0, 0.04, 1, 1))
    figure.savefig(IMAGES / "codecs.png", dpi=120, facecolor=SURFACE)
    plt.close(figure)


def ablation_rates(ablation, kind="features"):
    """The BD-rate (%) on the wire of every turned-off feature (or variant) and source, with the VMAF range it covers."""
    results = []
    for feature in ablation.get(kind, []):
        row = {"id": feature["id"], "name": feature["name"]}
        for source, _ in SOURCES:
            baseline = [(p["zlib_mbps"], p["vmaf_mean"]) for p in ablation["baseline"][source]]
            test = [(p["zlib_mbps"], p["vmaf_mean"]) for p in feature["points"][source]]
            row[source] = bd_rate(baseline, test)
        results.append(row)
    return results


def draw_features(rows):
    rows = sorted(rows, key=lambda row: row["gameplay30"][0] + row["proxy30"][0])
    values = [row[source][0] for row in rows for source, _ in SOURCES]
    # one feature far beyond the others would flatten every other bar: its bars are cut at the axis and labelled
    ordered = sorted(values, reverse=True)
    limit = max(ordered[1] * 1.35, 10) if len(ordered) > 1 and ordered[0] > 2.5 * ordered[1] else None
    height = 0.38
    figure, axis = plt.subplots(figsize=(9.5, 0.55 * len(rows) + 1.6))
    for index, (source, title) in enumerate(SOURCES):
        # the first source's bar above the second's, in the legend's order
        positions = [row_index + (0.5 - index) * (height + 0.02) for row_index in range(len(rows))]
        shown = [min(row[source][0], limit) if limit else row[source][0] for row in rows]
        axis.barh(positions, shown, height=height, color=SOURCE_COLOURS[source], label=title, zorder=3)
        for position, row, value in zip(positions, rows, shown):
            if limit and row[source][0] > limit:
                axis.annotate(f"{row[source][0]:+.0f}% →", (value, position), textcoords="offset points",
                              xytext=(-4, 0), ha="right", va="center", fontsize=8.5, color=SURFACE, zorder=4)
    axis.set_yticks(range(len(rows)))
    axis.set_yticklabels([row["name"] for row in rows], color=INK)
    axis.axvline(0, color=SECONDARY, linewidth=0.8, zorder=2)
    axis.set_xlabel("Extra rate on the wire with the feature turned off (BD-rate, %)", color=SECONDARY)
    axis.grid(True, axis="x", color=GRID, linewidth=0.6, zorder=0)
    if limit:
        axis.set_xlim(right=limit * 1.02)
    for side in ("top", "right"):
        axis.spines[side].set_visible(False)
    axis.legend(loc="lower right", frameon=False, fontsize=10)
    figure.tight_layout()
    figure.savefig(IMAGES / "features.png", dpi=120, facecolor=SURFACE)
    plt.close(figure)


def signed(value):
    """A BD-rate with its sign, and a plain 0.0% for what rounds to zero."""
    text = f"{value:+.1f}%"
    return "0.0%" if text in ("+0.0%", "-0.0%") else text


def print_tables(data, rows, removed):
    print("Rate on the wire (Mbit/s) for the same VMAF mean, log-linear between measured points:\n")
    print("| VMAF mean | " + " | ".join(name for _, name, _, _ in CODECS) + " |")
    print("|---:|" + "---:|" * len(CODECS))
    for source, title in SOURCES:
        print(f"| **{title}** |" + " |" * len(CODECS))
        for level in LEVELS:
            cells = []
            for codec, _, _, _ in CODECS:
                rate = rate_at(curve(data, source, codec), level)
                cells.append("-" if rate is None else f"{rate:.2f}")
            print(f"| {level} | " + " | ".join(cells) + " |")
    print("\nBD-rate of MCV2 against each codec (positive: MCV2 needs more), over the VMAF range both cover:\n")
    for source, title in SOURCES:
        mcv2 = curve(data, source, "mcv2")
        cells = []
        for codec, name, _, _ in CODECS[1:]:
            percent, low, high = bd_rate(curve(data, source, codec), mcv2)
            cells.append(f"{name} {percent:+.1f}% (VMAF {low:.1f}-{high:.1f})")
        print(f"- {title}: " + "; ".join(cells))
    if rows:
        print("\nExtra rate on the wire without each feature (BD-rate against the full encoder):\n")
        print("| Feature turned off | " + " | ".join(title for _, title in SOURCES) + " |")
        print("|---|" + "---:|" * len(SOURCES))
        for row in sorted(rows, key=lambda row: -(row["gameplay30"][0] + row["proxy30"][0])):
            cells = [f"{signed(row[source][0])} ({row[source][1]:.0f}-{row[source][2]:.0f})" for source, _ in SOURCES]
            print(f"| {row['name']} | " + " | ".join(cells) + " |")
    if removed:
        print("\nWhat each feature MCV2 no longer has was worth (BD-rate when it was measured):\n")
        print("| Feature | " + " | ".join(title for _, title in SOURCES) + " | Mean |")
        print("|---|" + "---:|" * (len(SOURCES) + 1))
        for row in sorted(removed, key=lambda row: -row["bd_rate"]["mean"]):
            cells = [signed(row["bd_rate"][source]) for source, _ in SOURCES]
            print(f"| {row['name']} | " + " | ".join(cells) + f" | {signed(row['bd_rate']['mean'])} |")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tables", action="store_true", help="print the tables of docs/mcv2.md")
    arguments = parser.parse_args()
    # the fonts missing on a platform are skipped; say nothing about them
    logging.getLogger("matplotlib.font_manager").setLevel(logging.ERROR)
    style()
    IMAGES.mkdir(parents=True, exist_ok=True)
    data = json.loads((DATA / "codec_curves.json").read_text())
    draw_codecs(data)
    ablation_file = DATA / "ablation.json"
    ablation = json.loads(ablation_file.read_text()) if ablation_file.exists() else {}
    rows = ablation_rates(ablation)
    if rows:
        draw_features(rows)
    if arguments.tables:
        print_tables(data, rows, ablation.get("removed", []))


if __name__ == "__main__":
    main()
