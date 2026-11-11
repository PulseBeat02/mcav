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


class TextShaderCheckTest(unittest.TestCase):
    def test_should_read_each_symbol_color_from_the_alphabet_table_and_not_from_its_constants(self) -> None:
        alphabet: str = (
            "const uint MCV2_SYMBOL_HASH = 1000u;\n"
            "const uint MCV2_SYMBOL_SHIFT = 24u;\n"
            "const uint MCV2_SYMBOLS[4] = uint[4](\n"
            "    4294967295u, 76354949u, 64u, 4294967295u);\n"
        )
        colors: numpy.ndarray = mcv2_tools.shader_check_alphabet_colors(alphabet)
        colored_symbols: list[int] = numpy.flatnonzero(colors.any(axis=1)).tolist()
        self.assertEqual([0, 5], colored_symbols)

        self.assertEqual([0x12, 0x34, 0x56], colors[5].tolist())
        self.assertEqual([0, 0, 1], colors[0].tolist())

    def test_should_write_the_anchor_magic_its_fields_and_their_check_for_a_stream(self) -> None:
        symbols: numpy.ndarray = mcv2_tools.shader_check_anchor_symbols(130)
        self.assertEqual([21, 3, 58, 44, 9, 37, 60, 17, 0, 0, 15, 9, 0, 2, 2, 28], symbols.tolist())

    def test_should_parse_a_window_size(self) -> None:
        size: tuple[int, ...] = mcv2_tools.parse_size("1280x720")
        self.assertEqual((1280, 720), size)


if __name__ == "__main__":
    unittest.main()
