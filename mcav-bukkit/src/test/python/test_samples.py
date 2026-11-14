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

import argparse
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
from unittest.mock import patch

import mcv2_tools
from mcv2_reference import SKIP, Leaf, pack_frame

PROGRAM: str = "mcv2_tools.py"
SAMPLES: str = "samples"

ARCHIVE_FILE: str = "frames.mcv2"
DUMP_INDENT: str = "  "

LEAF_SIZE: int = 8
LEAF_QUANTIZER: int = 0


class SamplesTest(unittest.TestCase):
    def test_should_dump_every_byte_of_the_chosen_frame_once_in_order(self) -> None:
        frames: list[bytes] = [pack_frame(1, 1, 7, 7, {}), pack_frame(1, 1, 8, 8, {})]
        output: StringIO = StringIO()
        with tempfile.TemporaryDirectory() as directory:
            archive: Path = write_archive(directory, frames)
            arguments: argparse.Namespace = argparse.Namespace(archive=archive, frame=1)
            with redirect_stdout(output):
                mcv2_tools.samples_print_bytes(arguments)

        dumped: bytes = dumped_bytes(output.getvalue())
        self.assertEqual(frames[1], dumped)

    def test_should_keep_only_the_leaves_that_overlap_the_crop(self) -> None:
        crop: tuple[int, int, int, int] = mcv2_tools.samples_crop_of("16,24,32,40")
        neighbors: list[Leaf] = [leaf_at(8, 24), leaf_at(48, 24), leaf_at(16, 16), leaf_at(16, 64)]
        overlapping: list[Leaf] = [leaf_at(9, 17), leaf_at(47, 63)]

        inside: list[Leaf] = [leaf for leaf in neighbors + overlapping if mcv2_tools.samples_inside(leaf, crop)]
        self.assertEqual(overlapping, inside)

    def test_should_dump_the_frame_that_the_command_line_names(self) -> None:
        frame: bytes = pack_frame(1, 1, 7, 7, {})
        output: StringIO = StringIO()
        with tempfile.TemporaryDirectory() as directory:
            archive: Path = write_archive(directory, [frame])
            arguments: list[str] = [PROGRAM, SAMPLES, mcv2_tools.BYTES_COMMAND, str(archive), mcv2_tools.FRAME_OPTION, "0"]
            with patch.object(sys, "argv", arguments), redirect_stdout(output):
                mcv2_tools.main()

        dumped: bytes = dumped_bytes(output.getvalue())
        self.assertEqual(frame, dumped)


def write_archive(directory: str, frames: list[bytes]) -> Path:
    archive: Path = Path(directory) / ARCHIVE_FILE
    archive.write_bytes(mcv2_tools.archive_bytes(frames))

    return archive


def dumped_bytes(output: str) -> bytes:
    lines: list[str] = output.splitlines()
    dumps: list[str] = [line.split(maxsplit=1)[1] for line in lines if line.startswith(DUMP_INDENT)]
    return bytes.fromhex(mcv2_tools.WORD_SEPARATOR.join(dumps))


def leaf_at(pixel_x: int, pixel_y: int) -> Leaf:
    return Leaf(pixel_x, pixel_y, LEAF_SIZE, SKIP, LEAF_QUANTIZER, None, b"", None)


if __name__ == "__main__":
    unittest.main()
