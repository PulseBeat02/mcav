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
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stderr
from io import StringIO
from pathlib import Path
from typing import Any
from unittest.mock import patch

import mcv2_tools

CURVES_NAME: str = "codec_curves.json"
DOCUMENTED: Path = Path(__file__).resolve().parents[1] / "resources/mcv2/data" / CURVES_NAME

SOURCES_KEY: str = "sources"
CODECS_KEY: str = "codecs"
POINTS_KEY: str = "points"
DIGEST_KEY: str = "sha256"

DOCUMENTED_SOURCE: str = "proxy30"
NEW_SOURCE: str = "a"

DIGEST_DIGITS: int = 64

SOURCE_FILE_NAME: str = "source.rgb"
MISSING_FFMPEG_NAME: str = "missing-ffmpeg"
DECODED_FILE_NAME: str = "decoded.rgb"

FRAMES: int = 2
WIDTH: int = 2
HEIGHT: int = 1
CHANNELS: int = 3
FRAME_BYTES: int = WIDTH * HEIGHT * CHANNELS
BLACK_FRAMES: bytes = bytes(FRAMES * FRAME_BYTES)


class ResumeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.folder = tempfile.TemporaryDirectory()
        self.output = os.path.join(self.folder.name, CURVES_NAME)

    def tearDown(self) -> None:
        self.folder.cleanup()

    def test_should_resume_the_documented_output(self) -> None:
        documented: Any = json.loads(DOCUMENTED.read_text())
        self.write(documented)
        identity: Any = documented[SOURCES_KEY][DOCUMENTED_SOURCE]
        measured: Any | dict[Any, Any] = mcv2_tools.codec_curves_resume(self.output, DOCUMENTED_SOURCE, dict(identity))
        codecs: Any = measured[CODECS_KEY]
        self.assertEqual(documented[CODECS_KEY], codecs)

        source: Any = measured[SOURCES_KEY][DOCUMENTED_SOURCE]
        self.assertEqual(identity, source)

    def test_should_refuse_a_name_measured_from_other_content(self) -> None:
        documented: Any = json.loads(DOCUMENTED.read_text())
        self.write(documented)
        other: dict[str, Any] = {**documented[SOURCES_KEY][DOCUMENTED_SOURCE], DIGEST_KEY: "0" * DIGEST_DIGITS}
        with self.assertRaisesRegex(ValueError, "proxy30 already has measurements of other content"):
            mcv2_tools.codec_curves_resume(self.output, DOCUMENTED_SOURCE, other)

    def test_should_refuse_a_name_measured_with_other_settings(self) -> None:
        documented: Any = json.loads(DOCUMENTED.read_text())
        self.write(documented)
        identity: Any = documented[SOURCES_KEY][DOCUMENTED_SOURCE]
        other: dict[str, Any] = {**identity, "frames": identity["frames"] + 1}
        with self.assertRaisesRegex(ValueError, "proxy30 already has measurements of other content"):
            mcv2_tools.codec_curves_resume(self.output, DOCUMENTED_SOURCE, other)

    def test_should_exit_2_before_ffmpeg_when_a_name_has_other_content(self) -> None:
        documented: Any = json.loads(DOCUMENTED.read_text())
        self.write(documented)
        source: Path = Path(self.folder.name) / SOURCE_FILE_NAME
        source.write_bytes(b"\0")
        missing: str = os.path.join(self.folder.name, MISSING_FFMPEG_NAME)
        arguments: list[str] = [
            "mcv2_tools.py",
            mcv2_tools.FFMPEG_OPTION,
            missing,
            mcv2_tools.SOURCE_OPTION,
            str(source),
            "--name",
            DOCUMENTED_SOURCE,
            mcv2_tools.WIDTH_OPTION,
            "1",
            mcv2_tools.HEIGHT_OPTION,
            "1",
            mcv2_tools.FRAMES_OPTION,
            "1",
            mcv2_tools.FRAMES_PER_SECOND_OPTION,
            "1",
            "--qualities",
            "20",
            mcv2_tools.OUTPUT_OPTION,
            self.output,
        ]
        errors: StringIO = StringIO()
        with patch.object(sys, "argv", arguments), redirect_stderr(errors), self.assertRaises(SystemExit) as raised:
            mcv2_tools.codec_curves_main()
        status: int | str | None = raised.exception.code
        self.assertEqual(2, status)

        printed: str = errors.getvalue()
        self.assertIn("proxy30 already has measurements of other content", printed)

    def test_should_read_the_points_of_the_first_runs(self) -> None:
        point: dict[str, Any] = {"source": NEW_SOURCE, "codec": "x264", "quality": 20}
        self.write({SOURCES_KEY: {}, POINTS_KEY: [point]})
        measured: Any | dict[Any, Any] = mcv2_tools.codec_curves_resume(self.output, NEW_SOURCE, {DIGEST_KEY: "1" * DIGEST_DIGITS})
        codecs: Any = measured[CODECS_KEY]
        self.assertEqual([point], codecs)

        self.assertNotIn(POINTS_KEY, measured)

    def test_should_refuse_an_output_with_both_datasets(self) -> None:
        self.write({SOURCES_KEY: {}, POINTS_KEY: [], CODECS_KEY: []})
        with self.assertRaisesRegex(ValueError, "both points and codecs"):
            mcv2_tools.codec_curves_resume(self.output, NEW_SOURCE, {})

    def test_should_start_an_output_that_does_not_exist(self) -> None:
        digest: str = "2" * DIGEST_DIGITS
        measured: Any | dict[Any, Any] = mcv2_tools.codec_curves_resume(self.output, NEW_SOURCE, {DIGEST_KEY: digest})
        self.assertEqual({SOURCES_KEY: {NEW_SOURCE: {DIGEST_KEY: digest}}, CODECS_KEY: []}, measured)

    def write(self, measured: Any | dict[Any, Any]) -> None:
        with open(self.output, "w") as output_file:
            json.dump(measured, output_file)


class MeasurementTest(unittest.TestCase):
    def setUp(self) -> None:
        self.folder = tempfile.TemporaryDirectory()
        self.source = os.path.join(self.folder.name, SOURCE_FILE_NAME)
        Path(self.source).write_bytes(BLACK_FRAMES)

    def tearDown(self) -> None:
        self.folder.cleanup()

    def test_should_score_identical_frames_at_99_db(self) -> None:
        psnr: float = mcv2_tools.codec_curves_rgb_psnr(self.source, self.source, FRAMES, WIDTH, HEIGHT)
        self.assertEqual(99.0, psnr)

    def test_should_score_the_mean_of_the_frame_errors(self) -> None:
        decoded: str = os.path.join(self.folder.name, DECODED_FILE_NAME)
        Path(decoded).write_bytes(bytes([3] * FRAME_BYTES + [4] * FRAME_BYTES))
        psnr: float = mcv2_tools.codec_curves_rgb_psnr(self.source, decoded, FRAMES, WIDTH, HEIGHT)
        expected: float = 10 * math.log10(255**2 / ((3**2 + 4**2) / 2))
        self.assertAlmostEqual(expected, psnr)


if __name__ == "__main__":
    unittest.main()
