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
import math
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
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
CAPTURE_PREFIX: str = "capture-"
CAPTURE_SUFFIX: str = ".png"

SIDE: int = 8
PICTURE_SHAPE: tuple[int, int, int] = (SIDE, SIDE, mcv2_reference.CHANNELS)

FIRST_GRAY: int = 37
SECOND_GRAY: int = 92

EXACT_CAPTURES_KEY: str = "exact_captures"
AMBIGUOUS_REFERENCE_FRAMES_KEY: str = "ambiguous_reference_frames"
PSNR_MINIMUM_KEY: str = "psnr_min_db"
SSIM_MEAN_KEY: str = "ssim_mean"


class CaptureCheckTest(unittest.TestCase):
    def test_should_not_count_identical_reference_frames_as_unmatchable_missing_pictures(self) -> None:
        picture: numpy.ndarray = numpy.full(PICTURE_SHAPE, FIRST_GRAY, numpy.uint8)
        status: str | int | None
        result: dict[str, float | int | str]
        text: str
        status, result, text = self.run_capture_check([picture, picture], [picture, picture])
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
        status: str | int | None
        result: dict[str, float | int | str]
        _: str
        status, result, _ = self.run_capture_check([first, first, second], [first, first])
        self.assertEqual(1, status)

        exact_captures: float | int | str = result[EXACT_CAPTURES_KEY]
        self.assertEqual(2, exact_captures)

    def test_should_find_different_frames_indistinguishable_when_the_visible_crop_hides_the_difference(self) -> None:
        first: numpy.ndarray = numpy.full((2 * SIDE, SIDE, mcv2_reference.CHANNELS), FIRST_GRAY, numpy.uint8)
        second: numpy.ndarray = first.copy()
        second[SIDE:] = SECOND_GRAY
        status: str | int | None
        result: dict[str, float | int | str]
        _: str
        status, result, _ = self.run_capture_check([first, second], [first[:SIDE], second[:SIDE]])
        self.assertEqual(0, status)

        ambiguous_reference_frames: float | int | str = result[AMBIGUOUS_REFERENCE_FRAMES_KEY]
        self.assertEqual(2, ambiguous_reference_frames)

    def test_should_fail_when_a_capture_is_not_exact(self) -> None:
        first: numpy.ndarray = numpy.full(PICTURE_SHAPE, FIRST_GRAY, numpy.uint8)
        second: numpy.ndarray = numpy.full(PICTURE_SHAPE, SECOND_GRAY, numpy.uint8)
        status: str | int | None
        result: dict[str, float | int | str]
        text: str
        status, result, text = self.run_capture_check([first], [first, second])
        self.assertEqual(1, status)

        exact_captures: float | int | str = result[EXACT_CAPTURES_KEY]
        self.assertEqual(1, exact_captures)

        self.assertIn("capture-01.png is not exact: closest frame 0", text)

    def test_should_match_the_picture_below_the_top_rows(self) -> None:
        picture: numpy.ndarray = numpy.full(PICTURE_SHAPE, FIRST_GRAY, numpy.uint8)
        band: numpy.ndarray = numpy.full(PICTURE_SHAPE, SECOND_GRAY, numpy.uint8)
        capture: numpy.ndarray = numpy.concatenate([band, picture])
        status: str | int | None
        result: dict[str, float | int | str]
        _: str
        status, result, _ = self.run_capture_check([picture], [capture], top=SIDE)
        self.assertEqual(0, status)

        exact_captures: float | int | str = result[EXACT_CAPTURES_KEY]
        self.assertEqual(1, exact_captures)

    def test_should_score_an_exact_capture_as_identical(self) -> None:
        picture: numpy.ndarray = numpy.full(PICTURE_SHAPE, FIRST_GRAY, numpy.uint8)
        result: dict[str, float | int | str] = self.run_capture_check([picture], [picture])[1]
        psnr_minimum: float | int | str = result[PSNR_MINIMUM_KEY]
        self.assertEqual("inf", psnr_minimum)

        ssim_mean: float | int | str = result[SSIM_MEAN_KEY]
        self.assertEqual(1.0, ssim_mean)

    def test_should_score_a_capture_that_is_not_exact(self) -> None:
        reference: numpy.ndarray = numpy.full(PICTURE_SHAPE, FIRST_GRAY, numpy.uint8)
        capture: numpy.ndarray = numpy.full(PICTURE_SHAPE, SECOND_GRAY, numpy.uint8)
        result: dict[str, float | int | str] = self.run_capture_check([reference], [capture])[1]
        psnr_minimum: float = float(result[PSNR_MINIMUM_KEY])
        difference: int = SECOND_GRAY - FIRST_GRAY
        expected_psnr: float = 10 * math.log10(255**2 / difference**2)
        self.assertAlmostEqual(expected_psnr, psnr_minimum)

        ssim_mean: float = float(result[SSIM_MEAN_KEY])
        luminance_constant: float = (0.01 * 255) ** 2
        products: float = 2 * FIRST_GRAY * SECOND_GRAY + luminance_constant
        squares: float = FIRST_GRAY**2 + SECOND_GRAY**2 + luminance_constant
        self.assertAlmostEqual(products / squares, ssim_mean)

    def test_should_exit_2_when_the_top_rows_hide_the_whole_capture(self) -> None:
        status: str | int | None = self.run_capture_check_with_top(SIDE)
        self.assertEqual(2, status)

    def test_should_exit_2_on_a_negative_top(self) -> None:
        status: str | int | None = self.run_capture_check_with_top(-1)
        self.assertEqual(2, status)

    def run_capture_check(
        self,
        references: list[numpy.ndarray],
        captures: list[numpy.ndarray],
        top: int = 0
    ) -> tuple[str | int | None, dict[str, float | int | str], str]:
        with tempfile.TemporaryDirectory() as directory:
            root: Path = Path(directory)
            reference: Path = root / REFERENCE_NAME
            reference.write_bytes(b"".join(picture.tobytes() for picture in references))

            for index, picture in enumerate(captures):
                image: Image.Image = Image.fromarray(picture)
                image.save(root / f"{CAPTURE_PREFIX}{index:02d}{CAPTURE_SUFFIX}")

            height: int
            width: int
            _: int
            height, width, _ = references[0].shape
            arguments: list[str] = [PROGRAM, str(reference), str(width), str(height), str(root), "--top", str(top)]
            output: StringIO = StringIO()
            with patch.object(sys, "argv", arguments), redirect_stdout(output), self.assertRaises(SystemExit) as ended:
                mcv2_tools.capture_check_main()

            text: str = output.getvalue()
            last_line: str = text.splitlines()[-1]
            result: dict[str, float | int | str] = json.loads(last_line)
            return ended.exception.code, result, text

    def run_capture_check_with_top(self, top: int) -> str | int | None:
        picture: numpy.ndarray = numpy.full(PICTURE_SHAPE, FIRST_GRAY, numpy.uint8)
        with tempfile.TemporaryDirectory() as directory:
            root: Path = Path(directory)
            reference: Path = root / REFERENCE_NAME
            reference.write_bytes(picture.tobytes())
            image: Image.Image = Image.fromarray(picture)
            image.save(root / f"{CAPTURE_PREFIX}00{CAPTURE_SUFFIX}")
            arguments: list[str] = [PROGRAM, str(reference), str(SIDE), str(SIDE), str(root), "--top", str(top)]
            with patch.object(sys, "argv", arguments), redirect_stderr(StringIO()), self.assertRaises(SystemExit) as ended:
                mcv2_tools.capture_check_main()

            return ended.exception.code


if __name__ == "__main__":
    unittest.main()
