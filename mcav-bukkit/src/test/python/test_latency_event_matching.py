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
EVENT_NUMBER_KEY: str = "number"

FIRST_SENT: int = 100
LATER_ARRIVAL: int = 150
EARLY_SENT: int = 175
CAPTURE_TIME: int = 200
LATER_CAPTURE_TIME: int = 210
THIRD_ARRIVAL: int = 300
THIRD_SENT: int = 350

READABLE_CAPTURES: tuple[tuple[int, int | None], ...] = ((CAPTURE_TIME, FRAME_NUMBER), (LATER_CAPTURE_TIME, FRAME_NUMBER))
SEVERAL_CAPTURES: tuple[tuple[int, int | None], ...] = ((CAPTURE_TIME, FRAME_NUMBER), (250, FRAME_NUMBER + 1), (900, FRAME_NUMBER + 2))

FIRST_FRAME_LATENCY: int = 200

LATENCY_KEY_PREFIX: str = "glass_to_glass_milliseconds_"
MEAN_LATENCY_KEY: str = f"{LATENCY_KEY_PREFIX}mean"
AMBIGUOUS_DISPLAYED_KEY: str = "ambiguous_displayed"
SOURCE_FRAMES_KEY: str = "source_frames"
CAPTURE_SECONDS_KEY: str = "capture_seconds"
DISPLAYED_FRAMES_PER_SECOND_KEY: str = "displayed_frames_per_second"


class LatencyTest(unittest.TestCase):
    def test_should_use_a_frame_sent_before_the_capture_for_a_repeated_number(self) -> None:
        result: dict[str, float | int] = self.report(event(1, LATER_ARRIVAL, 250))
        displayed: float | int = result["displayed"]
        self.assertEqual(1, displayed)

        for statistic in ("mean", "p50", "p95", "maximum"):
            glass_to_glass: float | int = result[f"{LATENCY_KEY_PREFIX}{statistic}"]
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

    def test_should_count_only_a_display_with_several_sent_candidates_as_ambiguous(self) -> None:
        single: dict[str, float | int] = self.report(event(1, LATER_ARRIVAL, EARLY_SENT, sent_to=0))
        single_ambiguous: float | int = single[AMBIGUOUS_DISPLAYED_KEY]
        self.assertEqual(0, single_ambiguous)

        several: dict[str, float | int] = self.report(event(1, LATER_ARRIVAL, EARLY_SENT))
        several_ambiguous: float | int = several[AMBIGUOUS_DISPLAYED_KEY]
        self.assertEqual(1, several_ambiguous)

    def test_should_keep_the_displayed_number_across_an_unreadable_capture(self) -> None:
        captures: tuple[tuple[int, int | None], ...] = ((CAPTURE_TIME, FRAME_NUMBER), (205, None), (LATER_CAPTURE_TIME, FRAME_NUMBER))
        result: dict[str, float | int] = self.report(event(1, LATER_ARRIVAL, EARLY_SENT, sent_to=0), captures)
        glass_to_glass: float | int = result[MEAN_LATENCY_KEY]
        self.assertEqual(FIRST_FRAME_LATENCY, glass_to_glass)

    def test_should_report_each_latency_statistic_of_several_displays(self) -> None:
        second: dict[str, bool | float | int] = {**event(1, LATER_ARRIVAL, EARLY_SENT), EVENT_NUMBER_KEY: FRAME_NUMBER + 1}
        third: dict[str, bool | float | int] = {**event(2, THIRD_ARRIVAL, THIRD_SENT), EVENT_NUMBER_KEY: FRAME_NUMBER + 2}
        result: dict[str, float | int] = self.report_of([event(0, 0, FIRST_SENT), second, third], SEVERAL_CAPTURES)
        expected: dict[str, int] = dict(mean=300, p50=200, p95=560, maximum=600)
        for statistic, value in expected.items():
            glass_to_glass: float | int = result[f"{LATENCY_KEY_PREFIX}{statistic}"]
            self.assertAlmostEqual(value, glass_to_glass)

    def test_should_count_the_events_and_captures_of_a_latency_report(self) -> None:
        second: dict[str, bool | float | int] = {
            **event(1, LATER_ARRIVAL, EARLY_SENT, sent_to=2),
            EVENT_NUMBER_KEY: FRAME_NUMBER + 1,
            "keyframe": False,
            "behind": 1,
            "waiting": 2,
            "backlog": 4,
            "colors": -1,
        }
        third: dict[str, bool | float | int] = {**event(2, THIRD_ARRIVAL, THIRD_SENT), EVENT_NUMBER_KEY: FRAME_NUMBER + 2, "colors": 0}
        result: dict[str, float | int] = self.report_of([event(0, 0, FIRST_SENT), second, third], SEVERAL_CAPTURES)
        expected: dict[str, float | int] = {
            SOURCE_FRAMES_KEY: 3,
            "encoded": 3,
            "keyframes": 2,
            "sent_to_viewers": 4,
            "held_back_for_backlog": 1,
            "held_back_for_reference": 2,
            "not_sent_too_large": 1,
            "backlog_maximum": 4,
            "backlog_p95": 3.6,
            "captured": 3,
            CAPTURE_SECONDS_KEY: 0.7,
            "displayed": 3,
            AMBIGUOUS_DISPLAYED_KEY: 0,
            DISPLAYED_FRAMES_PER_SECOND_KEY: 3 / 0.7,
            "server_milliseconds_mean": 175 / 3,
            "server_milliseconds_p95": 95,
        }
        for key, value in expected.items():
            with self.subTest(key=key):
                reported: float | int = result[key]
                self.assertAlmostEqual(value, reported)

    def test_should_report_no_capture_time_and_no_source_frames_for_one_capture_without_events(self) -> None:
        result: dict[str, float | int] = self.report_of([], ((CAPTURE_TIME, FRAME_NUMBER),))
        capture_seconds: float | int = result[CAPTURE_SECONDS_KEY]
        self.assertTrue(math.isnan(capture_seconds))

        source_frames: float | int = result[SOURCE_FRAMES_KEY]
        self.assertEqual(0, source_frames)

    def test_should_report_no_display_rate_for_captures_at_one_time(self) -> None:
        captures: tuple[tuple[int, int | None], ...] = ((CAPTURE_TIME, FRAME_NUMBER), (CAPTURE_TIME, FRAME_NUMBER))
        result: dict[str, float | int] = self.report_of([event(0, 0, FIRST_SENT)], captures)
        frames_per_second: float | int = result[DISPLAYED_FRAMES_PER_SECOND_KEY]
        self.assertTrue(math.isnan(frames_per_second))

    def test_should_skip_events_without_a_number_or_a_finite_arrival(self) -> None:
        unnumbered: dict[str, bool | float | int | None] = {**event(1, LATER_ARRIVAL, EARLY_SENT), EVENT_NUMBER_KEY: None}
        nonfinite_arrival: dict[str, bool | float | int] = {**event(2, math.nan, EARLY_SENT), EVENT_NUMBER_KEY: FRAME_NUMBER + 100}
        result: dict[str, float | int] = self.report_of([event(0, 0, FIRST_SENT), unnumbered, nonfinite_arrival], READABLE_CAPTURES)
        source_frames: float | int = result[SOURCE_FRAMES_KEY]
        self.assertEqual(1, source_frames)

    def report(
        self,
        later: dict[str, bool | float | int] | dict[str, bool | int],
        captures: tuple[tuple[int, int | None], ...] = READABLE_CAPTURES,
    ) -> dict[str, float | int]:
        events: list[dict[str, bool | float | int] | dict[str, bool | int]] = [event(0, 0, FIRST_SENT), later]
        return self.report_of(events, captures)

    def report_of(
        self,
        events: list[dict[str, bool | float | int | None] | dict[str, bool | float | int] | dict[str, bool | int]],
        captures: tuple[tuple[int, int | None], ...],
    ) -> dict[str, float | int]:
        with tempfile.TemporaryDirectory() as directory:
            output: Path = Path(directory, REPORT_NAME)
            arguments: list[str] = [PROGRAM, RECORDING_PATH, CAPTURE_PATH, "--json", str(output)]
            with (
                patch.object(sys, "argv", arguments),
                patch.object(mcv2_tools, "latency_events", return_value=events),
                patch.object(mcv2_tools, "latency_captures", return_value=list(captures)),
                redirect_stdout(StringIO()),
            ):
                mcv2_tools.latency_main()

            return json.loads(output.read_text())


def event(frame: int, arrived: int | float, sent: int | float, sent_to: int = 1) -> dict[str, bool | float | int] | dict[str, bool | int]:
    return dict(frame=frame, arrived=arrived, sent=sent, sent_to=sent_to, number=FRAME_NUMBER,
                keyframe=True, bytes=EVENT_BYTES, colors=EVENT_COLORS, behind=0, waiting=0, backlog=0)


if __name__ == "__main__":
    unittest.main()
