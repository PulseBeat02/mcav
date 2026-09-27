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

// The sve512 dispatch level.
#define MCV2_SIMD_SVE512
#include "kernels.hpp"
#include "mcv2_kernels.h"
#define MCV2_PREFIX(name) mcv2_sve512_##name
// blocks narrower than a vector go to the neon kernels
#define MCV2_NARROW(name) mcv2_neon_##name
#define MCV2_NARROW_BELOW 16
#include "exports.inc"
