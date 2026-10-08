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

"""Generate or check the encoder's fitting matrices; v3 has no residual books.

The binary resource layout remains sizes 8,16,32 with grid widths 1,2,4,8 in
that order. V3 uses only the four-node grid. Keeping the unused matrix slots
preserves the existing resource offsets.
The normative decoder does not import or use this fitting utility.
"""

import argparse
import sys
from pathlib import Path

import numpy as np

REPOSITORY = Path(__file__).resolve().parents[2]
RESOURCES = REPOSITORY / 'mcav-bukkit/src/main/resources/me/brandonli/mcav/bukkit/media/mcv2'


def fitting_matrix(size, grid):
    position = np.clip((np.arange(size, dtype=np.float32) + 0.5) * grid / size - 0.5, 0, grid - 1)
    lower = np.floor(position).astype(np.int32)
    upper = np.minimum(lower + 1, grid - 1)
    fraction = position - lower
    weights = np.zeros((size, grid), np.float32)
    for pixel in range(size):
        weights[pixel, lower[pixel]] += 1 - fraction[pixel]
        weights[pixel, upper[pixel]] += fraction[pixel]
    return np.linalg.pinv(weights).astype(np.float32)


def fitting_matrices():
    return b''.join(fitting_matrix(size, grid).astype('<f4').tobytes() for size in (8, 16, 32) for grid in (1, 2, 4, 8))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='compare the committed fitting resource without writing')
    arguments = parser.parse_args()
    path = RESOURCES / 'fitting_matrices.bin'
    data = fitting_matrices()
    if arguments.check:
        if path.read_bytes() != data:
            print(f'{path} differs from generated fitting matrices')
            sys.exit(1)
    else:
        path.write_bytes(data)


if __name__ == '__main__':
    main()
