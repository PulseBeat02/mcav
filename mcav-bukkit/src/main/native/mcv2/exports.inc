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

// The exported kernels of one level, included by its translation unit after it defines MCV2_PREFIX(name). The
// declarations come from the one list in mcv2_kernels.h, so a definition that strays from it does not compile.
//
// A level whose vectors are wider than a narrow block defines MCV2_NARROW(name), a narrower level's kernel that the
// same CPU runs, and MCV2_NARROW_BELOW: a kernel given a block of fewer pixels a side hands it to that level, so every
// row the wide kernels step through is whole vectors, and a narrow block runs as fast as the narrower level runs it.
// A kernel that takes two vectors of a row at a time (cluster, halve) hands off below twice that.
#define MCV2_DECLARE(type, name, parameters) MCV2_EXPORT type MCV2_PREFIX(name) parameters;

extern "C" {

MCV2_KERNELS(MCV2_DECLARE)

#if defined(MCV2_NARROW)
#define MCV2_DECLARE_NARROW(type, name, parameters) MCV2_EXPORT type MCV2_NARROW(name) parameters;
MCV2_KERNELS(MCV2_DECLARE_NARROW)
#define MCV2_HAND_OFF_BELOW(below, name, ...)                                                                          \
  if (size < (below)) {                                                                                                \
    return MCV2_NARROW(name)(__VA_ARGS__);                                                                             \
  }
#else
#define MCV2_HAND_OFF_BELOW(below, name, ...)
#endif
#define MCV2_HAND_OFF(name, ...) MCV2_HAND_OFF_BELOW(MCV2_NARROW_BELOW, name, __VA_ARGS__)

// A level may also give one kernel's blocks of one size to a narrower level that runs them faster, so no wider level
// is slower than a narrower one (NatBench): MCV2_<KERNEL>_TO(name) names that level's kernel and MCV2_<KERNEL>_AT the
// size. Every level computes the same numbers, so the result does not change.
#if defined(MCV2_FIT_TO)
MCV2_EXPORT void MCV2_FIT_TO(fit)(const float *values, int32_t offset, int32_t stride, int32_t size, int32_t grid,
                                  const float *matrix, float *out, int32_t out_offset, int32_t out_stride);
#endif
#if defined(MCV2_CELL_SUMS_TO)
MCV2_EXPORT void MCV2_CELL_SUMS_TO(cell_sums)(const int32_t *source, int32_t size, int32_t *sums);
#endif
#if defined(MCV2_CELL_MEANS_TO)
MCV2_EXPORT void MCV2_CELL_MEANS_TO(cell_means)(const float *target, int32_t size, int32_t channel, int32_t grid,
                                                float *out, int32_t out_offset, int32_t out_stride);
#endif

int64_t MCV2_PREFIX(predicted)(const int32_t *prediction, int32_t size, int32_t *out, const int32_t *source,
                               double rate, double limit) {
  MCV2_HAND_OFF(predicted, prediction, size, out, source, rate, limit)
  return mcv2::predicted(prediction, size, out, {source, rate, limit, 0});
}

int64_t MCV2_PREFIX(solid)(int32_t color, int32_t size, int32_t *out, const int32_t *source, double rate,
                           double limit) {
  MCV2_HAND_OFF(solid, color, size, out, source, rate, limit)
  return mcv2::solid(color, size, out, {source, rate, limit, 0});
}

int64_t MCV2_PREFIX(palette)(const int8_t *record, int32_t offset, int32_t size, int32_t *out, const int32_t *source,
                             double rate, double limit) {
  MCV2_HAND_OFF(palette, record, offset, size, out, source, rate, limit)
  return mcv2::palette(record, offset, size, out, {source, rate, limit, 0});
}

int64_t MCV2_PREFIX(intra_grid)(const int8_t *record, int32_t offset, int32_t grid, int32_t size, int32_t *out,
                                const int32_t *source, double rate, double limit) {
  MCV2_HAND_OFF(intra_grid, record, offset, grid, size, out, source, rate, limit)
  return mcv2::intra_grid(record, offset, grid, size, out, {source, rate, limit, 0});
}

int64_t MCV2_PREFIX(residual_grid)(const int32_t *prediction, const int8_t *record, int32_t offset, int32_t grid,
                                   int32_t q, int32_t size, int32_t *out, const int32_t *source, double rate,
                                   double limit) {
  MCV2_HAND_OFF(residual_grid, prediction, record, offset, grid, q, size, out, source, rate, limit)
  return mcv2::residual_grid(prediction, record, offset, grid, q, size, out, {source, rate, limit, 0});
}

int64_t MCV2_PREFIX(reduced)(const int32_t *prediction, int32_t intra, const int8_t *record, int32_t offset,
                             int32_t luma, int32_t chroma, int32_t q, int32_t size, int32_t *out, const int32_t *source,
                             double rate, double limit) {
  MCV2_HAND_OFF(reduced, prediction, intra, record, offset, luma, chroma, q, size, out, source, rate, limit)
  return mcv2::reduced(prediction, intra, record, offset, luma, chroma, q, size, out, {source, rate, limit, 0});
}

int64_t MCV2_PREFIX(compact)(const int32_t *prediction, const int8_t *record, int32_t body, int32_t kind, int32_t q,
                             int32_t size, int32_t *out, const int32_t *source, double rate, double limit) {
  MCV2_HAND_OFF(compact, prediction, record, body, kind, q, size, out, source, rate, limit)
  return mcv2::compact(prediction, record, body, kind, q, size, out, {source, rate, limit, 0});
}

void MCV2_PREFIX(predict)(const uint8_t *reference, int32_t width, int32_t height, int32_t x, int32_t y, int32_t size,
                          int32_t mx, int32_t my, int32_t *out) {
  MCV2_HAND_OFF(predict, reference, width, height, x, y, size, mx, my, out)
  mcv2::predict(reference, width, height, x, y, size, mx, my, out);
}

void MCV2_PREFIX(fit)(const float *values, int32_t offset, int32_t stride, int32_t size, int32_t grid,
                      const float *matrix, float *out, int32_t out_offset, int32_t out_stride) {
  MCV2_HAND_OFF(fit, values, offset, stride, size, grid, matrix, out, out_offset, out_stride)
#if defined(MCV2_FIT_TO)
  if (size == MCV2_FIT_AT) {
    return MCV2_FIT_TO(fit)(values, offset, stride, size, grid, matrix, out, out_offset, out_stride);
  }
#endif
  mcv2::fit(values, offset, stride, size, grid, matrix, out, out_offset, out_stride);
}

void MCV2_PREFIX(cell_sums)(const int32_t *source, int32_t size, int32_t *sums) {
  MCV2_HAND_OFF(cell_sums, source, size, sums)
#if defined(MCV2_CELL_SUMS_TO)
  if (size == MCV2_CELL_SUMS_AT) {
    return MCV2_CELL_SUMS_TO(cell_sums)(source, size, sums);
  }
#endif
  mcv2::cell_sums(source, size, sums);
}

void MCV2_PREFIX(luma_residual)(const int32_t *source, const int32_t *prediction, int32_t size, float *nodes) {
  MCV2_HAND_OFF(luma_residual, source, prediction, size, nodes)
  mcv2::luma_residual(source, prediction, size, nodes);
}

void MCV2_PREFIX(cluster)(const int32_t *source, int32_t size, float *endpoints) {
  // every other pixel of a block of 16 or more, two vectors of a row at a time
  MCV2_HAND_OFF_BELOW(2 * MCV2_NARROW_BELOW, cluster, source, size, endpoints)
  mcv2::cluster(source, size, endpoints);
}

void MCV2_PREFIX(palette_cluster)(const int32_t *source, int32_t count, float *endpoints) {
  mcv2::palette_cluster(source, count, endpoints);
}

void MCV2_PREFIX(assign)(const int32_t *source, int32_t count, const int32_t *colors, int8_t *selectors) {
  mcv2::assign(source, count, colors, selectors);
}

int32_t MCV2_PREFIX(assign_pattern)(const int32_t *source, int32_t size, const int32_t *colors, int8_t *selectors) {
  MCV2_HAND_OFF(assign_pattern, source, size, colors, selectors)
  return mcv2::assign_pattern(source, size, colors, selectors);
}

int32_t MCV2_PREFIX(seeded)(const uint8_t *reference, int32_t width, int32_t height, const int32_t *source, int32_t x,
                            int32_t y, int32_t size, int32_t global_x, int32_t global_y, int32_t range,
                            int32_t half_pixel, const int32_t *seeds, int32_t seed_count) {
  MCV2_HAND_OFF(seeded, reference, width, height, source, x, y, size, global_x, global_y, range, half_pixel, seeds,
                seed_count)
  return mcv2::seeded(reference, width, height, source, x, y, size, global_x, global_y, range, half_pixel, seeds,
                      seed_count);
}

void MCV2_PREFIX(load_source)(const uint8_t *image, int32_t width, int32_t height, int32_t x, int32_t y, int32_t size,
                              int32_t *source) {
  MCV2_HAND_OFF(load_source, image, width, height, x, y, size, source)
  mcv2::load_source(image, width, height, x, y, size, source);
}

void MCV2_PREFIX(halve)(const int32_t *block, int32_t size, int32_t *out) {
  // two vectors of a row at a time
  MCV2_HAND_OFF_BELOW(2 * MCV2_NARROW_BELOW, halve, block, size, out)
  mcv2::halve(block, size, out);
}

void MCV2_PREFIX(ycocg)(const int32_t *source, int32_t count, int32_t chroma, float *out) {
  mcv2::ycocg(source, count, chroma, out);
}

void MCV2_PREFIX(residual_target)(const float *ycocg, const int32_t *prediction, int32_t count, int32_t chroma,
                                  float *target) {
  mcv2::residual_target(ycocg, prediction, count, chroma, target);
}

void MCV2_PREFIX(cell_means)(const float *target, int32_t size, int32_t channel, int32_t grid, float *out,
                             int32_t out_offset, int32_t out_stride) {
  MCV2_HAND_OFF(cell_means, target, size, channel, grid, out, out_offset, out_stride)
#if defined(MCV2_CELL_MEANS_TO)
  if (size == MCV2_CELL_MEANS_AT) {
    return MCV2_CELL_MEANS_TO(cell_means)(target, size, channel, grid, out, out_offset, out_stride);
  }
#endif
  mcv2::cell_means(target, size, channel, grid, out, out_offset, out_stride);
}
int32_t MCV2_PREFIX(verify)(const uint8_t *reference, const uint8_t *picture, int32_t width, int32_t height,
                            const int32_t *leaves, int32_t first, int32_t count, int32_t size, const int8_t *records) {
  MCV2_HAND_OFF(verify, reference, picture, width, height, leaves, first, count, size, records)
  return mcv2::verify(reference, picture, width, height, leaves, first, count, size, records);
}
}
