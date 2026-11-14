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
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path

import mcv2_tools
from mcv2_reference import pack_frame

ARCHIVE_FILE: str = "frames.mcv2"
DUMP_INDENT: str = "  "


class SamplesTest(unittest.TestCase):
    def test_should_dump_every_byte_of_the_chosen_frame_once_in_order(self) -> None:
        frames: list[bytes] = [pack_frame(1, 1, 7, 7, {}), pack_frame(1, 1, 8, 8, {})]
        output: StringIO = StringIO()
        with tempfile.TemporaryDirectory() as directory:
            archive: Path = Path(directory) / ARCHIVE_FILE
            archive.write_bytes(mcv2_tools.archive_bytes(frames))
            arguments: argparse.Namespace = argparse.Namespace(archive=archive, frame=1)
            with redirect_stdout(output):
                mcv2_tools.samples_print_bytes(arguments)

        dumped: bytes = dumped_bytes(output.getvalue())
        self.assertEqual(frames[1], dumped)


def dumped_bytes(output: str) -> bytes:
    lines: list[str] = output.splitlines()
    dumps: list[str] = [line.split(maxsplit=1)[1] for line in lines if line.startswith(DUMP_INDENT)]
    return bytes.fromhex(mcv2_tools.WORD_SEPARATOR.join(dumps))


if __name__ == "__main__":
    unittest.main()
