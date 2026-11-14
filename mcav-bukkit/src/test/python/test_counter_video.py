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

import sys
import tempfile
import unittest
from contextlib import redirect_stderr
from io import StringIO
from pathlib import Path
from unittest.mock import patch

import numpy

import mcv2_tools

WIDTH: int = 768
HEIGHT: int = 40
CHANNELS: int = 3

FRAME_SHAPE: tuple[int, int, int] = (HEIGHT, WIDTH, CHANNELS)
FRAME_SIZE: int = WIDTH * HEIGHT * CHANNELS

PROGRAM: str = "mcv2_tools.py counter_video"
FRAMES_PER_SECOND: str = "5"

CLIP_FILE: str = "clip.rgb"
OUT_FILE: str = "counter.rgb"


class CounterVideoTest(unittest.TestCase):
    def test_should_read_back_the_number_it_stamps(self) -> None:
        for number in [0, 1, 0x5A5A5, 0xFFFFF]:
            with self.subTest(number=number):
                frame: numpy.ndarray = numpy.zeros(FRAME_SHAPE, numpy.uint8)
                mcv2_tools.counter_video_stamp(frame, number)

                lumas: list[int] = self.sample_lumas(frame)
                read: int | None = mcv2_tools.counter_video_read(lumas)
                self.assertEqual(number, read)

    def test_should_loop_the_clip_forward_and_backward_and_stamp_each_frame(self) -> None:
        grays: numpy.ndarray = numpy.array([10, 20, 30], numpy.uint8)
        clip: numpy.ndarray = numpy.repeat(grays, FRAME_SIZE)
        status: str | int | None
        output: bytes
        status, output = self.run_counter_video(clip, WIDTH, HEIGHT, "1")
        self.assertEqual(0, status)

        pixels: numpy.ndarray = numpy.frombuffer(output, numpy.uint8)
        frames: numpy.ndarray = pixels.reshape(-1, HEIGHT, WIDTH, CHANNELS)
        bottom_grays: list[int] = frames[:, -1, 0, 0].tolist()
        self.assertEqual([10, 20, 30, 20, 10], bottom_grays)

        lumas: list[list[int]] = [self.sample_lumas(frame) for frame in frames]
        numbers: list[int | None] = [mcv2_tools.counter_video_read(sample) for sample in lumas]
        self.assertEqual([0, 1, 2, 3, 4], numbers)

    def test_should_exit_when_the_clip_has_no_frame_it_can_stamp(self) -> None:
        for width, height, pixels in [(767, HEIGHT, 767 * HEIGHT), (WIDTH, 31, WIDTH * 31), (WIDTH, HEIGHT, WIDTH)]:
            with self.subTest(width=width, height=height, pixels=pixels):
                clip: numpy.ndarray = numpy.zeros(pixels * CHANNELS, numpy.uint8)
                status: str | int | None
                _: bytes
                status, _ = self.run_counter_video(clip, width, height, "1")
                self.assertEqual("The clip must have a frame at least 768 pixels wide and 32 pixels high!", status)

    def test_should_exit_2_when_the_seconds_give_no_frame(self) -> None:
        clip: numpy.ndarray = numpy.zeros(WIDTH * HEIGHT * CHANNELS, numpy.uint8)
        status: str | int | None
        _: bytes
        with redirect_stderr(StringIO()):
            status, _ = self.run_counter_video(clip, WIDTH, HEIGHT, "0")

        self.assertEqual(2, status)

    def sample_lumas(self, frame: numpy.ndarray) -> list[int]:
        offset: int = mcv2_tools.LATENCY_SAMPLE_OFFSET
        block: int = mcv2_tools.COUNTER_VIDEO_BLOCK
        samples: numpy.ndarray = frame[offset, offset::block, 0]
        return samples.tolist()

    def run_counter_video(self, clip: numpy.ndarray, width: int, height: int, seconds: str) -> tuple[str | int | None, bytes]:
        with tempfile.TemporaryDirectory() as directory:
            clip_path: Path = Path(directory) / CLIP_FILE
            out_path: Path = Path(directory) / OUT_FILE
            clip_bytes: bytes = clip.tobytes()
            clip_path.write_bytes(clip_bytes)
            out_path.touch()
            arguments: list[str] = [PROGRAM, str(clip_path), str(width), str(height), FRAMES_PER_SECOND, seconds, str(out_path)]
            with patch.object(sys, "argv", arguments), self.assertRaises(SystemExit) as context:
                mcv2_tools.counter_video_main()

            output: bytes = out_path.read_bytes()

        return context.exception.code, output


if __name__ == "__main__":
    unittest.main()
