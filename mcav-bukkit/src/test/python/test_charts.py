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
from contextlib import redirect_stdout
from io import StringIO
from typing import Any

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

    def test_should_sign_each_percentage_and_print_a_zero_unsigned(self) -> None:
        for value, expected in [(1.5, "+1.5%"), (-2.0, "-2.0%"), (0.04, "0.0%"), (-0.04, "0.0%")]:
            with self.subTest(value=value):
                text: str = mcv2_tools.charts_signed(value)
                self.assertEqual(expected, text)

    def test_should_list_the_removed_features_by_their_mean_worth_largest_first(self) -> None:
        removed: list[dict[str, Any]] = [removed_feature("smaller", -1.0, 0.0, -0.5), removed_feature("larger", 3.0, 2.0, 2.5)]
        output: StringIO = StringIO()
        with redirect_stdout(output):
            mcv2_tools.charts_print_removed_table(removed)

        rows: list[str] = output.getvalue().splitlines()[-2:]
        self.assertEqual(["| larger | +3.0% | +2.0% | +2.5% |", "| smaller | -1.0% | 0.0% | -0.5% |"], rows)


def removed_feature(name: str, proxy: float, gameplay: float, mean: float) -> dict[str, Any]:
    rates: dict[str, float] = {mcv2_tools.PROXY_SOURCE: proxy, mcv2_tools.GAMEPLAY_SOURCE: gameplay, mcv2_tools.BD_RATE_MEAN_KEY: mean}
    return {mcv2_tools.FEATURE_NAME_KEY: name, mcv2_tools.BD_RATE_KEY: rates}


if __name__ == "__main__":
    unittest.main()
