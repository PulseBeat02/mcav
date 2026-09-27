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

// The C interface of the MCV2 live encoder's native pixel kernels. Every kernel is exported once per dispatch level,
// as mcv2_<level>_<kernel>, from the same source; Java chooses the level from mcv2_cpu_levels() and binds its symbols.
// Java validates every size, offset and array length before a call: a kernel reads and writes only inside the arrays
// it is given, allocates nothing, and keeps no state between calls. The kernels are the Java encoder's, operation for
// operation: every result is identical to Java's, which the tests check.
#ifndef MCV2_KERNELS_H
#define MCV2_KERNELS_H

#include <stdint.h>

#if defined(_WIN32)
#define MCV2_EXPORT __declspec(dllexport)
#else
#define MCV2_EXPORT __attribute__((visibility("default")))
#endif

// The version of this interface: Java refuses a library whose version differs from the one it was written for.
#define MCV2_ABI 1

// The dispatch levels, as bits of mcv2_cpu_levels().
#define MCV2_LEVEL_SCALAR 1
#define MCV2_LEVEL_SSE41 2
#define MCV2_LEVEL_AVX2 4
#define MCV2_LEVEL_NEON 8

// The kernels of one level: X(return type, name, parameters). The scored reconstructions return the block's
// distortion once every row is measured, or -1 at the first row after which the candidate can no longer be cheaper
// than the limit, where Java's measure stops.
#define MCV2_KERNELS(X)                                                                                                \
  X(int64_t, predicted,                                                                                                \
    (const int32_t *prediction, int32_t size, int32_t *out, const int32_t *source, double rate, double limit))         \
  X(int64_t, solid, (int32_t color, int32_t size, int32_t * out, const int32_t *source, double rate, double limit))    \
  X(int64_t, palette,                                                                                                  \
    (const int8_t *record, int32_t offset, int32_t size, int32_t *out, const int32_t *source, double rate,             \
     double limit))                                                                                                    \
  X(int64_t, intra_grid,                                                                                               \
    (const int8_t *record, int32_t offset, int32_t grid, int32_t size, int32_t *out, const int32_t *source,            \
     double rate, double limit))                                                                                       \
  X(int64_t, residual_grid,                                                                                            \
    (const int32_t *prediction, const int8_t *record, int32_t offset, int32_t grid, int32_t q, int32_t size,           \
     int32_t *out, const int32_t *source, double rate, double limit))                                                  \
  X(int64_t, reduced,                                                                                                  \
    (const int32_t *prediction, int32_t intra, const int8_t *record, int32_t offset, int32_t luma, int32_t chroma,     \
     int32_t q, int32_t size, int32_t *out, const int32_t *source, double rate, double limit))                         \
  X(int64_t, compact,                                                                                                  \
    (const int32_t *prediction, const int8_t *record, int32_t body, int32_t kind, int32_t q, int32_t size,             \
     int32_t *out, const int32_t *source, double rate, double limit))                                                  \
  X(void, predict,                                                                                                     \
    (const uint8_t *reference, int32_t width, int32_t height, int32_t x, int32_t y, int32_t size, int32_t mx,          \
     int32_t my, int32_t *out))                                                                                        \
  X(void, fit,                                                                                                         \
    (const float *values, int32_t offset, int32_t stride, int32_t size, int32_t grid, const float *matrix, float *out, \
     int32_t out_offset, int32_t out_stride))                                                                          \
  X(void, cell_sums, (const int32_t *source, int32_t size, int32_t *sums))                                             \
  X(void, luma_residual, (const int32_t *source, const int32_t *prediction, int32_t size, float *nodes))               \
  X(void, cluster, (const int32_t *source, int32_t size, float *endpoints))                                            \
  X(void, palette_cluster, (const int32_t *source, int32_t count, float *endpoints))                                   \
  X(void, assign, (const int32_t *source, int32_t count, const int32_t *colors, int8_t *selectors))                    \
  X(int32_t, assign_pattern, (const int32_t *source, int32_t size, const int32_t *colors, int8_t *selectors))          \
  X(int32_t, seeded,                                                                                                   \
    (const uint8_t *reference, int32_t width, int32_t height, const int32_t *source, int32_t x, int32_t y,             \
     int32_t size, int32_t global_x, int32_t global_y, int32_t range, int32_t half_pixel, const int32_t *seeds,        \
     int32_t seed_count))                                                                                              \
  X(void, load_source,                                                                                                 \
    (const uint8_t *image, int32_t width, int32_t height, int32_t x, int32_t y, int32_t size, int32_t *source))        \
  X(void, halve, (const int32_t *block, int32_t size, int32_t *out))                                                   \
  X(void, ycocg, (const int32_t *source, int32_t count, int32_t chroma, float *out))                                   \
  X(void, residual_target,                                                                                             \
    (const float *ycocg, const int32_t *prediction, int32_t count, int32_t chroma, float *target))                     \
  X(void, cell_means,                                                                                                  \
    (const float *target, int32_t size, int32_t channel, int32_t grid, float *out, int32_t out_offset,                 \
     int32_t out_stride))

#ifdef __cplusplus
extern "C" {
#endif

// The dispatch levels this CPU runs, as a bit set of MCV2_LEVEL_*: scalar always, the others only where the CPU and
// the operating system support their instructions.
MCV2_EXPORT int32_t mcv2_cpu_levels(void);

// MCV2_ABI of the library.
MCV2_EXPORT int32_t mcv2_abi(void);

#ifdef __cplusplus
}
#endif

#endif
