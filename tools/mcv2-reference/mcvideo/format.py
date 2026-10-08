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

"""Constants of the MCV2 version 3 specification."""

import struct

MAGIC = b"MCV2"
VERSION = 3
HEADER = struct.Struct("<4sIHHII")
HEADER_BYTES = 20
MAGIC_OFFSET = 0
VERSION_OFFSET = 4
WIDTH_OFFSET = 8
HEIGHT_OFFSET = 10
FRAME_ID_OFFSET = 12
REFERENCE_ID_OFFSET = 16

KEYFRAME = 1
MIN_DIMENSION = 1
MAX_DIMENSION = 4096
MIN_FRAME_BYTES = 20
MAX_FRAME_BYTES = 131071
ID_MASK = 0xFFFFFFFF
ID_HALF_RANGE = 0x80000000
SUPERBLOCK_SIZE = 32
LEAF_SIZES = (32, 16, 8)
MASK_BITS = 32
DIRECTORY_GROUPS = 8
WALK_STRIDE = 8
WALK_CURSOR_BITS = 17
MAX_QUANTIZER = 2

SKIP, MOTION, SOLID, PALETTE, PATTERN, COMPACT, SPLIT = range(7)
MODE_MASK = 31
QUANTIZER_SHIFT = 5
COMPACT_BYTES = 10

PAGE_MAGIC = b"MCP1"
PAGE_VERSION = 1
SYMBOL_BITS = 6
PAGE_SYMBOLS = 128 * 128
PAGE_HEADER_BYTES = 32
PAGE_CAPACITY = 12256
MAX_PENDING = 4
MAX_PAGE_SLOTS = 8
SCREEN_CAPACITY = MAX_PAGE_SLOTS * PAGE_CAPACITY
