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

import unittest

import numpy

import mcv2_tools

WIDTH: int = 768
HEIGHT: int = 40
CHANNELS: int = 3

FRAME_SHAPE: tuple[int, int, int] = (HEIGHT, WIDTH, CHANNELS)


class CounterVideoTest(unittest.TestCase):
    def test_should_read_back_the_number_it_stamps(self) -> None:
        for number in [0, 1, 0x5A5A5, 0xFFFFF]:
            with self.subTest(number=number):
                frame: numpy.ndarray = numpy.zeros(FRAME_SHAPE, numpy.uint8)
                mcv2_tools.counter_video_stamp(frame, number)

                lumas: list[int] = self.sample_lumas(frame)
                read: int | None = mcv2_tools.counter_video_read(lumas)
                self.assertEqual(number, read)

    def sample_lumas(self, frame: numpy.ndarray) -> list[int]:
        offset: int = mcv2_tools.LATENCY_SAMPLE_OFFSET
        block: int = mcv2_tools.COUNTER_VIDEO_BLOCK
        samples: numpy.ndarray = frame[offset, offset::block, 0]
        return samples.tolist()


if __name__ == "__main__":
    unittest.main()
