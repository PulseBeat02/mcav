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

import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
from typing import Any
from unittest.mock import patch

import numpy
from PIL import Image

import mcv2_reference
import mcv2_tools

PROGRAM: str = "mcv2_tools.py"
REFERENCE_NAME: str = "reference.rgb"
CAPTURE_NAME: str = "capture-%02d.png"

SIDE: int = 8
PICTURE_SHAPE: tuple[int, int, int] = (SIDE, SIDE, mcv2_reference.CHANNELS)

FIRST_GRAY: int = 37
SECOND_GRAY: int = 92

EXACT_CAPTURES_KEY: str = "exact_captures"
AMBIGUOUS_REFERENCE_FRAMES_KEY: str = "ambiguous_reference_frames"


class CaptureCheckTest(unittest.TestCase):
    def test_should_not_count_identical_reference_frames_as_unmatchable_missing_pictures(self) -> None:
        picture: numpy.ndarray = numpy.full(PICTURE_SHAPE, FIRST_GRAY, numpy.uint8)
        status, result, text = self.report([picture, picture], [picture, picture])
        self.assertEqual(0, status)

        exact_captures: float | int | str = result[EXACT_CAPTURES_KEY]
        self.assertEqual(2, exact_captures)

        pictures_seen_exactly: float | int | str = result["pictures_seen_exactly"]
        self.assertEqual(1, pictures_seen_exactly)

        distinct_pictures: float | int | str = result["distinct_pictures"]
        self.assertEqual(1, distinct_pictures)

        ambiguous_reference_frames: float | int | str = result[AMBIGUOUS_REFERENCE_FRAMES_KEY]
        self.assertEqual(2, ambiguous_reference_frames)

        frames_seen_exactly: float | int | str = result["frames_seen_exactly"]
        self.assertEqual(0, frames_seen_exactly)

        self.assertIn("indistinguishable", text)

    def test_should_fail_when_a_distinct_picture_is_missing(self) -> None:
        first: numpy.ndarray = numpy.full(PICTURE_SHAPE, FIRST_GRAY, numpy.uint8)
        second: numpy.ndarray = numpy.full(PICTURE_SHAPE, SECOND_GRAY, numpy.uint8)
        status, result, _ = self.report([first, first, second], [first, first])
        self.assertEqual(1, status)

        exact_captures: float | int | str = result[EXACT_CAPTURES_KEY]
        self.assertEqual(2, exact_captures)

    def test_should_find_different_frames_indistinguishable_when_the_visible_crop_hides_the_difference(self) -> None:
        first: numpy.ndarray = numpy.full((2 * SIDE, SIDE, mcv2_reference.CHANNELS), FIRST_GRAY, numpy.uint8)
        second: numpy.ndarray = first.copy()
        second[SIDE:] = SECOND_GRAY
        status, result, _ = self.report([first, second], [first[:SIDE], second[:SIDE]])
        self.assertEqual(0, status)

        ambiguous_reference_frames: float | int | str = result[AMBIGUOUS_REFERENCE_FRAMES_KEY]
        self.assertEqual(2, ambiguous_reference_frames)

    def report(
        self, references: list[numpy.ndarray], captures: list[numpy.ndarray]
    ) -> tuple[str | int | None, dict[str, float | int | str], str]:
        with tempfile.TemporaryDirectory() as directory:
            root: Path = Path(directory)
            reference: Path = root / REFERENCE_NAME
            reference.write_bytes(b"".join(picture.tobytes() for picture in references))
            for index, picture in enumerate(captures):
                Image.fromarray(picture).save(root / (CAPTURE_NAME % index))
            height, width, _ = references[0].shape
            arguments: list[str] = [PROGRAM, str(reference), str(width), str(height), str(root), "--top", "0"]
            output: StringIO = StringIO()
            with patch.object(sys, "argv", arguments), redirect_stdout(output), self.assertRaises(SystemExit) as ended:
                mcv2_tools.capture_check_main()
            return ended.exception.code, json.loads(output.getvalue().splitlines()[-1]), output.getvalue()


if __name__ == "__main__":
    unittest.main()
