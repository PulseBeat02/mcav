# This file is part of mcav, a media playback library for Java
# Copyright (C) Brandon Li <https://brandonli.me/>
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.

"""Check the transport strip and the status squares in pictures captured from a client with the MCV2 pack's debug view.

    python tools/mcv2/strip_check.py <captures folder> --slots N --video-width W
        [--screens S --screen I --first-slot F --total-slots T --debug-top R]

The captures are screenshots of the whole screen, as for capture_check.py. In every capture the page in every slot of
the transport strip is read back, four six-bit symbols from the three bytes of each pixel, and validated by the
reference's read_page (tools/mcv2-reference; header, extent and CRC32), and the anchor descriptor row must start with the pixels "MCV" and
0xA1. When the screen is wide enough, the debug view also draws status squares to the right of the picture: one per
page slot (green: a valid page, red: none), one for the client frame's decision (green: decoded, blue: nothing new,
red: a frame that cannot be decoded) and four grey squares for the bytes of the decoded-frame counter, lowest first.
Every square must be one exact colour, a slot's square must agree with the page read from the same capture, no
decision may be red, and the counter must never go down. The summary is one JSON line; the exit code is 1 when any
check failed, and 2 when the folder holds no PNG screenshot.

A pack of several screens has every screen's slots in the strip, then one descriptor row per screen, and the debug
view draws the screens' pictures one under the other: --screens and --total-slots describe the strip, --screen,
--first-slot and --slots the screen checked, and --debug-top the row below the strip where its picture starts (the
pack's MCV2_DEBUG_TOP of that screen).
"""

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "mcv2-reference"))
from mcvideo.format import PAGE_SYMBOLS, SYMBOL_BITS
from mcvideo.transport import PAGE_HEADER, page_capacity, read_page

PAGE_PIXELS = PAGE_SYMBOLS // 4
HEADER_SYMBOLS = (PAGE_HEADER.size * 8 + SYMBOL_BITS - 1) // SYMBOL_BITS
SQUARE = 20
STEP = 24
GREEN, RED, BLUE = (0, 255, 0), (255, 0, 0), (0, 0, 255)
DESCRIPTOR = [(0x4D, 0x43, 0x56), (0xA1, 0, 0)]


def page_symbols(screen, slot, rows):
    """The 16,384 symbols of the page in a slot: its 4,096 pixels row by row from the top of the slot."""
    pixels = screen[slot * rows : (slot + 1) * rows].reshape(-1, 3)[:PAGE_PIXELS].astype(np.uint32)
    bits = pixels[:, 0] | pixels[:, 1] << 8 | pixels[:, 2] << 16
    return np.stack([bits >> shift & 63 for shift in (0, 6, 12, 18)], axis=1).astype(np.uint8).ravel()


def read_strip_page(symbols):
    """Remove map row padding before validating the exact six-bit page extent."""
    if len(symbols) < HEADER_SYMBOLS:
        raise ValueError("truncated strip page header")
    bits = ((symbols[:HEADER_SYMBOLS, None] >> np.arange(SYMBOL_BITS)) & 1).astype(np.uint8).ravel()
    fields = PAGE_HEADER.unpack(np.packbits(bits[:PAGE_HEADER.size * 8], bitorder="little").tobytes())
    number, total = fields[6], fields[9]
    size = min(page_capacity(), max(total - number * page_capacity(), 0))
    length = ((PAGE_HEADER.size + size) * 8 + SYMBOL_BITS - 1) // SYMBOL_BITS
    return read_page(symbols[:length].tobytes())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("captures", type=Path)
    parser.add_argument("--slots", type=int, required=True)
    parser.add_argument("--video-width", type=int, required=True)
    parser.add_argument("--screens", type=int, default=1)
    parser.add_argument("--screen", type=int, default=0)
    parser.add_argument("--first-slot", type=int, default=0)
    parser.add_argument("--total-slots", type=int)
    parser.add_argument("--debug-top", type=int, default=0)
    arguments = parser.parse_args()
    total_slots = arguments.total_slots if arguments.total_slots is not None else arguments.slots
    captures = sorted(arguments.captures.glob("*.png"))
    # a folder without captures checked nothing, which must not read as a pass
    if not captures:
        parser.error("%s holds no PNG screenshots" % arguments.captures)
    valid, invalid, frames = Counter(), Counter(), set()
    descriptors, decisions, failures, counts = 0, Counter(), [], []
    squared = 0
    for capture in captures:
        screen = np.asarray(Image.open(capture).convert("RGB"))
        width = screen.shape[1]
        rows = (PAGE_PIXELS + width - 1) // width
        pages = {}
        for slot in range(arguments.slots):
            try:
                page = read_strip_page(page_symbols(screen, arguments.first_slot + slot, rows))
                pages[slot] = True
                valid[slot] += 1
                frames.add((page.frame_id, page.number))
            except ValueError as error:
                pages[slot] = False
                invalid[slot, str(error)] += 1
        descriptor_row = total_slots * rows + arguments.screen
        if [tuple(int(channel) for channel in screen[descriptor_row, column]) for column in range(2)] == DESCRIPTOR:
            descriptors += 1
        else:
            failures.append((capture.name, "descriptor"))
        top = total_slots * rows + arguments.screens + arguments.debug_top
        if arguments.video_width + 8 + (arguments.slots + 5) * STEP > width:
            continue
        squared += 1
        colours = []
        for square in range(arguments.slots + 5):
            left = arguments.video_width + 8 + square * STEP
            region = screen[top : top + SQUARE, left : left + SQUARE].reshape(-1, 3)
            if np.any(region != region[0]):
                failures.append((capture.name, "square %d is not one colour" % square))
            colours.append(tuple(int(channel) for channel in region[0]))
        for slot in range(arguments.slots):
            if colours[slot] != (GREEN if pages[slot] else RED):
                failures.append((capture.name, "slot %d square %s, page valid %s" % (slot, colours[slot], pages[slot])))
        decision = {GREEN: "decoded", BLUE: "nothing new", RED: "cannot decode"}.get(colours[arguments.slots], "other")
        decisions[decision] += 1
        if decision in ("cannot decode", "other"):
            failures.append((capture.name, "decision %s" % (colours[arguments.slots],)))
        count = 0
        for index, colour in enumerate(colours[arguments.slots + 1 :]):
            if len(set(colour)) != 1:
                failures.append((capture.name, "counter byte %d not grey: %s" % (index, colour)))
            count |= colour[0] << 8 * index
        if counts and count < counts[-1]:
            failures.append((capture.name, "counter went down from %d to %d" % (counts[-1], count)))
        counts.append(count)
    summary = {
        "captures": len(captures),
        "valid_pages_per_slot": {slot: valid[slot] for slot in sorted(valid)},
        "slots_without_a_page": {"%d: %s" % key: value for key, value in sorted(invalid.items())},
        "distinct_pages": len(frames),
        "descriptor_rows": descriptors,
        "captures_with_squares": squared,
        "decisions": dict(decisions),
        "counter": [counts[0], counts[-1]] if counts else None,
        "failures": len(failures),
    }
    for failure in failures[:20]:
        print("%s: %s" % failure)
    print(json.dumps(summary))
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
