"""Regenerate the MCV2 tables mcav-bukkit loads at run time, from the reference in tools/mcv2-reference.

    python tools/mcv2/tables.py [--check]

- fitting_matrices.bin: the encoder's least-squares grid fits, the reference's own float32 pseudo-inverses of the
  decoder's bilinear interpolation (pixels.fitting_matrix), for block sizes 8, 16 and 32 and grid widths 1, 2, 4 and 8
  in that order, each a grid-by-size matrix, row by row, little-endian (Fits reads them in this layout).
- residual_books.bin: the immutable VQ/PQ residual books of the compact classes. They are data, not computed: the
  reference's copy (research_artifacts/residual_books.bin) is written as it is, after its SHA-256 manifest check.

With --check nothing is written and the exit code is 1 when a committed table differs. Both are reproduced byte for
byte with the pinned requirements (numpy 2.5.3); numpy computes the pseudo-inverses, so another numpy or LAPACK may
round a last bit differently, which is why mcav loads the committed bytes instead of computing them.
"""

import argparse
import sys
from pathlib import Path

REPOSITORY = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPOSITORY / "tools/mcv2-reference"))

from mcvideo.codebooks import book_bytes  # noqa: E402
from mcvideo.pixels import fitting_matrix  # noqa: E402

RESOURCES = REPOSITORY / "mcav-bukkit/src/main/resources/me/brandonli/mcav/bukkit/media/mcv2"


def fitting_matrices():
    return b"".join(fitting_matrix(size, grid).astype("<f4").tobytes() for size in (8, 16, 32) for grid in (1, 2, 4, 8))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="compare with the committed tables, write nothing")
    arguments = parser.parse_args()
    differing = []
    for name, data in (("fitting_matrices.bin", fitting_matrices()), ("residual_books.bin", book_bytes())):
        path = RESOURCES / name
        if arguments.check:
            if path.read_bytes() != data:
                differing.append(name)
        else:
            path.write_bytes(data)
    for name in differing:
        print(name, "differs from the reference")
    sys.exit(1 if differing else 0)


if __name__ == "__main__":
    main()
