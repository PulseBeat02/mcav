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

// The kernels of one dispatch level, written once over the level's VI and VD from simd.hpp. Each is the Java method
// named in its comment, operation for operation: integer arithmetic wraps as Java's does, and floating-point values
// are computed in the same precision and order, since -ffp-contract=off keeps the compiler from fusing a product into
// a sum. Everything here has internal linkage, so the levels' copies never meet at link time.
#ifndef MCV2_KERNELS_HPP
#define MCV2_KERNELS_HPP

#include <stdint.h>

#include "simd.hpp"

namespace mcv2 {
namespace {

constexpr int32_t CHANNELS = 3;
constexpr int32_t MAX_CHANNEL = 255;
constexpr int32_t MAX_GRID = 8;
constexpr int32_t ROOT_SIZE = 32;
constexpr int32_t BLOCK_SIZES = 3;
constexpr int32_t GRID_WIDTHS = 4;
constexpr int32_t SELECTORS_AT = 6;
constexpr int32_t CHROMA_NODES = MAX_GRID * MAX_GRID;
constexpr int32_t CHROMA_PLANES = 2;
constexpr double DISTORTION_SCALE = 96.0;
constexpr int32_t PREDICTION_SCALE = 4;
constexpr int32_t PREDICTION_SHIFT = 2;
constexpr int32_t NIBBLE_BITS = 4;
constexpr int32_t NIBBLE_MASK = 15;

// the compact classes the encoder tries (CompactRecord)
constexpr int32_t DC_Y = 0;
constexpr int32_t GRID2_YC = 1;
constexpr int32_t GRID4_N4_YC = 2;
constexpr int32_t GRID4_N4_Y = 3;
constexpr int32_t GRID4_YC = 4;
constexpr int32_t LOW2 = 8;

inline int32_t log2i(int32_t v) { return __builtin_ctz((unsigned)v); }
inline int32_t size_index(int32_t size) { return log2i(size) - 3; }
inline int32_t min32(int32_t a, int32_t b) { return a < b ? a : b; }
inline int32_t max32(int32_t a, int32_t b) { return a > b ? a : b; }
inline int32_t clamp32(int32_t v, int32_t low, int32_t high) { return min32(max32(v, low), high); }

// Reconstruction's interpolation tables: for pixel p of a block, the lower and upper node and the distance to the
// lower one in units of 1 / (2 size)
struct Axis {
  int32_t lower[ROOT_SIZE];
  int32_t upper[ROOT_SIZE];
  int32_t weight[ROOT_SIZE];
};

struct Axes {
  Axis axes[BLOCK_SIZES][GRID_WIDTHS];
};

constexpr Axes make_axes() {
  Axes t{};
  for (int32_t s = 0; s < BLOCK_SIZES; s++) {
    const int32_t size = 8 << s;
    const int32_t span = 2 * size;
    for (int32_t g = 0; g < GRID_WIDTHS; g++) {
      const int32_t grid = 1 << g;
      for (int32_t p = 0; p < size; p++) {
        int32_t position = (2 * p + 1) * grid - size;
        position = position < 0 ? 0 : position;
        position = position > (grid - 1) * span ? (grid - 1) * span : position;
        const int32_t lower = position / span;
        t.axes[s][g].lower[p] = lower;
        t.axes[s][g].upper[p] = lower + 1 < grid - 1 ? lower + 1 : grid - 1;
        t.axes[s][g].weight[p] = position - lower * span;
      }
    }
  }
  return t;
}

constexpr Axes AXES = make_axes();

inline int32_t shift_of(int32_t size) { return 2 * (log2i(size) + 1); }

// Reconstruction.round: floor(clamp(value / 2^shift, 0, 255) + 0.5) of an exact fixed-point value
inline VI round(VI value, int32_t shift) {
  return VI::min(VI::max((value + VI::set1(1 << (shift - 1))).sar(shift), VI::zero()), VI::set1(MAX_CHANNEL));
}

// Reconstruction.Score: the measure of a reconstruction, row by row
struct Measure {
  const int32_t *source;
  double rate;
  double limit;
  int64_t distortion;
};

// Score.row: adds a row's error; false once the candidate can no longer be cheaper than the limit. The row's sum
// fits an int as in Java, and integer sums do not depend on their order.
inline bool row(Measure &m, const int32_t *out, int32_t from, int32_t pixels) {
  VI sum = VI::zero();
  for (int32_t p = 0; p < pixels; p += VI::N) {
    VI sr, sg, sb, rr, rg, rb;
    VI::load3(m.source + from + p * CHANNELS, sr, sg, sb);
    VI::load3(out + from + p * CHANNELS, rr, rg, rb);
    const VI dr = sr - rr;
    const VI dg = sg - rg;
    const VI db = sb - rb;
    const VI luma = dr + dg.shl(1) + db;
    const VI co = dr - db;
    const VI cg = dg.shl(1) - dr - db;
    sum = sum + (luma * luma + co * co).shl(2) + cg * cg;
  }
  m.distortion += sum.sum();
  return (double)m.distortion / DISTORTION_SCALE + m.rate < m.limit;
}

// Reconstruction.horizontal: every row of nodes interpolated across the block's width, times 2 size
inline void horizontal(const int32_t *nodes, int32_t offset, int32_t stride, int32_t grid, int32_t size,
                       int32_t *rows) {
  const Axis &a = AXES.axes[size_index(size)][log2i(grid)];
  const int32_t span = 2 * size;
  for (int32_t j = 0; j < grid; j++) {
    const int32_t row_at = offset + j * grid * stride;
    const int32_t at = j * size;
    for (int32_t x = 0; x < size; x++) {
      const int32_t w = a.weight[x];
      rows[at + x] = wrap_add(wrap_mul(nodes[row_at + a.lower[x] * stride], span - w),
                              wrap_mul(nodes[row_at + a.upper[x] * stride], w));
    }
  }
}

// Reconstruction.vertical: one row of the interpolated plane, times (2 size)^2
inline void vertical(const int32_t *rows, int32_t grid, int32_t size, int32_t y, int32_t *line) {
  const Axis &a = AXES.axes[size_index(size)][log2i(grid)];
  const int32_t top = a.lower[y] * size;
  const int32_t bottom = a.upper[y] * size;
  const VI w = VI::set1(a.weight[y]);
  const VI v = VI::set1(2 * size - a.weight[y]);
  for (int32_t x = 0; x < size; x += VI::N) {
    (VI::load(rows + top + x) * v + VI::load(rows + bottom + x) * w).store(line + x);
  }
}

// Reconstruction.predicted
int64_t predicted(const int32_t *prediction, int32_t size, int32_t *out, Measure m) {
  const int32_t n = size * CHANNELS;
  const VI two = VI::set1(2);
  for (int32_t y = 0; y < size; y++) {
    const int32_t from = y * n;
    for (int32_t i = from; i < from + n; i += VI::N) {
      (VI::load(prediction + i) + two).sar(2).store(out + i);
    }
    if (!row(m, out, from, size)) {
      return -1;
    }
  }
  return m.distortion;
}

// Reconstruction.solid
int64_t solid(int32_t color, int32_t size, int32_t *out, Measure m) {
  const VI r = VI::set1((color >> 16) & 0xFF);
  const VI g = VI::set1((color >> 8) & 0xFF);
  const VI b = VI::set1(color & 0xFF);
  for (int32_t y = 0; y < size; y++) {
    const int32_t from = y * size * CHANNELS;
    for (int32_t x = 0; x < size; x += VI::N) {
      VI::store3(out + from + x * CHANNELS, r, g, b);
    }
    if (!row(m, out, from, size)) {
      return -1;
    }
  }
  return m.distortion;
}

// Reconstruction.palette: two RGB endpoints, then one selector bit per pixel
int64_t palette(const int8_t *record, int32_t offset, int32_t size, int32_t *out, Measure m) {
  const int8_t *c = record + offset;
  const VI r0 = VI::set1((uint8_t)c[0]);
  const VI g0 = VI::set1((uint8_t)c[1]);
  const VI b0 = VI::set1((uint8_t)c[2]);
  const VI dr = VI::set1((uint8_t)c[3] - (uint8_t)c[0]);
  const VI dg = VI::set1((uint8_t)c[4] - (uint8_t)c[1]);
  const VI db = VI::set1((uint8_t)c[5] - (uint8_t)c[2]);
  for (int32_t y = 0; y < size; y++) {
    for (int32_t x = 0; x < size; x += VI::N) {
      const int32_t i = y * size + x;
      const VI bit = VI::bits(c + SELECTORS_AT, i);
      VI::store3(out + i * CHANNELS, r0 + (dr & bit), g0 + (dg & bit), b0 + (db & bit));
    }
    if (!row(m, out, y * size * CHANNELS, size)) {
      return -1;
    }
  }
  return m.distortion;
}

// Reconstruction.intraGrid: interleaved unsigned RGB nodes
int64_t intra_grid(const int8_t *record, int32_t offset, int32_t grid, int32_t size, int32_t *out, Measure m) {
  int32_t nodes[CHANNELS * MAX_GRID * MAX_GRID];
  for (int32_t i = 0; i < CHANNELS * grid * grid; i++) {
    nodes[i] = (uint8_t)record[offset + i];
  }
  int32_t rows0[MAX_GRID * ROOT_SIZE];
  int32_t rows1[MAX_GRID * ROOT_SIZE];
  int32_t rows2[MAX_GRID * ROOT_SIZE];
  horizontal(nodes, 0, CHANNELS, grid, size, rows0);
  horizontal(nodes, 1, CHANNELS, grid, size, rows1);
  horizontal(nodes, 2, CHANNELS, grid, size, rows2);
  int32_t r[ROOT_SIZE];
  int32_t g[ROOT_SIZE];
  int32_t b[ROOT_SIZE];
  const int32_t shift = shift_of(size);
  for (int32_t y = 0; y < size; y++) {
    vertical(rows0, grid, size, y, r);
    vertical(rows1, grid, size, y, g);
    vertical(rows2, grid, size, y, b);
    const int32_t from = y * size * CHANNELS;
    for (int32_t x = 0; x < size; x += VI::N) {
      VI::store3(out + from + x * CHANNELS, round(VI::load(r + x), shift), round(VI::load(g + x), shift),
                 round(VI::load(b + x), shift));
    }
    if (!row(m, out, from, size)) {
      return -1;
    }
  }
  return m.distortion;
}

// Reconstruction.residualGrid: signed YCoCg nodes scaled by 2^q, added to the prediction
int64_t residual_grid(const int32_t *prediction, const int8_t *record, int32_t offset, int32_t grid, int32_t q,
                      int32_t size, int32_t *out, Measure m) {
  int32_t nodes[CHANNELS * MAX_GRID * MAX_GRID];
  for (int32_t i = 0; i < CHANNELS * grid * grid; i++) {
    nodes[i] = shl(record[offset + i], q);
  }
  int32_t rows0[MAX_GRID * ROOT_SIZE];
  int32_t rows1[MAX_GRID * ROOT_SIZE];
  int32_t rows2[MAX_GRID * ROOT_SIZE];
  horizontal(nodes, 0, CHANNELS, grid, size, rows0);
  horizontal(nodes, 1, CHANNELS, grid, size, rows1);
  horizontal(nodes, 2, CHANNELS, grid, size, rows2);
  int32_t c0[ROOT_SIZE];
  int32_t c1[ROOT_SIZE];
  int32_t c2[ROOT_SIZE];
  const int32_t shift = shift_of(size);
  const VI quarter = VI::set1(size * size);
  for (int32_t y = 0; y < size; y++) {
    vertical(rows0, grid, size, y, c0);
    vertical(rows1, grid, size, y, c1);
    vertical(rows2, grid, size, y, c2);
    const int32_t from = y * size * CHANNELS;
    for (int32_t x = 0; x < size; x += VI::N) {
      VI pr, pg, pb;
      VI::load3(prediction + from + x * CHANNELS, pr, pg, pb);
      const VI v0 = VI::load(c0 + x);
      const VI v1 = VI::load(c1 + x);
      const VI v2 = VI::load(c2 + x);
      VI::store3(out + from + x * CHANNELS, round(pr * quarter + ((v0 + v1) - v2), shift),
                 round(pg * quarter + (v0 + v2), shift), round(pb * quarter + ((v0 - v1) - v2), shift));
    }
    if (!row(m, out, from, size)) {
      return -1;
    }
  }
  return m.distortion;
}

// Reconstruction.reduced: a luma grid and a coarser interleaved chroma grid, unsigned luma in an intra record
int64_t reduced(const int32_t *prediction, int32_t intra, const int8_t *record, int32_t offset, int32_t luma,
                int32_t chroma, int32_t q, int32_t size, int32_t *out, Measure m) {
  int32_t nodes[CHANNELS * MAX_GRID * MAX_GRID];
  for (int32_t i = 0; i < luma * luma; i++) {
    nodes[i] = intra ? (int32_t)(uint8_t)record[offset + i] : (int32_t)record[offset + i];
  }
  const int32_t chroma_at = offset + luma * luma;
  for (int32_t i = 0; i < CHROMA_PLANES * chroma * chroma; i++) {
    nodes[CHROMA_NODES + i] = record[chroma_at + i];
  }
  int32_t rows0[MAX_GRID * ROOT_SIZE];
  int32_t rows1[MAX_GRID * ROOT_SIZE];
  int32_t rows2[MAX_GRID * ROOT_SIZE];
  horizontal(nodes, 0, 1, luma, size, rows0);
  horizontal(nodes, CHROMA_NODES, CHROMA_PLANES, chroma, size, rows1);
  horizontal(nodes, CHROMA_NODES + 1, CHROMA_PLANES, chroma, size, rows2);
  int32_t yv[ROOT_SIZE];
  int32_t co[ROOT_SIZE];
  int32_t cg[ROOT_SIZE];
  const int32_t shift = shift_of(size);
  const VI quarter = VI::set1(size * size);
  for (int32_t y = 0; y < size; y++) {
    vertical(rows0, luma, size, y, yv);
    vertical(rows1, chroma, size, y, co);
    vertical(rows2, chroma, size, y, cg);
    const int32_t from = y * size * CHANNELS;
    for (int32_t x = 0; x < size; x += VI::N) {
      const VI vy = VI::load(yv + x);
      const VI vco = VI::load(co + x);
      const VI vcg = VI::load(cg + x);
      const VI r = (vy + vco) - vcg;
      const VI g = vy + vcg;
      const VI b = (vy - vco) - vcg;
      if (intra) {
        VI::store3(out + from + x * CHANNELS, round(r, shift), round(g, shift), round(b, shift));
      } else {
        VI pr, pg, pb;
        VI::load3(prediction + from + x * CHANNELS, pr, pg, pb);
        VI::store3(out + from + x * CHANNELS, round(pr * quarter + r.shl(q), shift),
                   round(pg * quarter + g.shl(q), shift), round(pb * quarter + b.shl(q), shift));
      }
    }
    if (!row(m, out, from, size)) {
      return -1;
    }
  }
  return m.distortion;
}

// Reconstruction.chromaOffset of the grid classes: Co (which 0) or Cg (1) after the luma
inline int32_t chroma_offset(const int8_t *record, int32_t body, int32_t kind, int32_t which) {
  switch (kind) {
  case GRID2_YC:
    return record[body + 2 * 2 + which];
  case GRID4_N4_YC:
    return record[body + 4 * 4 / 2 + which];
  case GRID4_YC:
    return record[body + 4 * 4 + which];
  default:
    return 0;
  }
}

// Reconstruction.nodes of the grid classes: the luma nodes, and the grid width
inline int32_t grid_nodes(const int8_t *record, int32_t body, int32_t kind, int32_t *nodes) {
  if (kind == GRID2_YC) {
    for (int32_t i = 0; i < 4; i++) {
      nodes[i] = record[body + i];
    }
    return 2;
  }
  if (kind == GRID4_N4_YC || kind == GRID4_N4_Y) {
    for (int32_t i = 0; i < 16; i++) {
      const int32_t packed = (uint8_t)record[body + i / 2];
      const int32_t nibble = (packed >> ((i % 2) * NIBBLE_BITS)) & NIBBLE_MASK;
      // Mcv2Format.signed of a 4-bit value
      nodes[i] = nibble >= 8 ? nibble - 16 : nibble;
    }
    return 4;
  }
  for (int32_t i = 0; i < 16; i++) {
    nodes[i] = record[body + i];
  }
  return 4;
}

// Reconstruction.compact of the classes the encoder tries: the DC, the grids and the luma plane
int64_t compact(const int32_t *prediction, const int8_t *record, int32_t body, int32_t kind, int32_t q, int32_t size,
                int32_t *out, Measure m) {
  if (kind == DC_Y) {
    const VI dc = VI::set1(wrap_mul(shl(record[body], q), PREDICTION_SCALE));
    const int32_t n = size * CHANNELS;
    for (int32_t y = 0; y < size; y++) {
      const int32_t from = y * n;
      for (int32_t i = from; i < from + n; i += VI::N) {
        round(VI::load(prediction + i) + dc, PREDICTION_SHIFT).store(out + i);
      }
      if (!row(m, out, from, size)) {
        return -1;
      }
    }
    return m.distortion;
  }
  const bool low2 = kind == LOW2;
  int32_t co;
  int32_t cg;
  int32_t grid = 0;
  int32_t nodes[16];
  int32_t rows0[MAX_GRID * ROOT_SIZE];
  if (low2) {
    co = record[body + 3];
    cg = record[body + 4];
  } else {
    co = chroma_offset(record, body, kind, 0);
    cg = chroma_offset(record, body, kind, 1);
    grid = grid_nodes(record, body, kind, nodes);
    horizontal(nodes, 0, 1, grid, size, rows0);
  }
  const int32_t shift = shift_of(size);
  const VI quarter = VI::set1(size * size);
  const int32_t scale = PREDICTION_SCALE * size * size;
  // with no chroma these are zero, and the sums are those of Java's luma-only path
  const VI red = VI::set1(shl(wrap_mul(co - cg, scale), q));
  const VI green = VI::set1(shl(wrap_mul(cg, scale), q));
  const VI blue = VI::set1(shl(wrap_mul(-(co + cg), scale), q));
  int32_t luma[ROOT_SIZE];
  for (int32_t y = 0; y < size; y++) {
    if (low2) {
      // (dc + gx * (2 x + 1 - size) + gy * (2 y + 1 - size)) * 4 * size
      const VI dc = VI::set1(wrap_add(wrap_mul(record[body], size), wrap_mul(record[body + 2], 2 * y + 1 - size)));
      const VI gx = VI::set1(record[body + 1]);
      const VI factor = VI::set1(PREDICTION_SCALE * size);
      for (int32_t x = 0; x < size; x += VI::N) {
        const VI column = (VI::iota() + VI::set1(x)).shl(1) + VI::set1(1 - size);
        ((dc + gx * column) * factor).store(luma + x);
      }
    } else {
      vertical(rows0, grid, size, y, luma);
    }
    const int32_t from = y * size * CHANNELS;
    for (int32_t x = 0; x < size; x += VI::N) {
      const VI ys = VI::load(luma + x).shl(q);
      VI pr, pg, pb;
      VI::load3(prediction + from + x * CHANNELS, pr, pg, pb);
      VI::store3(out + from + x * CHANNELS, round(pr * quarter + ys + red, shift),
                 round(pg * quarter + ys + green, shift), round(pb * quarter + ys + blue, shift));
    }
    if (!row(m, out, from, size)) {
      return -1;
    }
  }
  return m.distortion;
}

// Reconstruction.predictInside: a block whose samples, and the neighbours half pixels average, lie in the picture
inline void predict_inside(const uint8_t *reference, int32_t width, int32_t left, int32_t top, int32_t size,
                           int32_t half_x, int32_t half_y, int32_t *out) {
  const int32_t n = size * CHANNELS;
  const int32_t right = half_x * CHANNELS;
  const int32_t below = half_y * width * CHANNELS;
  for (int32_t py = 0; py < size; py++) {
    const uint8_t *a = reference + ((int64_t)(top + py) * width + left) * CHANNELS;
    int32_t *o = out + py * n;
    if (half_x == 0 && half_y == 0) {
      for (int32_t i = 0; i < n; i += VI::N) {
        VI::loadu8(a + i).shl(2).store(o + i);
      }
    } else if (half_y == 0 || half_x == 0) {
      const uint8_t *b = a + right + below;
      for (int32_t i = 0; i < n; i += VI::N) {
        (VI::loadu8(a + i) + VI::loadu8(b + i)).shl(1).store(o + i);
      }
    } else {
      const uint8_t *b = a + right;
      const uint8_t *d = a + below;
      const uint8_t *e = d + right;
      for (int32_t i = 0; i < n; i += VI::N) {
        (VI::loadu8(a + i) + VI::loadu8(b + i) + VI::loadu8(d + i) + VI::loadu8(e + i)).store(o + i);
      }
    }
  }
}

// Reconstruction.predict: four times each channel of a block displaced by half pixels, coordinates clamped
void predict(const uint8_t *reference, int32_t width, int32_t height, int32_t x, int32_t y, int32_t size, int32_t mx,
             int32_t my, int32_t *out) {
  const int32_t left = x + (mx >> 1);
  const int32_t top = y + (my >> 1);
  if (left >= 0 && top >= 0 && left + size + (mx & 1) <= width && top + size + (my & 1) <= height) {
    predict_inside(reference, width, left, top, size, mx & 1, my & 1, out);
    return;
  }
  for (int32_t py = 0; py < size; py++) {
    const int32_t hy = clamp32(2 * (y + py) + my, 0, 2 * (height - 1));
    const int32_t y0 = hy >> 1;
    const int32_t y1 = min32(y0 + 1, height - 1);
    const bool half_y = (hy & 1) != 0;
    for (int32_t px = 0; px < size; px++) {
      const int32_t hx = clamp32(2 * (x + px) + mx, 0, 2 * (width - 1));
      const int32_t x0 = hx >> 1;
      const int32_t x1 = min32(x0 + 1, width - 1);
      const bool half_x = (hx & 1) != 0;
      const uint8_t *a = reference + ((int64_t)y0 * width + x0) * CHANNELS;
      int32_t *o = out + (py * size + px) * CHANNELS;
      if (!half_x && !half_y) {
        for (int32_t c = 0; c < CHANNELS; c++) {
          o[c] = 4 * a[c];
        }
      } else if (!half_y) {
        const uint8_t *b = reference + ((int64_t)y0 * width + x1) * CHANNELS;
        for (int32_t c = 0; c < CHANNELS; c++) {
          o[c] = 2 * (a[c] + b[c]);
        }
      } else if (!half_x) {
        const uint8_t *b = reference + ((int64_t)y1 * width + x0) * CHANNELS;
        for (int32_t c = 0; c < CHANNELS; c++) {
          o[c] = 2 * (a[c] + b[c]);
        }
      } else {
        const uint8_t *b = reference + ((int64_t)y0 * width + x1) * CHANNELS;
        const uint8_t *d = reference + ((int64_t)y1 * width + x0) * CHANNELS;
        const uint8_t *e = reference + ((int64_t)y1 * width + x1) * CHANNELS;
        for (int32_t c = 0; c < CHANNELS; c++) {
          o[c] = a[c] + b[c] + d[c] + e[c];
        }
      }
    }
  }
}

// Fits.fit: out[i][j] = sum over y, x of M[i][y] v[y][x] M[j][x], every sum in Java's order. The first pass, the
// heavy one, keeps one row's sum per lane: it reads the channel transposed, so a lane's next value is contiguous.
void fit(const float *values, int32_t offset, int32_t stride, int32_t size, int32_t grid, const float *matrix,
         float *out, int32_t out_offset, int32_t out_stride) {
  float plane[ROOT_SIZE * ROOT_SIZE];
  for (int32_t y = 0; y < size; y++) {
    for (int32_t x = 0; x < size; x++) {
      plane[x * size + y] = values[offset + (y * size + x) * stride];
    }
  }
  double scratch[ROOT_SIZE * MAX_GRID];
  double lanes[4 * VD::N];
  for (int32_t j = 0; j < grid; j++) {
    const float *weights = matrix + j * size;
    int32_t y0 = 0;
    // four groups of rows at once: their sums are independent, so one goes on while another's addition finishes
    for (; y0 + 4 * VD::N <= size; y0 += 4 * VD::N) {
      VD s0 = VD::set1(0.0);
      VD s1 = s0;
      VD s2 = s0;
      VD s3 = s0;
      for (int32_t x = 0; x < size; x++) {
        const VD w = VD::set1((double)weights[x]);
        const float *column = plane + x * size + y0;
        s0 = s0 + VD::loadf(column) * w;
        s1 = s1 + VD::loadf(column + VD::N) * w;
        s2 = s2 + VD::loadf(column + 2 * VD::N) * w;
        s3 = s3 + VD::loadf(column + 3 * VD::N) * w;
      }
      s0.store(lanes);
      s1.store(lanes + VD::N);
      s2.store(lanes + 2 * VD::N);
      s3.store(lanes + 3 * VD::N);
      for (int32_t k = 0; k < 4 * VD::N; k++) {
        scratch[(y0 + k) * grid + j] = lanes[k];
      }
    }
    for (; y0 < size; y0 += VD::N) {
      VD sum = VD::set1(0.0);
      for (int32_t x = 0; x < size; x++) {
        sum = sum + VD::loadf(plane + x * size + y0) * VD::set1((double)weights[x]);
      }
      sum.store(lanes);
      for (int32_t k = 0; k < VD::N; k++) {
        scratch[(y0 + k) * grid + j] = lanes[k];
      }
    }
  }
  for (int32_t i = 0; i < grid; i++) {
    for (int32_t j = 0; j < grid; j++) {
      double sum = 0;
      for (int32_t y = 0; y < size; y++) {
        sum += (double)matrix[i * size + y] * scratch[y * grid + j];
      }
      out[out_offset + (i * grid + j) * out_stride] = (float)sum;
    }
  }
}

// FastFits.cellSums: the channel sums of a 4x4 grid of cells
void cell_sums(const int32_t *source, int32_t size, int32_t *sums) {
  const int32_t cell = size / 4;
  if (cell % VI::N != 0) {
    // a cell narrower than a vector: each cell's pixels summed in turn, which int sums do not depend on the order of
    for (int32_t cy = 0; cy < 4; cy++) {
      for (int32_t cx = 0; cx < 4; cx++) {
        int32_t r = 0;
        int32_t g = 0;
        int32_t b = 0;
        for (int32_t y = cy * cell; y < (cy + 1) * cell; y++) {
          const int32_t *row = source + (y * size + cx * cell) * CHANNELS;
          for (int32_t x = 0; x < cell * CHANNELS; x += CHANNELS) {
            r = wrap_add(r, row[x]);
            g = wrap_add(g, row[x + 1]);
            b = wrap_add(b, row[x + 2]);
          }
        }
        const int32_t to = (cy * 4 + cx) * CHANNELS;
        sums[to] = r;
        sums[to + 1] = g;
        sums[to + 2] = b;
      }
    }
    return;
  }
  // a cell's sums in lanes, which int sums do not depend on the order of
  for (int32_t cy = 0; cy < 4; cy++) {
    for (int32_t cx = 0; cx < 4; cx++) {
      VI r = VI::zero();
      VI g = VI::zero();
      VI b = VI::zero();
      for (int32_t y = cy * cell; y < (cy + 1) * cell; y++) {
        for (int32_t x = cx * cell; x < (cx + 1) * cell; x += VI::N) {
          VI pr, pg, pb;
          VI::load3(source + (y * size + x) * CHANNELS, pr, pg, pb);
          r = r + pr;
          g = g + pg;
          b = b + pb;
        }
      }
      const int32_t to = (cy * 4 + cx) * CHANNELS;
      sums[to] = r.sum();
      sums[to + 1] = g.sum();
      sums[to + 2] = b.sum();
    }
  }
}

// FastFits.lumaResidual: the 4x4 luma nodes of a compact record as cell means of the luma difference
void luma_residual(const int32_t *source, const int32_t *prediction, int32_t size, float *nodes) {
  int32_t sums[16] = {0};
  const int32_t cell = size / 4;
  for (int32_t y = 0; y < size; y++) {
    const int32_t row_at = (y / cell) * 4;
    for (int32_t x = 0; x < size; x++) {
      const int32_t at = (y * size + x) * CHANNELS;
      const int32_t luma = wrap_mul(4, source[at] + 2 * source[at + 1] + source[at + 2]);
      sums[row_at + x / cell] = wrap_add(sums[row_at + x / cell],
                                         wrap_sub(luma, prediction[at] + 2 * prediction[at + 1] + prediction[at + 2]));
    }
  }
  const float scale = 16.0f * cell * cell;
  for (int32_t i = 0; i < 16; i++) {
    nodes[i] = (float)sums[i] / scale;
  }
}

// FastFits.cluster: two colours by two integer Lloyd iterations on every other pixel of blocks of 16 and more
void cluster(const int32_t *source, int32_t size, float *endpoints) {
  const int32_t step = size >= 16 ? 2 : 1;
  int32_t low = 0;
  int32_t high = 0;
  int32_t low_luma = INT32_MAX;
  int32_t high_luma = INT32_MIN;
  for (int32_t y = 0; y < size; y += step) {
    for (int32_t x = 0; x < size; x += step) {
      const int32_t at = (y * size + x) * CHANNELS;
      const int32_t luma = source[at] + 2 * source[at + 1] + source[at + 2];
      if (luma < low_luma) {
        low_luma = luma;
        low = at;
      }
      if (luma > high_luma) {
        high_luma = luma;
        high = at;
      }
    }
  }
  for (int32_t c = 0; c < CHANNELS; c++) {
    endpoints[c] = (float)source[low + c];
    endpoints[CHANNELS + c] = (float)source[high + c];
  }
  const int64_t pixels = (int64_t)(size / step) * (size / step);
  for (int32_t iteration = 0; iteration < 2; iteration++) {
    const VI r0 = VI::set1((int32_t)endpoints[0]);
    const VI g0 = VI::set1((int32_t)endpoints[1]);
    const VI b0 = VI::set1((int32_t)endpoints[2]);
    const VI r1 = VI::set1((int32_t)endpoints[3]);
    const VI g1 = VI::set1((int32_t)endpoints[4]);
    const VI b1 = VI::set1((int32_t)endpoints[5]);
    // the pixels nearer endpoint 1 in lanes, and all of them: endpoint 0 has the difference
    VI near_r = VI::zero();
    VI near_g = VI::zero();
    VI near_b = VI::zero();
    VI near_n = VI::zero();
    VI all_r = VI::zero();
    VI all_g = VI::zero();
    VI all_b = VI::zero();
    for (int32_t y = 0; y < size; y += step) {
      for (int32_t x = 0; x < size; x += step * VI::N) {
        VI r, g, b;
        VI::load3(source + (y * size + x) * CHANNELS, r, g, b);
        if (step == 2) {
          VI r2, g2, b2;
          VI::load3(source + (y * size + x + VI::N) * CHANNELS, r2, g2, b2);
          r = VI::evens(r, r2);
          g = VI::evens(g, g2);
          b = VI::evens(b, b2);
        }
        const VI e0 = (r - r0) * (r - r0) + (g - g0) * (g - g0) + (b - b0) * (b - b0);
        const VI e1 = (r - r1) * (r - r1) + (g - g1) * (g - g1) + (b - b1) * (b - b1);
        const VI nearer = VI::less(e1, e0);
        near_r = near_r + (r & nearer);
        near_g = near_g + (g & nearer);
        near_b = near_b + (b & nearer);
        near_n = near_n - nearer;
        all_r = all_r + r;
        all_g = all_g + g;
        all_b = all_b + b;
      }
    }
    const int64_t counts[2] = {pixels - near_n.sum(), near_n.sum()};
    const int64_t near[CHANNELS] = {near_r.sum(), near_g.sum(), near_b.sum()};
    const int64_t all[CHANNELS] = {all_r.sum(), all_g.sum(), all_b.sum()};
    for (int32_t k = 0; k < 2; k++) {
      const int64_t n = counts[k];
      if (n > 0) {
        for (int32_t c = 0; c < CHANNELS; c++) {
          const int64_t sum = k == 1 ? near[c] : all[c] - near[c];
          endpoints[k * CHANNELS + c] = (float)((sum + n / 2) / n);
        }
      }
    }
  }
}

// PaletteFit.nearer: whether endpoint 1 is strictly nearer, in float32 as the reference computes it
inline bool nearer(int32_t r, int32_t g, int32_t b, const float *c) {
  const float dr0 = (float)r - c[0];
  const float dg0 = (float)g - c[1];
  const float db0 = (float)b - c[2];
  const float dr1 = (float)r - c[3];
  const float dg1 = (float)g - c[4];
  const float db1 = (float)b - c[5];
  const float e0 = (dr0 * dr0 + dg0 * dg0) + db0 * db0;
  const float e1 = (dr1 * dr1 + dg1 * dg1) + db1 * db1;
  return e1 < e0;
}

// PaletteFit.cluster: luma extrema, then four float Lloyd iterations over every pixel
void palette_cluster(const int32_t *source, int32_t count, float *endpoints) {
  int32_t low = 0;
  int32_t high = 0;
  int32_t low_luma = INT32_MAX;
  int32_t high_luma = INT32_MIN;
  for (int32_t i = 0; i < count; i++) {
    const int32_t luma = source[i * CHANNELS] + 2 * source[i * CHANNELS + 1] + source[i * CHANNELS + 2];
    if (luma < low_luma) {
      low_luma = luma;
      low = i;
    }
    if (luma > high_luma) {
      high_luma = luma;
      high = i;
    }
  }
  for (int32_t c = 0; c < CHANNELS; c++) {
    endpoints[c] = (float)source[low * CHANNELS + c];
    endpoints[CHANNELS + c] = (float)source[high * CHANNELS + c];
  }
  for (int32_t iteration = 0; iteration < 4; iteration++) {
    int64_t sums[6] = {0, 0, 0, 0, 0, 0};
    int32_t weights[2] = {0, 0};
    for (int32_t i = 0; i < count; i++) {
      const int32_t r = source[i * CHANNELS];
      const int32_t g = source[i * CHANNELS + 1];
      const int32_t b = source[i * CHANNELS + 2];
      const int32_t index = nearer(r, g, b, endpoints) ? 1 : 0;
      weights[index]++;
      sums[index * CHANNELS] += r;
      sums[index * CHANNELS + 1] += g;
      sums[index * CHANNELS + 2] += b;
    }
    for (int32_t index = 0; index < 2; index++) {
      if (weights[index] > 0) {
        for (int32_t c = 0; c < CHANNELS; c++) {
          endpoints[index * CHANNELS + c] = (float)((double)sums[index * CHANNELS + c] / weights[index]);
        }
      }
    }
  }
}

// PaletteFit.finish after the rounding of the endpoints: every pixel takes the strictly nearer endpoint, else the first
void assign(const int32_t *source, int32_t count, const int32_t *colors, int8_t *selectors) {
  const VI r0 = VI::set1(colors[0]);
  const VI g0 = VI::set1(colors[1]);
  const VI b0 = VI::set1(colors[2]);
  const VI r1 = VI::set1(colors[3]);
  const VI g1 = VI::set1(colors[4]);
  const VI b1 = VI::set1(colors[5]);
  int32_t lanes[VI::N];
  int32_t i = 0;
  for (; i + VI::N <= count; i += VI::N) {
    VI r, g, b;
    VI::load3(source + i * CHANNELS, r, g, b);
    const VI dr0 = r - r0;
    const VI dg0 = g - g0;
    const VI db0 = b - b0;
    const VI dr1 = r - r1;
    const VI dg1 = g - g1;
    const VI db1 = b - b1;
    const VI e0 = dr0 * dr0 + dg0 * dg0 + db0 * db0;
    const VI e1 = dr1 * dr1 + dg1 * dg1 + db1 * db1;
    VI::less(e1, e0).store(lanes);
    for (int32_t k = 0; k < VI::N; k++) {
      selectors[i + k] = (int8_t)(lanes[k] & 1);
    }
  }
  for (; i < count; i++) {
    const int32_t *s = source + i * CHANNELS;
    const int32_t e0 = (s[0] - colors[0]) * (s[0] - colors[0]) + (s[1] - colors[1]) * (s[1] - colors[1]) +
                       (s[2] - colors[2]) * (s[2] - colors[2]);
    const int32_t e1 = (s[0] - colors[3]) * (s[0] - colors[3]) + (s[1] - colors[4]) * (s[1] - colors[4]) +
                       (s[2] - colors[5]) * (s[2] - colors[5]);
    selectors[i] = (int8_t)(e1 < e0 ? 1 : 0);
  }
}

// PaletteFit.finishPattern after the rounding: the selectors row by row, stopping at the first row after which they
// repeat along neither axis; whether they do
int32_t assign_pattern(const int32_t *source, int32_t size, const int32_t *colors, int8_t *selectors) {
  bool columns = true;
  bool rows = true;
  for (int32_t y = 0; y < size && (columns || rows); y++) {
    assign(source + y * size * CHANNELS, size, colors, selectors + y * size);
    for (int32_t x = 0; x < size; x++) {
      const int32_t i = y * size + x;
      columns &= selectors[i] == selectors[x];
      rows &= selectors[i] == selectors[y * size];
    }
  }
  return columns || rows ? 1 : 0;
}

// MotionSearch: the sampled rows and columns of a block, min(k size / 4 + size / 8, size - 1)
inline int32_t sample(int32_t size, int32_t k) { return min32((k * size) / 4 + size / 8, size - 1); }

constexpr int32_t DIRECTIONS[8][2] = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}, {-1, -1}, {1, 1}, {-1, 1}, {1, -1}};

inline int32_t pack(int32_t x, int32_t y) { return shl(x, 16) | (y & 0xFFFF); }
inline int32_t unpack_x(int32_t vector) { return vector >> 16; }
inline int32_t unpack_y(int32_t vector) { return (int16_t)vector; }

// MotionSearch.inside for one kind of sample (whole, half across, half down, half both ways): the samples and their
// half-pixel neighbours lie in the picture. source4 holds four times the source's sixteen samples, row by row.
template <int KIND>
int64_t inside(const uint8_t *reference, int32_t width, const int32_t *source4, int32_t x, int32_t y,
               const int32_t *samples, int32_t mx, int32_t my) {
  const int32_t right = (mx & 1) * CHANNELS;
  const int64_t below = (int64_t)(my & 1) * width * CHANNELS;
  int64_t sum = 0;
  for (int32_t j = 0; j < 4; j++) {
    const uint8_t *line = reference + ((int64_t)(y + samples[j] + (my >> 1)) * width + x + (mx >> 1)) * CHANNELS;
    const int32_t *t = source4 + j * 4 * CHANNELS;
    for (int32_t i = 0; i < 4; i++) {
      const uint8_t *a = line + samples[i] * CHANNELS;
      for (int32_t c = 0; c < CHANNELS; c++) {
        int32_t value4;
        if (KIND == 0) {
          value4 = 4 * a[c];
        } else if (KIND == 3) {
          value4 = a[c] + a[right + c] + a[below + c] + a[below + right + c];
        } else {
          value4 = 2 * (a[c] + a[right + below + c]);
        }
        // Math.abs of an int, which leaves Integer.MIN_VALUE negative
        const int32_t difference = wrap_sub(value4, t[i * CHANNELS + c]);
        sum += difference < 0 ? wrap_sub(0, difference) : difference;
      }
    }
  }
  return sum;
}

// MotionSearch.cost: four times the sum of absolute differences on the sixteen samples
int64_t cost(const uint8_t *reference, int32_t width, int32_t height, const int32_t *source4, int32_t x, int32_t y,
             const int32_t *samples, int32_t mx, int32_t my) {
  const int32_t left = x + samples[0] + (mx >> 1);
  const int32_t right = x + samples[3] + (mx >> 1) + (mx & 1);
  const int32_t top = y + samples[0] + (my >> 1);
  const int32_t bottom = y + samples[3] + (my >> 1) + (my & 1);
  if (left >= 0 && top >= 0 && right < width && bottom < height) {
    switch ((mx & 1) | ((my & 1) << 1)) {
    case 0:
      return inside<0>(reference, width, source4, x, y, samples, mx, my);
    case 3:
      return inside<3>(reference, width, source4, x, y, samples, mx, my);
    default:
      return inside<1>(reference, width, source4, x, y, samples, mx, my);
    }
  }
  int64_t sum = 0;
  for (int32_t j = 0; j < 4; j++) {
    const int32_t py = y + samples[j];
    const int32_t hy = clamp32(2 * py + my, 0, 2 * (height - 1));
    const int32_t y0 = hy >> 1;
    const int32_t y1 = min32(y0 + 1, height - 1);
    const bool half_y = (hy & 1) != 0;
    for (int32_t i = 0; i < 4; i++) {
      const int32_t px = x + samples[i];
      const int32_t hx = clamp32(2 * px + mx, 0, 2 * (width - 1));
      const int32_t x0 = hx >> 1;
      const int32_t x1 = min32(x0 + 1, width - 1);
      const bool half_x = (hx & 1) != 0;
      const int32_t *t = source4 + (j * 4 + i) * CHANNELS;
      for (int32_t c = 0; c < CHANNELS; c++) {
        const int32_t a = reference[((int64_t)y0 * width + x0) * CHANNELS + c];
        int32_t value4;
        if (!half_x && !half_y) {
          value4 = 4 * a;
        } else if (!half_y) {
          value4 = 2 * (a + reference[((int64_t)y0 * width + x1) * CHANNELS + c]);
        } else if (!half_x) {
          value4 = 2 * (a + reference[((int64_t)y1 * width + x0) * CHANNELS + c]);
        } else {
          value4 = a + reference[((int64_t)y0 * width + x1) * CHANNELS + c] +
                   reference[((int64_t)y1 * width + x0) * CHANNELS + c] +
                   reference[((int64_t)y1 * width + x1) * CHANNELS + c];
        }
        const int32_t difference = wrap_sub(value4, t[c]);
        sum += difference < 0 ? wrap_sub(0, difference) : difference;
      }
    }
  }
  return sum;
}

// The vectors a search has measured: any of them costs at least the best found since, so measuring one again can
// never replace the best, and a search that skips them decides exactly as MotionSearch does
struct Measured {
  static constexpr int32_t CAPACITY = 256;
  int64_t vectors[CAPACITY];
  int32_t count = 0;
  // both coordinates whole: a packed vector keeps only 16 bits of y
  static int64_t key(int32_t x, int32_t y) { return (int64_t)((uint64_t)(uint32_t)x << 32 | (uint32_t)y); }
  bool contains(int32_t x, int32_t y) const {
    const int64_t vector = key(x, y);
    for (int32_t k = 0; k < count; k++) {
      if (vectors[k] == vector) {
        return true;
      }
    }
    return false;
  }
  void add(int32_t x, int32_t y) {
    if (count < CAPACITY) {
      vectors[count++] = key(x, y);
    }
  }
};

// MotionSearch.seeded: the best of the seeds, one-pixel steps to the best of four neighbours while one is better,
// then the best of the eight half-pixel neighbours; a vector replaces the best only when strictly better
int32_t seeded(const uint8_t *reference, int32_t width, int32_t height, const int32_t *source, int32_t x, int32_t y,
               int32_t size, int32_t global_x, int32_t global_y, int32_t range, int32_t half_pixel,
               const int32_t *seeds, int32_t seed_count) {
  const int32_t samples[4] = {sample(size, 0), sample(size, 1), sample(size, 2), sample(size, 3)};
  int32_t source4[16 * CHANNELS];
  for (int32_t j = 0; j < 4; j++) {
    for (int32_t i = 0; i < 4; i++) {
      for (int32_t c = 0; c < CHANNELS; c++) {
        source4[(j * 4 + i) * CHANNELS + c] = wrap_mul(4, source[(samples[j] * size + samples[i]) * CHANNELS + c]);
      }
    }
  }
  const int32_t low_x = global_x - range * 2;
  const int32_t high_x = global_x + range * 2;
  const int32_t low_y = global_y - range * 2;
  const int32_t high_y = global_y + range * 2;
  Measured measured;
  int32_t vx = global_x;
  int32_t vy = global_y;
  int64_t best = cost(reference, width, height, source4, x, y, samples, vx, vy);
  measured.add(vx, vy);
  for (int32_t k = 0; k < seed_count; k++) {
    const int32_t sx = clamp32(unpack_x(seeds[k]), low_x, high_x);
    const int32_t sy = clamp32(unpack_y(seeds[k]), low_y, high_y);
    if (!measured.contains(sx, sy)) {
      measured.add(sx, sy);
      const int64_t error = cost(reference, width, height, source4, x, y, samples, sx, sy);
      if (error < best) {
        best = error;
        vx = sx;
        vy = sy;
      }
    }
  }
  for (int32_t steps = 0; steps < 2 * range; steps++) {
    const int32_t cx = vx;
    const int32_t cy = vy;
    for (int32_t d = 0; d < 4; d++) {
      const int32_t hx = clamp32(cx + DIRECTIONS[d][0] * 2, low_x, high_x);
      const int32_t hy = clamp32(cy + DIRECTIONS[d][1] * 2, low_y, high_y);
      if (measured.contains(hx, hy)) {
        continue;
      }
      measured.add(hx, hy);
      const int64_t error = cost(reference, width, height, source4, x, y, samples, hx, hy);
      if (error < best) {
        best = error;
        vx = hx;
        vy = hy;
      }
    }
    if (vx == cx && vy == cy) {
      break;
    }
  }
  if (half_pixel) {
    const int32_t cx = vx;
    const int32_t cy = vy;
    for (int32_t d = 0; d < 8; d++) {
      const int32_t hx = clamp32(cx + DIRECTIONS[d][0], low_x, high_x);
      const int32_t hy = clamp32(cy + DIRECTIONS[d][1], low_y, high_y);
      if (measured.contains(hx, hy)) {
        continue;
      }
      measured.add(hx, hy);
      const int64_t error = cost(reference, width, height, source4, x, y, samples, hx, hy);
      if (error < best) {
        best = error;
        vx = hx;
        vy = hy;
      }
    }
  }
  return pack(vx, vy);
}

// BlockCoder.loadSource of a superblock: the block's channels, the picture's last row and column repeated past its
// edges
void load_source(const uint8_t *image, int32_t width, int32_t height, int32_t x, int32_t y, int32_t size,
                 int32_t *source) {
  for (int32_t py = 0; py < size; py++) {
    const int32_t sy = min32(y + py, height - 1);
    const uint8_t *line = image + (int64_t)sy * width * CHANNELS;
    int32_t *to = source + py * size * CHANNELS;
    if (x + size <= width) {
      const uint8_t *from = line + (int64_t)x * CHANNELS;
      for (int32_t i = 0; i < size * CHANNELS; i += VI::N) {
        VI::loadu8(from + i).store(to + i);
      }
      continue;
    }
    for (int32_t px = 0; px < size; px++) {
      const int32_t sx = min32(x + px, width - 1);
      for (int32_t c = 0; c < CHANNELS; c++) {
        to[px * CHANNELS + c] = line[(int64_t)sx * CHANNELS + c];
      }
    }
  }
}

// BlockCoder.halve: each pixel the rounded mean of a 2x2 square
void halve(const int32_t *block, int32_t size, int32_t *out) {
  const int32_t half = size / 2;
  const int32_t row_length = size * CHANNELS;
  for (int32_t py = 0; py < half; py++) {
    for (int32_t px = 0; px < half; px++) {
      const int32_t at = (2 * py * size + 2 * px) * CHANNELS;
      for (int32_t c = 0; c < CHANNELS; c++) {
        const int32_t sum = block[at + c] + block[at + CHANNELS + c] + block[at + row_length + c] +
                            block[at + row_length + CHANNELS + c];
        out[(py * half + px) * CHANNELS + c] = (sum + 2) >> 2;
      }
    }
  }
}

// BlockCoder.ycocg: the YCoCg of the source; the chroma only when asked, leaving it untouched otherwise
void ycocg(const int32_t *source, int32_t count, int32_t chroma, float *out) {
  const VF quarter = VF::set1(0.25f);
  const VF half = VF::set1(0.5f);
  int32_t i = 0;
  for (; i + VI::N <= count; i += VI::N) {
    VI r, g, b;
    VI::load3(source + i * CHANNELS, r, g, b);
    const VF luma = VF::from(r + g.shl(1) + b) * quarter;
    if (chroma) {
      // Java's -r + 2 g - b, as ints wrap alike in either order
      VI::store3((int32_t *)(out + i * CHANNELS), luma.bits(), (VF::from(r - b) * half).bits(),
                 (VF::from(g.shl(1) - r - b) * quarter).bits());
    } else {
      int32_t lanes[VI::N];
      luma.bits().store(lanes);
      for (int32_t k = 0; k < VI::N; k++) {
        memcpy(out + (i + k) * CHANNELS, &lanes[k], sizeof(float));
      }
    }
  }
  for (; i < count; i++) {
    const int32_t r = source[i * CHANNELS];
    const int32_t g = source[i * CHANNELS + 1];
    const int32_t b = source[i * CHANNELS + 2];
    out[i * CHANNELS] = (float)(r + 2 * g + b) * 0.25f;
    if (chroma) {
      out[i * CHANNELS + 1] = (float)(r - b) * 0.5f;
      out[i * CHANNELS + 2] = (float)(-r + 2 * g - b) * 0.25f;
    }
  }
}

// BlockCoder.residualTarget: the YCoCg source less the YCoCg of a four-times prediction
void residual_target(const float *ycocg_source, const int32_t *prediction, int32_t count, int32_t chroma,
                     float *target) {
  const VF quarter = VF::set1(0.25f);
  const VF half = VF::set1(0.5f);
  const VF two = VF::set1(2.0f);
  int32_t i = 0;
  for (; chroma && i + VI::N <= count; i += VI::N) {
    VI sy, sco, scg;
    VI::load3((const int32_t *)(ycocg_source + i * CHANNELS), sy, sco, scg);
    VI pr, pg, pb;
    VI::load3(prediction + i * CHANNELS, pr, pg, pb);
    const VF r = VF::from(pr) * quarter;
    const VF g = VF::from(pg) * quarter;
    const VF b = VF::from(pb) * quarter;
    VI::store3((int32_t *)(target + i * CHANNELS), (VF::bits(sy) - ((r + two * g) + b) * quarter).bits(),
               (VF::bits(sco) - (r - b) * half).bits(), (VF::bits(scg) - ((-r + two * g) - b) * quarter).bits());
  }
  for (; i < count; i++) {
    const float r = (float)prediction[i * CHANNELS] * 0.25f;
    const float g = (float)prediction[i * CHANNELS + 1] * 0.25f;
    const float b = (float)prediction[i * CHANNELS + 2] * 0.25f;
    target[i * CHANNELS] = ycocg_source[i * CHANNELS] - (r + 2 * g + b) * 0.25f;
    if (chroma) {
      target[i * CHANNELS + 1] = ycocg_source[i * CHANNELS + 1] - (r - b) * 0.5f;
      target[i * CHANNELS + 2] = ycocg_source[i * CHANNELS + 2] - (-r + 2 * g - b) * 0.25f;
    }
  }
}

// BlockCoder.cellMeans: one channel as the means of a grid's cells, each summed in double in Java's order
void cell_means(const float *target, int32_t size, int32_t channel, int32_t grid, float *out, int32_t out_offset,
                int32_t out_stride) {
  const int32_t side = size / grid;
  for (int32_t j = 0; j < grid; j++) {
    for (int32_t i = 0; i < grid; i++) {
      double sum = 0;
      for (int32_t y = j * side; y < (j + 1) * side; y++) {
        for (int32_t x = i * side; x < (i + 1) * side; x++) {
          sum += target[(y * size + x) * CHANNELS + channel];
        }
      }
      out[out_offset + (j * grid + i) * out_stride] = (float)(sum / (side * side));
    }
  }
}

} // namespace
} // namespace mcv2

#endif
