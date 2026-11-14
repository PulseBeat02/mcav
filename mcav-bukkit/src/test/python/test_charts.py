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

import mcv2_tools

LOWER_POINT: tuple[float, float] = (1.0, 80.0)
UPPER_POINT: tuple[float, float] = (4.0, 90.0)


class ChartsTablesTest(unittest.TestCase):
    def test_should_interpolate_the_rate_log_linearly_between_measured_points(self) -> None:
        for level, expected in [(80, 1.0), (85, 2.0), (90, 4.0)]:
            with self.subTest(level=level):
                rate: float | None = mcv2_tools.charts_rate_at([UPPER_POINT, LOWER_POINT], level)
                self.assertAlmostEqual(expected, rate)

    def test_should_have_no_rate_outside_the_measured_points(self) -> None:
        for level in [75, 95]:
            with self.subTest(level=level):
                rate: float | None = mcv2_tools.charts_rate_at([UPPER_POINT, LOWER_POINT], level)
                self.assertIsNone(rate)

    def test_should_have_no_rate_between_points_of_the_same_quality(self) -> None:
        rate: float | None = mcv2_tools.charts_rate_at([(1.0, 80.0), (2.0, 80.0)], 80)
        self.assertIsNone(rate)


if __name__ == "__main__":
    unittest.main()
