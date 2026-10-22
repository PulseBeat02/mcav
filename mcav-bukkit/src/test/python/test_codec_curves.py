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


"""Resuming codec curves into an earlier output preserves the previous measurements."""

import json
import os
import tempfile
import unittest
from pathlib import Path
from typing import Any

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


class ResumeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.folder = tempfile.TemporaryDirectory()
        self.out = os.path.join(self.folder.name, CURVES_NAME)

    def tearDown(self) -> None:
        self.folder.cleanup()

    def test_should_resume_the_documented_output(self) -> None:
        documented: Any = json.loads(DOCUMENTED.read_text())
        self.write(documented)
        identity: Any = documented[SOURCES_KEY][DOCUMENTED_SOURCE]
        measured: Any | dict[Any, Any] = mcv2_tools.codec_curves_resume(self.out, DOCUMENTED_SOURCE, dict(identity))
        codecs: Any = measured[CODECS_KEY]
        self.assertEqual(documented[CODECS_KEY], codecs)

        source: Any = measured[SOURCES_KEY][DOCUMENTED_SOURCE]
        self.assertEqual(identity, source)

    def test_should_refuse_a_name_measured_from_other_content(self) -> None:
        documented: Any = json.loads(DOCUMENTED.read_text())
        self.write(documented)
        other: dict[str, Any] = dict(documented[SOURCES_KEY][DOCUMENTED_SOURCE], sha256="0" * DIGEST_DIGITS)
        with self.assertRaisesRegex(ValueError, "proxy30 already has measurements of other content"):
            mcv2_tools.codec_curves_resume(self.out, DOCUMENTED_SOURCE, other)

    def test_should_read_the_points_of_the_first_runs(self) -> None:
        self.write({SOURCES_KEY: {}, POINTS_KEY: [{"source": NEW_SOURCE, "codec": "x264", "quality": 20}]})
        measured: Any | dict[Any, Any] = mcv2_tools.codec_curves_resume(self.out, NEW_SOURCE, {DIGEST_KEY: "1" * DIGEST_DIGITS})
        codecs: Any = measured[CODECS_KEY]
        self.assertEqual([{"source": NEW_SOURCE, "codec": "x264", "quality": 20}], codecs)

        self.assertNotIn(POINTS_KEY, measured)

    def test_should_refuse_an_output_with_both_datasets(self) -> None:
        self.write({SOURCES_KEY: {}, POINTS_KEY: [], CODECS_KEY: []})
        with self.assertRaisesRegex(ValueError, "both points and codecs"):
            mcv2_tools.codec_curves_resume(self.out, NEW_SOURCE, {})

    def test_should_start_an_output_that_does_not_exist(self) -> None:
        measured: Any | dict[Any, Any] = mcv2_tools.codec_curves_resume(self.out, NEW_SOURCE, {DIGEST_KEY: "2" * DIGEST_DIGITS})
        self.assertEqual({SOURCES_KEY: {NEW_SOURCE: {DIGEST_KEY: "2" * DIGEST_DIGITS}}, CODECS_KEY: []}, measured)

    def write(self, measured: Any | dict[Any, Any]) -> None:
        with open(self.out, "w") as out:
            json.dump(measured, out)


if __name__ == "__main__":
    unittest.main()
