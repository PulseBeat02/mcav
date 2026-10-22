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


"""Exact event matching for the MCV2 latency report, without JFR or a client."""

import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
from typing import Any
from unittest.mock import patch

import mcv2_tools

PROGRAM: str = "mcv2_tools.py"
REPORT_NAME: str = "report.json"
RECORDING_PATH: str = "unused.jfr"
CAPTURE_PATH: str = "unused.nut"

FRAME_NUMBER: int = 7
EVENT_BYTES: int = 64
EVENT_COLORS: int = 64

FIRST_SENT: int = 100
LATER_ARRIVAL: int = 150
EARLY_SENT: int = 175
CAPTURE_TIME: int = 200
LATER_CAPTURE_TIME: int = 210

FIRST_FRAME_LATENCY: int = 200

LATENCY_KEY_PREFIX: str = "glass_to_glass_ms_"
MEAN_LATENCY_KEY: str = LATENCY_KEY_PREFIX + "mean"


class LatencyTest(unittest.TestCase):
    def test_should_use_a_frame_sent_before_the_capture_for_a_repeated_number(self) -> None:
        result: dict[str, float | int] = self.report(event(1, LATER_ARRIVAL, 250))
        displayed: float | int = result["displayed"]
        self.assertEqual(1, displayed)

        for statistic in ("mean", "p50", "p95", "max"):
            glass_to_glass: float | int = result[LATENCY_KEY_PREFIX + statistic]
            self.assertEqual(FIRST_FRAME_LATENCY, glass_to_glass)

    def test_should_not_replace_the_displayed_frame_with_an_unsent_occurrence(self) -> None:
        result: dict[str, float | int] = self.report(event(1, LATER_ARRIVAL, EARLY_SENT, sent_to=0))
        glass_to_glass: float | int = result[MEAN_LATENCY_KEY]
        self.assertEqual(FIRST_FRAME_LATENCY, glass_to_glass)

    def test_should_display_a_later_sent_occurrence(self) -> None:
        result: dict[str, float | int] = self.report(event(1, LATER_ARRIVAL, EARLY_SENT))
        glass_to_glass: float | int = result[MEAN_LATENCY_KEY]
        self.assertEqual(50, glass_to_glass)

    def test_should_not_display_an_occurrence_with_a_nonfinite_send_time(self) -> None:
        for invalid in (float("nan"), float("inf")):
            with self.subTest(invalid=invalid):
                result: dict[str, float | int] = self.report(event(1, LATER_ARRIVAL, invalid))
                glass_to_glass: float | int = result[MEAN_LATENCY_KEY]
                self.assertEqual(FIRST_FRAME_LATENCY, glass_to_glass)

    def report(self, later: dict[str, bool | float | int] | dict[str, bool | int]) -> dict[str, float | int]:
        with tempfile.TemporaryDirectory() as directory:
            output: Path = Path(directory, REPORT_NAME)
            arguments: list[str] = [PROGRAM, RECORDING_PATH, CAPTURE_PATH, "--json", str(output)]
            events: list[dict[str, bool | float | int] | dict[str, bool | int]] = [event(0, 0, FIRST_SENT), later]
            captures: list[tuple[int, int]] = [(CAPTURE_TIME, FRAME_NUMBER), (LATER_CAPTURE_TIME, FRAME_NUMBER)]
            with patch.object(sys, "argv", arguments), patch.object(mcv2_tools, "latency_events", return_value=events),\
                    patch.object(mcv2_tools, "latency_captures", return_value=captures), redirect_stdout(StringIO()):
                mcv2_tools.latency_main()
            return json.loads(output.read_text())


def event(frame: int, arrived: int, sent: int | float, sent_to: int = 1) -> dict[str, bool | float | int] | dict[str, bool | int]:
    return dict(frame=frame, arrived=arrived, sent=sent, sent_to=sent_to, number=FRAME_NUMBER,
                keyframe=True, bytes=EVENT_BYTES, colors=EVENT_COLORS, behind=0, waiting=0, backlog=0)


if __name__ == "__main__":
    unittest.main()
