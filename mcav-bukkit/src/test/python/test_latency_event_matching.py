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
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from io import StringIO
from pathlib import Path
from typing import Any
from unittest.mock import patch

import mcv2_tools

PROGRAM: str = "mcv2_tools.py"
REPORT_NAME: str = "report.json"
RECORDING_PATH: str = "unused.jfr"
CAPTURE_PATH: str = "unused.nut"

STREAM_OPTION: str = "--stream"
STREAM_FRAMES: int = 3

SUCCESS_EXIT_CODE: int = 0

BRIGHT_LUMA: int = mcv2_tools.COUNTER_VIDEO_THRESHOLD
DARK_LUMA: int = mcv2_tools.COUNTER_VIDEO_THRESHOLD - 1

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
DISPLAYED_KEY: str = "displayed"


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
            DISPLAYED_KEY: 3,
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

    def test_should_match_stream_sends_by_their_frame_in_the_stream_and_skip_unsent_ones(self) -> None:
        displayed_send: dict[str, bool | int] = send_values(STREAM_FRAMES + 1, 0, EVENT_COLORS)
        unsent: dict[str, bool | int] = send_values(STREAM_FRAMES + 2, EARLY_SENT, -1)
        recording: subprocess.CompletedProcess[str] = jfr_run([displayed_send, unsent])
        captures: tuple[tuple[int, int | None], ...] = ((CAPTURE_TIME, 1), (LATER_CAPTURE_TIME, 2))
        with patch.object(mcv2_tools.subprocess, "run", return_value=recording):
            result: dict[str, float | int] = self.report_of([], captures, (STREAM_OPTION, str(STREAM_FRAMES)))

        displayed: float | int = result[DISPLAYED_KEY]
        self.assertEqual(1, displayed)

        glass_to_glass: float | int = result[MEAN_LATENCY_KEY]
        self.assertEqual(FIRST_FRAME_LATENCY, glass_to_glass)

    def test_should_read_the_utc_times_and_the_counter_number_of_a_frame_event(self) -> None:
        fingerprint: str = bytes(counter_lumas(FRAME_NUMBER)).hex()
        values: dict[str, bool | int | str] = {
            **send_values(1, 0, EVENT_COLORS),
            mcv2_tools.SENT_FIELD: "1970-01-01T00:00:00.100+00:00",
            mcv2_tools.ARRIVED_FIELD: "1970-01-01T00:00:00.150+00:00",
            mcv2_tools.FINGERPRINT_FIELD: fingerprint,
        }
        recording: subprocess.CompletedProcess[str] = jfr_run([values])
        with patch.object(mcv2_tools.subprocess, "run", return_value=recording):
            events: list[Any] = mcv2_tools.latency_events(RECORDING_PATH, mcv2_tools.JFR_COMMAND)

        read: dict[str, Any] = events[0]
        sent: float = read["sent"]
        arrived: float = read["arrived"]
        self.assertAlmostEqual(FIRST_SENT, sent)
        self.assertAlmostEqual(LATER_ARRIVAL, arrived)

        number: Any = read[EVENT_NUMBER_KEY]
        self.assertEqual(FRAME_NUMBER, number)

    def test_should_read_no_counter_number_from_a_missing_short_or_unsynchronized_fingerprint(self) -> None:
        lumas: list[int] = counter_lumas(FRAME_NUMBER)
        short: str = bytes(lumas[:-1]).hex()
        unsynchronized: str = bytes([*lumas[:-1], BRIGHT_LUMA]).hex()
        missing: dict[str, bool | int] = {**send_values(0, 0, EVENT_COLORS), mcv2_tools.ARRIVED_FIELD: 0}
        values: list[dict[str, bool | int | str]] = [
            missing,
            {**missing, mcv2_tools.FINGERPRINT_FIELD: short},
            {**missing, mcv2_tools.FINGERPRINT_FIELD: unsynchronized},
        ]
        recording: subprocess.CompletedProcess[str] = jfr_run(values)
        with patch.object(mcv2_tools.subprocess, "run", return_value=recording):
            events: list[Any] = mcv2_tools.latency_events(RECORDING_PATH, mcv2_tools.JFR_COMMAND)

        numbers: list[Any] = [event[EVENT_NUMBER_KEY] for event in events]
        self.assertEqual([None, None, None], numbers)

    def test_should_reject_a_negative_stream_length(self) -> None:
        with (
            patch.object(mcv2_tools, "latency_sends", return_value=[]),
            redirect_stderr(StringIO()),
            self.assertRaises(SystemExit) as status,
        ):
            self.report_of([], (), (STREAM_OPTION, "-1"))

        self.assertEqual(2, status.exception.code)

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
        options: tuple[str, ...] = (),
    ) -> dict[str, float | int]:
        with tempfile.TemporaryDirectory() as directory:
            output: Path = Path(directory, REPORT_NAME)
            arguments: list[str] = [PROGRAM, RECORDING_PATH, CAPTURE_PATH, "--json", str(output), *options]
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


def send_values(frame: int, sent: int, colors: int) -> dict[str, bool | int]:
    return {mcv2_tools.FRAME_ID_FIELD: frame, mcv2_tools.KEYFRAME_FIELD: True, mcv2_tools.BYTES_FIELD: EVENT_BYTES,
            mcv2_tools.COLORS_FIELD: colors, mcv2_tools.SENT_FIELD: sent, mcv2_tools.SENT_TO_FIELD: 1,
            mcv2_tools.BEHIND_FIELD: 0, mcv2_tools.WAITING_FIELD: 0, mcv2_tools.BACKLOG_FIELD: 0}


def jfr_run(values: list[dict[str, Any]]) -> subprocess.CompletedProcess[str]:
    events: list[dict[str, Any]] = [{mcv2_tools.JFR_VALUES_KEY: value} for value in values]
    recording: dict[str, Any] = {mcv2_tools.JFR_RECORDING_KEY: {mcv2_tools.JFR_EVENTS_KEY: events}}
    output: str = json.dumps(recording)
    return subprocess.CompletedProcess(args=[], returncode=SUCCESS_EXIT_CODE, stdout=output)


def counter_lumas(number: int) -> list[int]:
    bits: list[int] = [(number >> position) & 1 for position in range(mcv2_tools.COUNTER_VIDEO_BITS)]
    return [BRIGHT_LUMA if bit else DARK_LUMA for bit in [*bits, *mcv2_tools.COUNTER_VIDEO_SYNCHRONIZATION_BITS]]


if __name__ == "__main__":
    unittest.main()
