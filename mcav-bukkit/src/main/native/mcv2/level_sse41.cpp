/*
 * This file is part of mcav, a media playback library for Java
 * Copyright (C) Brandon Li <https://brandonli.me/>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

// The sse41 dispatch level.
#define MCV2_SIMD_SSE41
#include "kernels.hpp"
#include "mcv2_kernels.h"
#define MCV2_PREFIX(name) mcv2_sse41_##name
// NatBench on the i7-8700: 16-pixel cell sums ran 13 % slower here than on SSE2, whose code the CPU also runs, and an
// 8-pixel fit up to 14 % slower or 6 % faster as the library's code alignment moved (this CPU's jump-alignment penalty)
#define MCV2_FIT_TO(name) mcv2_sse2_##name
#define MCV2_FIT_AT 8
#define MCV2_CELL_SUMS_TO(name) mcv2_sse2_##name
#define MCV2_CELL_SUMS_AT 16
#include "exports.inc"
