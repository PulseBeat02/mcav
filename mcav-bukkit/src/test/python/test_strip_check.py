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
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
from typing import Any
from unittest.mock import patch

import numpy
from PIL import Image

import mcv2_reference
from mcv2_reference import make_pages
from mcv2_reference import pack_frame

import mcv2_tools

PAGE_ROWS: int = mcv2_tools.PAGE_PIXELS // mcv2_reference.MAP_SIDE

PROGRAM: str = "mcv2_tools.py"
STRIP_CHECK: str = "strip_check"
VIDEO_WIDTH_OPTION: str = "--video-width"

SLOTS: int = 1
VIDEO_WIDTH: int = mcv2_reference.MAP_SIDE
SQUARES_WIDTH: int = VIDEO_WIDTH + mcv2_tools.STRIP_CHECK_MARGIN + (SLOTS + mcv2_tools.STRIP_CHECK_EXTRA_SQUARES) * mcv2_tools.STRIP_CHECK_STEP

NOTES_FILE: str = "notes.txt"
CAPTURE_FILE: str = "capture.png"
DESCRIPTOR_FAILURE: str = f"{CAPTURE_FILE}: descriptor"

SQUARES_KEY: str = "captures_with_squares"
DESCRIPTORS_KEY: str = "descriptor_rows"

TWO_PAGE_WIDTH: int = 3200
TWO_PAGE_LEAVES: int = 100
PALETTE_BYTES: int = 134


class StripCheckTest(unittest.TestCase):
    def test_should_return_the_exact_six_bit_symbols_of_an_rgb_strip_and_read_its_page(self) -> None:
        frame: bytes = pack_frame(1, 1, 7, 7, {})
        symbols: bytes = make_pages(frame, 5)[0]
        padded: numpy.ndarray = numpy.zeros(mcv2_reference.PAGE_SYMBOLS, numpy.uint32)
        padded[:len(symbols)] = numpy.frombuffer(symbols, numpy.uint8)
        groups: numpy.ndarray = padded.reshape(-1, mcv2_tools.SYMBOLS_PER_PIXEL)
        words: numpy.ndarray = groups[:, 0] | groups[:, 1] << 6 | groups[:, 2] << 12 | groups[:, 3] << 18
        pixels: numpy.ndarray = numpy.stack((words & 255, words >> 8 & 255, words >> 16), axis=1).astype(numpy.uint8)
        image: numpy.ndarray = pixels.reshape(PAGE_ROWS, mcv2_reference.MAP_SIDE, mcv2_reference.CHANNELS)
        actual: numpy.ndarray = mcv2_tools.strip_check_page_symbols(image, 0, PAGE_ROWS)
        actual_bytes: bytes = actual.tobytes()
        self.assertEqual(padded.astype(numpy.uint8).tobytes(), actual_bytes)

        page: mcv2_reference.Page = mcv2_tools.strip_check_read_strip_page(actual)
        payload: bytes = page.payload
        self.assertEqual(frame, payload)

        fields: tuple[int, int, int] = (page.stream_id, page.frame_id, page.symbol_bits)
        self.assertEqual((5, 7, 6), fields)

    def test_should_reject_invalid_and_truncated_strip_pages(self) -> None:
        for symbols in (numpy.zeros(0, numpy.uint8), numpy.zeros(42, numpy.uint8), numpy.zeros(mcv2_reference.PAGE_SYMBOLS, numpy.uint8)):
            with self.assertRaises(ValueError):
                mcv2_tools.strip_check_read_strip_page(symbols)

        frame: bytes = pack_frame(1, 1, 0, 0, {})
        damaged: numpy.ndarray = numpy.frombuffer(make_pages(frame)[0], numpy.uint8).copy()
        damaged[50] ^= 1
        with self.assertRaisesRegex(ValueError, 'CRC'):
            mcv2_tools.strip_check_read_strip_page(damaged)

    def test_should_read_the_page_symbols_of_the_slot_it_is_given(self) -> None:
        screen: numpy.ndarray = blank_screen(2 * PAGE_ROWS)
        screen[PAGE_ROWS:, :, 0] = 1
        symbols: numpy.ndarray = mcv2_tools.strip_check_page_symbols(screen, 1, PAGE_ROWS)
        actual: bytes = symbols.tobytes()
        self.assertEqual(bytes((1, 0, 0, 0)) * mcv2_tools.PAGE_PIXELS, actual)

    def test_should_read_each_page_of_a_frame_longer_than_one_page(self) -> None:
        frame: bytes = two_page_frame()
        first_symbols: bytes
        last_symbols: bytes
        first_symbols, last_symbols = make_pages(frame)
        first: numpy.ndarray = padded_symbols(first_symbols)
        last: numpy.ndarray = padded_symbols(last_symbols)
        capacity: int = mcv2_reference.page_capacity()
        first_page: mcv2_reference.Page = mcv2_tools.strip_check_read_strip_page(first)
        first_payload: bytes = first_page.payload
        self.assertEqual(frame[:capacity], first_payload)

        last_page: mcv2_reference.Page = mcv2_tools.strip_check_read_strip_page(last)
        last_payload: bytes = last_page.payload
        self.assertEqual(frame[capacity:], last_payload)

        run: numpy.ndarray = numpy.concatenate((first, last))
        run_page: mcv2_reference.Page = mcv2_tools.strip_check_read_strip_page(run)
        run_payload: bytes = run_page.payload
        self.assertEqual(frame[:capacity], run_payload)

    def test_should_read_one_page_of_symbols_from_a_slot_taller_than_a_page(self) -> None:
        screen: numpy.ndarray = blank_screen(PAGE_ROWS + 1)
        screen[PAGE_ROWS:, :, 0] = 1
        symbols: numpy.ndarray = mcv2_tools.strip_check_page_symbols(screen, 0, PAGE_ROWS + 1)
        actual: bytes = symbols.tobytes()
        self.assertEqual(bytes(mcv2_reference.PAGE_SYMBOLS), actual)

    def test_should_exit_with_the_usage_status_when_the_folder_holds_no_png_screenshot(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            captures: Path = Path(directory)
            notes: Path = captures / NOTES_FILE
            notes.touch()

            status: int | str | None
            lines: list[str]
            status, lines = self.run_strip_check(captures)

        self.assertEqual(mcv2_tools.USAGE_EXIT_CODE, status)
        self.assertEqual([], lines)

    def test_should_read_the_squares_of_a_capture_just_wide_enough_for_them(self) -> None:
        screen: numpy.ndarray = blank_capture(SQUARES_WIDTH)
        summary: Any
        _, _, summary = self.check_screen(screen)
        squares: Any = summary[SQUARES_KEY]
        self.assertEqual(1, squares)

    def test_should_skip_the_squares_of_a_capture_narrower_than_they_need(self) -> None:
        screen: numpy.ndarray = blank_capture(SQUARES_WIDTH - 1)
        summary: Any
        _, _, summary = self.check_screen(screen)
        squares: Any = summary[SQUARES_KEY]
        self.assertEqual(0, squares)

    def test_should_count_the_anchor_descriptor_of_its_screen(self) -> None:
        screen: numpy.ndarray = blank_capture(SQUARES_WIDTH)
        row: int = mcv2_tools.page_rows(SQUARES_WIDTH) * SLOTS
        screen[row, : len(mcv2_tools.STRIP_CHECK_DESCRIPTOR)] = mcv2_tools.STRIP_CHECK_DESCRIPTOR
        failures: list[str]
        summary: Any
        _, failures, summary = self.check_screen(screen)
        descriptors: Any = summary[DESCRIPTORS_KEY]
        self.assertEqual(1, descriptors)
        self.assertNotIn(DESCRIPTOR_FAILURE, failures)

    def test_should_report_a_capture_without_the_anchor_descriptor(self) -> None:
        screen: numpy.ndarray = blank_capture(SQUARES_WIDTH)
        failures: list[str]
        summary: Any
        _, failures, summary = self.check_screen(screen)
        descriptors: Any = summary[DESCRIPTORS_KEY]
        self.assertEqual(0, descriptors)
        self.assertIn(DESCRIPTOR_FAILURE, failures)

    def check_screen(self, screen: numpy.ndarray) -> tuple[int | str | None, list[str], Any]:
        with tempfile.TemporaryDirectory() as directory:
            captures: Path = Path(directory)
            image: Image.Image = Image.fromarray(screen)
            image.save(captures / CAPTURE_FILE)
            status: int | str | None
            lines: list[str]
            status, lines = self.run_strip_check(captures)

        summary: Any = json.loads(lines[-1])
        return status, lines[:-1], summary

    def run_strip_check(self, captures: Path) -> tuple[int | str | None, list[str]]:
        arguments: list[str] = [PROGRAM, STRIP_CHECK, str(captures), mcv2_tools.SLOTS_OPTION, str(SLOTS), VIDEO_WIDTH_OPTION, str(VIDEO_WIDTH)]
        output: StringIO = StringIO()
        with (
            patch.object(sys, "argv", arguments),
            redirect_stdout(output),
            self.assertRaises(SystemExit) as raised,
        ):
            mcv2_tools.main()

        text: str = output.getvalue()
        return raised.exception.code, text.splitlines()


def blank_screen(rows: int) -> numpy.ndarray:
    return numpy.zeros((rows, mcv2_reference.MAP_SIDE, mcv2_reference.CHANNELS), numpy.uint8)


def blank_capture(width: int) -> numpy.ndarray:
    rows: int = mcv2_tools.page_rows(width) * SLOTS + mcv2_tools.STRIP_CHECK_DEFAULT_SCREENS + mcv2_tools.STRIP_CHECK_SQUARE
    return numpy.zeros((rows, width, mcv2_reference.CHANNELS), numpy.uint8)


def two_page_frame() -> bytes:
    roots: dict[int, mcv2_reference.Node] = {
        index: mcv2_reference.Node(mcv2_reference.PALETTE, record=bytes(PALETTE_BYTES)) for index in range(TWO_PAGE_LEAVES)
    }
    return pack_frame(TWO_PAGE_WIDTH, mcv2_reference.SUPERBLOCK_SIZE, 0, 0, roots)


def padded_symbols(symbols: bytes) -> numpy.ndarray:
    padded: numpy.ndarray = numpy.zeros(mcv2_reference.PAGE_SYMBOLS, numpy.uint8)
    padded[: len(symbols)] = numpy.frombuffer(symbols, numpy.uint8)
    return padded


if __name__ == '__main__':
    unittest.main()
