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

// The vector types of one dispatch level. A translation unit defines exactly one of MCV2_SIMD_SCALAR, MCV2_SIMD_SSE2,
// MCV2_SIMD_SSE41, MCV2_SIMD_AVX2, MCV2_SIMD_AVX512, MCV2_SIMD_NEON, MCV2_SIMD_SVE256 or MCV2_SIMD_SVE512 before
// including this file, and each unit is compiled with its own extensions only, so no instruction can run on a CPU
// that the dispatch did not find it on.
//
// VI holds N int32 lanes with Java's arithmetic: sums and products wrap, shifts to the right are arithmetic. The
// kernels are written once over VI, so every level runs the same operations on the same values; a level only decides
// how many pixels one operation covers.
#ifndef MCV2_SIMD_HPP
#define MCV2_SIMD_HPP

#include <stdint.h>
#include <string.h>

static_assert((-7 >> 1) == -4, "right shifts of negative values must be arithmetic, as Java's >>");

#if defined(MCV2_SIMD_SSE41)
#include <smmintrin.h>
#elif defined(MCV2_SIMD_SSE2)
#include <emmintrin.h>
#elif defined(MCV2_SIMD_AVX2) || defined(MCV2_SIMD_AVX512)
#include <immintrin.h>
#elif defined(MCV2_SIMD_NEON)
#include <arm_neon.h>
#elif defined(MCV2_SIMD_SVE256) || defined(MCV2_SIMD_SVE512)
#include <arm_sve.h>
#elif !defined(MCV2_SIMD_SCALAR)
#error "define one of the MCV2_SIMD_ levels"
#endif

namespace mcv2 {

// Java's int arithmetic on one value: wrapping, and arithmetic shifts.
inline int32_t wrap_add(int32_t a, int32_t b) { return (int32_t)((uint32_t)a + (uint32_t)b); }
inline int32_t wrap_sub(int32_t a, int32_t b) { return (int32_t)((uint32_t)a - (uint32_t)b); }
inline int32_t wrap_mul(int32_t a, int32_t b) { return (int32_t)((uint32_t)a * (uint32_t)b); }
inline int32_t shl(int32_t a, int32_t k) { return (int32_t)((uint32_t)a << (k & 31)); }
inline int32_t sar(int32_t a, int32_t k) { return a >> (k & 31); }

#if defined(MCV2_SIMD_SCALAR)

struct VI {
  static constexpr int N = 1;
  int32_t v;
  static VI load(const int32_t *p) { return {p[0]}; }
  void store(int32_t *p) const { p[0] = v; }
  static VI set1(int32_t x) { return {x}; }
  static VI zero() { return {0}; }
  static VI iota() { return {0}; }
  static VI loadu8(const uint8_t *p) { return {p[0]}; }
  friend VI operator+(VI a, VI b) { return {wrap_add(a.v, b.v)}; }
  friend VI operator-(VI a, VI b) { return {wrap_sub(a.v, b.v)}; }
  friend VI operator*(VI a, VI b) { return {wrap_mul(a.v, b.v)}; }
  friend VI operator&(VI a, VI b) { return {a.v & b.v}; }
  VI shl(int32_t k) const { return {mcv2::shl(v, k)}; }
  VI sar(int32_t k) const { return {mcv2::sar(v, k)}; }
  static VI min(VI a, VI b) { return {a.v < b.v ? a.v : b.v}; }
  static VI max(VI a, VI b) { return {a.v > b.v ? a.v : b.v}; }
  static VI abs(VI a) { return {a.v < 0 ? wrap_sub(0, a.v) : a.v}; }
  // all ones where a < b, else zero
  static VI less(VI a, VI b) { return {a.v < b.v ? -1 : 0}; }
  int32_t sum() const { return v; }
  static void load3(const int32_t *p, VI &a, VI &b, VI &c) {
    a.v = p[0];
    b.v = p[1];
    c.v = p[2];
  }
  static void store3(int32_t *p, VI a, VI b, VI c) {
    p[0] = a.v;
    p[1] = b.v;
    p[2] = c.v;
  }
  // all ones in the lanes whose bit is set, the bits of lanes 0.. starting at bit i of the byte array
  static VI bits(const int8_t *p, int32_t i) { return {((p[i >> 3] >> (i & 7)) & 1) ? -1 : 0}; }
  // the even lanes of a, then the even lanes of b
  static VI evens(VI a, VI) { return a; }
};

// N float lanes, as many as VI's, and their bits as a VI for load3 and store3.
struct VF {
  float v;
  static VF set1(float x) { return {x}; }
  static VF from(VI a) { return {(float)a.v}; }
  static VF bits(VI a) {
    VF f;
    memcpy(&f.v, &a.v, sizeof(f.v));
    return f;
  }
  VI bits() const {
    VI a;
    memcpy(&a.v, &v, sizeof(v));
    return a;
  }
  friend VF operator+(VF a, VF b) { return {a.v + b.v}; }
  friend VF operator-(VF a, VF b) { return {a.v - b.v}; }
  friend VF operator*(VF a, VF b) { return {a.v * b.v}; }
  friend VF operator-(VF a) { return {-a.v}; }
};

// N double lanes: a fit keeps one output's sum in each lane, so no sum is ever reordered.
struct VD {
  static constexpr int N = 1;
  double v;
  static VD set1(double x) { return {x}; }
  static VD loadf(const float *p) { return {(double)p[0]}; }
  friend VD operator+(VD a, VD b) { return {a.v + b.v}; }
  friend VD operator*(VD a, VD b) { return {a.v * b.v}; }
  void store(double *p) const { p[0] = v; }
};

#elif defined(MCV2_SIMD_SSE41) || defined(MCV2_SIMD_SSE2)

// The five operations SSE4.1 (and SSSE3's abs) added over SSE2, which the SSE2 level computes from SSE2 instructions:
// the same values, lane for lane.
#if defined(MCV2_SIMD_SSE41)
template <int M> inline __m128i blend16(__m128i a, __m128i b) { return _mm_blend_epi16(a, b, M); }
inline __m128i mullo32(__m128i a, __m128i b) { return _mm_mullo_epi32(a, b); }
inline __m128i min32(__m128i a, __m128i b) { return _mm_min_epi32(a, b); }
inline __m128i max32(__m128i a, __m128i b) { return _mm_max_epi32(a, b); }
inline __m128i abs32(__m128i a) { return _mm_abs_epi32(a); }
inline __m128i widen8(__m128i bytes) { return _mm_cvtepu8_epi32(bytes); }
#else
// the 16-bit lanes whose bit of M is set taken from b, the others from a
template <int M> inline __m128i blend16(__m128i a, __m128i b) {
  const __m128i mask = _mm_setr_epi16((M & 1) ? -1 : 0, (M & 2) ? -1 : 0, (M & 4) ? -1 : 0, (M & 8) ? -1 : 0,
                                      (M & 16) ? -1 : 0, (M & 32) ? -1 : 0, (M & 64) ? -1 : 0, (M & 128) ? -1 : 0);
  return _mm_or_si128(_mm_and_si128(mask, b), _mm_andnot_si128(mask, a));
}
// the low 32 bits of each product: the same for signed and unsigned operands, as Java's wrapping multiply
inline __m128i mullo32(__m128i a, __m128i b) {
  const __m128i even = _mm_mul_epu32(a, b);
  const __m128i odd = _mm_mul_epu32(_mm_srli_epi64(a, 32), _mm_srli_epi64(b, 32));
  return _mm_unpacklo_epi32(_mm_shuffle_epi32(even, _MM_SHUFFLE(0, 0, 2, 0)),
                            _mm_shuffle_epi32(odd, _MM_SHUFFLE(0, 0, 2, 0)));
}
inline __m128i min32(__m128i a, __m128i b) {
  const __m128i less = _mm_cmplt_epi32(a, b);
  return _mm_or_si128(_mm_and_si128(less, a), _mm_andnot_si128(less, b));
}
inline __m128i max32(__m128i a, __m128i b) {
  const __m128i greater = _mm_cmpgt_epi32(a, b);
  return _mm_or_si128(_mm_and_si128(greater, a), _mm_andnot_si128(greater, b));
}
// (a ^ s) - s with s the sign: the minimum value stays itself, as Java's Math.abs and pabsd
inline __m128i abs32(__m128i a) {
  const __m128i sign = _mm_srai_epi32(a, 31);
  return _mm_sub_epi32(_mm_xor_si128(a, sign), sign);
}
inline __m128i widen8(__m128i bytes) {
  const __m128i zero = _mm_setzero_si128();
  return _mm_unpacklo_epi16(_mm_unpacklo_epi8(bytes, zero), zero);
}
#endif

struct VI {
  static constexpr int N = 4;
  __m128i v;
  static VI load(const int32_t *p) { return {_mm_loadu_si128((const __m128i *)p)}; }
  void store(int32_t *p) const { _mm_storeu_si128((__m128i *)p, v); }
  static VI set1(int32_t x) { return {_mm_set1_epi32(x)}; }
  static VI zero() { return {_mm_setzero_si128()}; }
  static VI iota() { return {_mm_setr_epi32(0, 1, 2, 3)}; }
  static VI loadu8(const uint8_t *p) {
    int32_t word;
    memcpy(&word, p, sizeof(word));
    return {widen8(_mm_cvtsi32_si128(word))};
  }
  friend VI operator+(VI a, VI b) { return {_mm_add_epi32(a.v, b.v)}; }
  friend VI operator-(VI a, VI b) { return {_mm_sub_epi32(a.v, b.v)}; }
  friend VI operator*(VI a, VI b) { return {mullo32(a.v, b.v)}; }
  friend VI operator&(VI a, VI b) { return {_mm_and_si128(a.v, b.v)}; }
  VI shl(int32_t k) const { return {_mm_sll_epi32(v, _mm_cvtsi32_si128(k & 31))}; }
  VI sar(int32_t k) const { return {_mm_sra_epi32(v, _mm_cvtsi32_si128(k & 31))}; }
  static VI min(VI a, VI b) { return {min32(a.v, b.v)}; }
  static VI max(VI a, VI b) { return {max32(a.v, b.v)}; }
  static VI abs(VI a) { return {abs32(a.v)}; }
  static VI less(VI a, VI b) { return {_mm_cmplt_epi32(a.v, b.v)}; }
  int32_t sum() const {
    __m128i s = _mm_add_epi32(v, _mm_shuffle_epi32(v, _MM_SHUFFLE(1, 0, 3, 2)));
    s = _mm_add_epi32(s, _mm_shuffle_epi32(s, _MM_SHUFFLE(2, 3, 0, 1)));
    return _mm_cvtsi128_si32(s);
  }
  // p holds r0 g0 b0 r1 | g1 b1 r2 g2 | b2 r3 g3 b3: each channel takes its lanes from the three vectors by two blends
  // (lane k is bits 2k and 2k+1 of a 16-bit blend mask), then one shuffle puts them in pixel order
  static void load3(const int32_t *p, VI &a, VI &b, VI &c) {
    const __m128i v0 = _mm_loadu_si128((const __m128i *)p);
    const __m128i v1 = _mm_loadu_si128((const __m128i *)(p + 4));
    const __m128i v2 = _mm_loadu_si128((const __m128i *)(p + 8));
    // r0 r3 r2 r1
    const __m128i r = blend16<0x0C>(blend16<0x30>(v0, v1), v2);
    // g1 g0 g3 g2
    const __m128i g = blend16<0x30>(blend16<0x0C>(v1, v0), v2);
    // b2 b1 b0 b3
    const __m128i bl = blend16<0x30>(blend16<0x0C>(v2, v1), v0);
    a.v = _mm_shuffle_epi32(r, _MM_SHUFFLE(1, 2, 3, 0));
    b.v = _mm_shuffle_epi32(g, _MM_SHUFFLE(2, 3, 0, 1));
    c.v = _mm_shuffle_epi32(bl, _MM_SHUFFLE(3, 0, 1, 2));
  }
  static void store3(int32_t *p, VI a, VI b, VI c) {
    // v0 = r0 g0 b0 r1
    const __m128i v0 = blend16<0x30>(
        blend16<0x0C>(_mm_shuffle_epi32(a.v, _MM_SHUFFLE(1, 0, 0, 0)), _mm_shuffle_epi32(b.v, _MM_SHUFFLE(0, 0, 0, 0))),
        _mm_shuffle_epi32(c.v, _MM_SHUFFLE(0, 0, 0, 0)));
    // v1 = g1 b1 r2 g2
    const __m128i v1 = blend16<0x30>(
        blend16<0x0C>(_mm_shuffle_epi32(b.v, _MM_SHUFFLE(2, 0, 0, 1)), _mm_shuffle_epi32(c.v, _MM_SHUFFLE(1, 1, 1, 1))),
        _mm_shuffle_epi32(a.v, _MM_SHUFFLE(2, 2, 2, 2)));
    // v2 = b2 r3 g3 b3
    const __m128i v2 = blend16<0x30>(
        blend16<0x0C>(_mm_shuffle_epi32(c.v, _MM_SHUFFLE(3, 0, 0, 2)), _mm_shuffle_epi32(a.v, _MM_SHUFFLE(3, 3, 3, 3))),
        _mm_shuffle_epi32(b.v, _MM_SHUFFLE(3, 3, 3, 3)));
    _mm_storeu_si128((__m128i *)p, v0);
    _mm_storeu_si128((__m128i *)(p + 4), v1);
    _mm_storeu_si128((__m128i *)(p + 8), v2);
  }
  static VI bits(const int8_t *p, int32_t i) {
    const __m128i byte = _mm_set1_epi32((p[i >> 3] >> (i & 7)) & 0xF);
    const __m128i lanes = _mm_setr_epi32(1, 2, 4, 8);
    return {_mm_cmpeq_epi32(_mm_and_si128(byte, lanes), lanes)};
  }
  static VI evens(VI a, VI b) {
    return {_mm_castps_si128(_mm_shuffle_ps(_mm_castsi128_ps(a.v), _mm_castsi128_ps(b.v), _MM_SHUFFLE(2, 0, 2, 0)))};
  }
};

struct VF {
  __m128 v;
  static VF set1(float x) { return {_mm_set1_ps(x)}; }
  static VF from(VI a) { return {_mm_cvtepi32_ps(a.v)}; }
  static VF bits(VI a) { return {_mm_castsi128_ps(a.v)}; }
  VI bits() const { return {_mm_castps_si128(v)}; }
  friend VF operator+(VF a, VF b) { return {_mm_add_ps(a.v, b.v)}; }
  friend VF operator-(VF a, VF b) { return {_mm_sub_ps(a.v, b.v)}; }
  friend VF operator*(VF a, VF b) { return {_mm_mul_ps(a.v, b.v)}; }
  friend VF operator-(VF a) { return {_mm_xor_ps(a.v, _mm_set1_ps(-0.0f))}; }
};

struct VD {
  static constexpr int N = 2;
  __m128d v;
  static VD set1(double x) { return {_mm_set1_pd(x)}; }
  static VD loadf(const float *p) {
    int64_t pair;
    memcpy(&pair, p, sizeof(pair));
    return {_mm_cvtps_pd(_mm_castsi128_ps(_mm_cvtsi64_si128(pair)))};
  }
  friend VD operator+(VD a, VD b) { return {_mm_add_pd(a.v, b.v)}; }
  friend VD operator*(VD a, VD b) { return {_mm_mul_pd(a.v, b.v)}; }
  void store(double *p) const { _mm_storeu_pd(p, v); }
};

#elif defined(MCV2_SIMD_AVX2)

struct VI {
  static constexpr int N = 8;
  __m256i v;
  static VI load(const int32_t *p) { return {_mm256_loadu_si256((const __m256i *)p)}; }
  void store(int32_t *p) const { _mm256_storeu_si256((__m256i *)p, v); }
  static VI set1(int32_t x) { return {_mm256_set1_epi32(x)}; }
  static VI zero() { return {_mm256_setzero_si256()}; }
  static VI iota() { return {_mm256_setr_epi32(0, 1, 2, 3, 4, 5, 6, 7)}; }
  static VI loadu8(const uint8_t *p) { return {_mm256_cvtepu8_epi32(_mm_loadl_epi64((const __m128i *)p))}; }
  friend VI operator+(VI a, VI b) { return {_mm256_add_epi32(a.v, b.v)}; }
  friend VI operator-(VI a, VI b) { return {_mm256_sub_epi32(a.v, b.v)}; }
  friend VI operator*(VI a, VI b) { return {_mm256_mullo_epi32(a.v, b.v)}; }
  friend VI operator&(VI a, VI b) { return {_mm256_and_si256(a.v, b.v)}; }
  VI shl(int32_t k) const { return {_mm256_sll_epi32(v, _mm_cvtsi32_si128(k & 31))}; }
  VI sar(int32_t k) const { return {_mm256_sra_epi32(v, _mm_cvtsi32_si128(k & 31))}; }
  static VI min(VI a, VI b) { return {_mm256_min_epi32(a.v, b.v)}; }
  static VI max(VI a, VI b) { return {_mm256_max_epi32(a.v, b.v)}; }
  static VI abs(VI a) { return {_mm256_abs_epi32(a.v)}; }
  static VI less(VI a, VI b) { return {_mm256_cmpgt_epi32(b.v, a.v)}; }
  int32_t sum() const {
    __m128i s = _mm_add_epi32(_mm256_castsi256_si128(v), _mm256_extracti128_si256(v, 1));
    s = _mm_add_epi32(s, _mm_shuffle_epi32(s, _MM_SHUFFLE(1, 0, 3, 2)));
    s = _mm_add_epi32(s, _mm_shuffle_epi32(s, _MM_SHUFFLE(2, 3, 0, 1)));
    return _mm_cvtsi128_si32(s);
  }
  // p holds r0 g0 b0 r1 g1 b1 r2 g2 | b2 r3 g3 b3 r4 g4 b4 r5 | g5 b5 r6 g6 b6 r7 g7 b7: a channel's eight values lie
  // in lanes {0,3,6} {1,4,7} {2,5} of the three vectors in some rotation, so two blends gather them into one vector
  // and a permutation puts them in pixel order
  static void load3(const int32_t *p, VI &a, VI &b, VI &c) {
    const __m256i v0 = _mm256_loadu_si256((const __m256i *)p);
    const __m256i v1 = _mm256_loadu_si256((const __m256i *)(p + 8));
    const __m256i v2 = _mm256_loadu_si256((const __m256i *)(p + 16));
    // r0 r3 r6 r1 r4 r7 r2 r5
    const __m256i r = _mm256_blend_epi32(_mm256_blend_epi32(v0, v1, 0x92), v2, 0x24);
    // g5 g0 g3 g6 g1 g4 g7 g2
    const __m256i g = _mm256_blend_epi32(_mm256_blend_epi32(v0, v1, 0x24), v2, 0x49);
    // b2 b5 b0 b3 b6 b1 b4 b7
    const __m256i bl = _mm256_blend_epi32(_mm256_blend_epi32(v0, v1, 0x49), v2, 0x92);
    a.v = _mm256_permutevar8x32_epi32(r, _mm256_setr_epi32(0, 3, 6, 1, 4, 7, 2, 5));
    b.v = _mm256_permutevar8x32_epi32(g, _mm256_setr_epi32(1, 4, 7, 2, 5, 0, 3, 6));
    c.v = _mm256_permutevar8x32_epi32(bl, _mm256_setr_epi32(2, 5, 0, 3, 6, 1, 4, 7));
  }
  static void store3(int32_t *p, VI a, VI b, VI c) {
    const __m256i r = _mm256_permutevar8x32_epi32(a.v, _mm256_setr_epi32(0, 3, 6, 1, 4, 7, 2, 5));
    const __m256i g = _mm256_permutevar8x32_epi32(b.v, _mm256_setr_epi32(5, 0, 3, 6, 1, 4, 7, 2));
    const __m256i bl = _mm256_permutevar8x32_epi32(c.v, _mm256_setr_epi32(2, 5, 0, 3, 6, 1, 4, 7));
    _mm256_storeu_si256((__m256i *)p, _mm256_blend_epi32(_mm256_blend_epi32(r, g, 0x92), bl, 0x24));
    _mm256_storeu_si256((__m256i *)(p + 8), _mm256_blend_epi32(_mm256_blend_epi32(bl, r, 0x92), g, 0x24));
    _mm256_storeu_si256((__m256i *)(p + 16), _mm256_blend_epi32(_mm256_blend_epi32(g, bl, 0x92), r, 0x24));
  }
  static VI bits(const int8_t *p, int32_t i) {
    const __m256i byte = _mm256_set1_epi32(p[i >> 3] & 0xFF);
    const __m256i lanes = _mm256_setr_epi32(1, 2, 4, 8, 16, 32, 64, 128);
    return {_mm256_cmpeq_epi32(_mm256_and_si256(byte, lanes), lanes)};
  }
  // a0 a2 b0 b2 | a4 a6 b4 b6 within each half, then the halves' 64-bit quarters put in order
  static VI evens(VI a, VI b) {
    const __m256 pairs = _mm256_shuffle_ps(_mm256_castsi256_ps(a.v), _mm256_castsi256_ps(b.v), _MM_SHUFFLE(2, 0, 2, 0));
    return {_mm256_permute4x64_epi64(_mm256_castps_si256(pairs), _MM_SHUFFLE(3, 1, 2, 0))};
  }
};

struct VF {
  __m256 v;
  static VF set1(float x) { return {_mm256_set1_ps(x)}; }
  static VF from(VI a) { return {_mm256_cvtepi32_ps(a.v)}; }
  static VF bits(VI a) { return {_mm256_castsi256_ps(a.v)}; }
  VI bits() const { return {_mm256_castps_si256(v)}; }
  friend VF operator+(VF a, VF b) { return {_mm256_add_ps(a.v, b.v)}; }
  friend VF operator-(VF a, VF b) { return {_mm256_sub_ps(a.v, b.v)}; }
  friend VF operator*(VF a, VF b) { return {_mm256_mul_ps(a.v, b.v)}; }
  friend VF operator-(VF a) { return {_mm256_xor_ps(a.v, _mm256_set1_ps(-0.0f))}; }
};

struct VD {
  static constexpr int N = 4;
  __m256d v;
  static VD set1(double x) { return {_mm256_set1_pd(x)}; }
  static VD loadf(const float *p) { return {_mm256_cvtps_pd(_mm_loadu_ps(p))}; }
  friend VD operator+(VD a, VD b) { return {_mm256_add_pd(a.v, b.v)}; }
  friend VD operator*(VD a, VD b) { return {_mm256_mul_pd(a.v, b.v)}; }
  void store(double *p) const { _mm256_storeu_pd(p, v); }
};

#elif defined(MCV2_SIMD_AVX512)

// 16 lanes; a block narrower than 16 pixels goes to the AVX2 kernels (exports.inc), so every row a kernel steps through
// is whole vectors. Only CPUs with the Ice Lake feature set run it (cpu.cpp), never the ones that slow down at 512
// bits.
struct VI {
  static constexpr int N = 16;
  __m512i v;
  static VI load(const int32_t *p) { return {_mm512_loadu_si512(p)}; }
  void store(int32_t *p) const { _mm512_storeu_si512(p, v); }
  static VI set1(int32_t x) { return {_mm512_set1_epi32(x)}; }
  static VI zero() { return {_mm512_setzero_si512()}; }
  static VI iota() { return {_mm512_set_epi32(15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0)}; }
  static VI loadu8(const uint8_t *p) { return {_mm512_cvtepu8_epi32(_mm_loadu_si128((const __m128i *)p))}; }
  friend VI operator+(VI a, VI b) { return {_mm512_add_epi32(a.v, b.v)}; }
  friend VI operator-(VI a, VI b) { return {_mm512_sub_epi32(a.v, b.v)}; }
  friend VI operator*(VI a, VI b) { return {_mm512_mullo_epi32(a.v, b.v)}; }
  friend VI operator&(VI a, VI b) { return {_mm512_and_si512(a.v, b.v)}; }
  VI shl(int32_t k) const { return {_mm512_sll_epi32(v, _mm_cvtsi32_si128(k & 31))}; }
  VI sar(int32_t k) const { return {_mm512_sra_epi32(v, _mm_cvtsi32_si128(k & 31))}; }
  static VI min(VI a, VI b) { return {_mm512_min_epi32(a.v, b.v)}; }
  static VI max(VI a, VI b) { return {_mm512_max_epi32(a.v, b.v)}; }
  static VI abs(VI a) { return {_mm512_abs_epi32(a.v)}; }
  static VI less(VI a, VI b) { return {_mm512_movm_epi32(_mm512_cmplt_epi32_mask(a.v, b.v))}; }
  int32_t sum() const { return _mm512_reduce_add_epi32(v); }
  // channel c of pixel k is element 3k + c of the 48 values: the first two vectors give the elements below 32, the
  // third the rest
  static void load3(const int32_t *p, VI &a, VI &b, VI &c) {
    const __m512i v0 = _mm512_loadu_si512(p);
    const __m512i v1 = _mm512_loadu_si512(p + 16);
    const __m512i v2 = _mm512_loadu_si512(p + 32);
    a.v = gather(v0, v1, v2, 0);
    b.v = gather(v0, v1, v2, 1);
    c.v = gather(v0, v1, v2, 2);
  }
  static void store3(int32_t *p, VI a, VI b, VI c) {
    _mm512_storeu_si512(p, scatter(a.v, b.v, c.v, 0));
    _mm512_storeu_si512(p + 16, scatter(a.v, b.v, c.v, 1));
    _mm512_storeu_si512(p + 32, scatter(a.v, b.v, c.v, 2));
  }
  // the 16 bits of lanes 0.. from bit i of the byte array, i a multiple of 8
  static VI bits(const int8_t *p, int32_t i) {
    const int32_t at = i >> 3;
    const uint32_t word = (uint32_t)(uint8_t)p[at] | ((uint32_t)(uint8_t)p[at + 1] << 8);
    return {_mm512_movm_epi32((__mmask16)word)};
  }
  static VI evens(VI a, VI b) {
    const __m512i index = _mm512_set_epi32(30, 28, 26, 24, 22, 20, 18, 16, 14, 12, 10, 8, 6, 4, 2, 0);
    return {_mm512_permutex2var_epi32(a.v, index, b.v)};
  }

private:
  // gather[c]: channel c's elements below 32 from the first two vectors, then the rest from the third; scatter[j]:
  // output vector j's elements from the first two channels, then the third's (element f is channel f % 3 of pixel f /
  // 3)
  alignas(64) static constexpr int32_t GATHER_FIRST[3][16] = {{0, 3, 6, 9, 12, 15, 18, 21, 24, 27, 30, 0, 0, 0, 0, 0},
                                                              {1, 4, 7, 10, 13, 16, 19, 22, 25, 28, 31, 0, 0, 0, 0, 0},
                                                              {2, 5, 8, 11, 14, 17, 20, 23, 26, 29, 0, 0, 0, 0, 0, 0}};
  alignas(64) static constexpr int32_t GATHER_SECOND[3][16] = {{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 17, 20, 23, 26, 29},
                                                               {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 18, 21, 24, 27, 30},
                                                               {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 16, 19, 22, 25, 28, 31}};
  alignas(64) static constexpr int32_t SCATTER_FIRST[3][16] = {
      {0, 16, 0, 1, 17, 1, 2, 18, 2, 3, 19, 3, 4, 20, 4, 5},
      {21, 5, 6, 22, 6, 7, 23, 7, 8, 24, 8, 9, 25, 9, 10, 26},
      {10, 11, 27, 11, 12, 28, 12, 13, 29, 13, 14, 30, 14, 15, 31, 15}};
  alignas(64) static constexpr int32_t SCATTER_SECOND[3][16] = {
      {0, 1, 16, 3, 4, 17, 6, 7, 18, 9, 10, 19, 12, 13, 20, 15},
      {0, 21, 2, 3, 22, 5, 6, 23, 8, 9, 24, 11, 12, 25, 14, 15},
      {26, 1, 2, 27, 4, 5, 28, 7, 8, 29, 10, 11, 30, 13, 14, 31}};

  static __m512i gather(__m512i v0, __m512i v1, __m512i v2, int32_t channel) {
    const __m512i low = _mm512_permutex2var_epi32(v0, _mm512_load_si512(GATHER_FIRST[channel]), v1);
    return _mm512_permutex2var_epi32(low, _mm512_load_si512(GATHER_SECOND[channel]), v2);
  }
  static __m512i scatter(__m512i a, __m512i b, __m512i c, int32_t j) {
    const __m512i ab = _mm512_permutex2var_epi32(a, _mm512_load_si512(SCATTER_FIRST[j]), b);
    return _mm512_permutex2var_epi32(ab, _mm512_load_si512(SCATTER_SECOND[j]), c);
  }
};

struct VF {
  __m512 v;
  static VF set1(float x) { return {_mm512_set1_ps(x)}; }
  static VF from(VI a) { return {_mm512_cvtepi32_ps(a.v)}; }
  static VF bits(VI a) { return {_mm512_castsi512_ps(a.v)}; }
  VI bits() const { return {_mm512_castps_si512(v)}; }
  friend VF operator+(VF a, VF b) { return {_mm512_add_ps(a.v, b.v)}; }
  friend VF operator-(VF a, VF b) { return {_mm512_sub_ps(a.v, b.v)}; }
  friend VF operator*(VF a, VF b) { return {_mm512_mul_ps(a.v, b.v)}; }
  friend VF operator-(VF a) { return {_mm512_xor_ps(a.v, _mm512_set1_ps(-0.0f))}; }
};

struct VD {
  static constexpr int N = 8;
  __m512d v;
  static VD set1(double x) { return {_mm512_set1_pd(x)}; }
  static VD loadf(const float *p) { return {_mm512_cvtps_pd(_mm256_loadu_ps(p))}; }
  friend VD operator+(VD a, VD b) { return {_mm512_add_pd(a.v, b.v)}; }
  friend VD operator*(VD a, VD b) { return {_mm512_mul_pd(a.v, b.v)}; }
  void store(double *p) const { _mm512_storeu_pd(p, v); }
};

#elif defined(MCV2_SIMD_NEON)

struct VI {
  static constexpr int N = 4;
  int32x4_t v;
  static VI load(const int32_t *p) { return {vld1q_s32(p)}; }
  void store(int32_t *p) const { vst1q_s32(p, v); }
  static VI set1(int32_t x) { return {vdupq_n_s32(x)}; }
  static VI zero() { return {vdupq_n_s32(0)}; }
  static VI iota() {
    static const int32_t lanes[4] = {0, 1, 2, 3};
    return {vld1q_s32(lanes)};
  }
  static VI loadu8(const uint8_t *p) {
    uint32_t word;
    memcpy(&word, p, sizeof(word));
    const uint8x8_t bytes = vreinterpret_u8_u32(vdup_n_u32(word));
    return {vreinterpretq_s32_u32(vmovl_u16(vget_low_u16(vmovl_u8(bytes))))};
  }
  friend VI operator+(VI a, VI b) { return {vaddq_s32(a.v, b.v)}; }
  friend VI operator-(VI a, VI b) { return {vsubq_s32(a.v, b.v)}; }
  friend VI operator*(VI a, VI b) { return {vmulq_s32(a.v, b.v)}; }
  friend VI operator&(VI a, VI b) { return {vandq_s32(a.v, b.v)}; }
  VI shl(int32_t k) const { return {vshlq_s32(v, vdupq_n_s32(k & 31))}; }
  VI sar(int32_t k) const { return {vshlq_s32(v, vdupq_n_s32(-(k & 31)))}; }
  static VI min(VI a, VI b) { return {vminq_s32(a.v, b.v)}; }
  static VI max(VI a, VI b) { return {vmaxq_s32(a.v, b.v)}; }
  static VI abs(VI a) { return {vabsq_s32(a.v)}; }
  static VI less(VI a, VI b) { return {vreinterpretq_s32_u32(vcltq_s32(a.v, b.v))}; }
  int32_t sum() const { return vaddvq_s32(v); }
  static void load3(const int32_t *p, VI &a, VI &b, VI &c) {
    const int32x4x3_t t = vld3q_s32(p);
    a.v = t.val[0];
    b.v = t.val[1];
    c.v = t.val[2];
  }
  static void store3(int32_t *p, VI a, VI b, VI c) {
    int32x4x3_t t;
    t.val[0] = a.v;
    t.val[1] = b.v;
    t.val[2] = c.v;
    vst3q_s32(p, t);
  }
  static VI bits(const int8_t *p, int32_t i) {
    static const int32_t lanes[4] = {1, 2, 4, 8};
    const int32x4_t byte = vdupq_n_s32((p[i >> 3] >> (i & 7)) & 0xF);
    return {vreinterpretq_s32_u32(vtstq_s32(byte, vld1q_s32(lanes)))};
  }
  static VI evens(VI a, VI b) { return {vuzp1q_s32(a.v, b.v)}; }
};

struct VF {
  float32x4_t v;
  static VF set1(float x) { return {vdupq_n_f32(x)}; }
  static VF from(VI a) { return {vcvtq_f32_s32(a.v)}; }
  static VF bits(VI a) { return {vreinterpretq_f32_s32(a.v)}; }
  VI bits() const { return {vreinterpretq_s32_f32(v)}; }
  friend VF operator+(VF a, VF b) { return {vaddq_f32(a.v, b.v)}; }
  friend VF operator-(VF a, VF b) { return {vsubq_f32(a.v, b.v)}; }
  friend VF operator*(VF a, VF b) { return {vmulq_f32(a.v, b.v)}; }
  friend VF operator-(VF a) { return {vnegq_f32(a.v)}; }
};

struct VD {
  static constexpr int N = 2;
  float64x2_t v;
  static VD set1(double x) { return {vdupq_n_f64(x)}; }
  static VD loadf(const float *p) { return {vcvt_f64_f32(vld1_f32(p))}; }
  friend VD operator+(VD a, VD b) { return {vaddq_f64(a.v, b.v)}; }
  friend VD operator*(VD a, VD b) { return {vmulq_f64(a.v, b.v)}; }
  void store(double *p) const { vst1q_f64(p, v); }
};

#elif defined(MCV2_SIMD_SVE256) || defined(MCV2_SIMD_SVE512)

// SVE at one vector length, fixed when the unit is compiled (-msve-vector-bits): the dispatch runs a unit only on a CPU
// whose vector length is exactly that (svcntb, cpu.cpp). The 512-bit unit hands blocks narrower than 16 pixels to NEON.
#if defined(MCV2_SIMD_SVE256)
#define MCV2_SVE_BITS 256
#else
#define MCV2_SVE_BITS 512
#endif
typedef svint32_t sve_i32 __attribute__((arm_sve_vector_bits(MCV2_SVE_BITS)));
typedef svuint64_t sve_u64 __attribute__((arm_sve_vector_bits(MCV2_SVE_BITS)));
typedef svfloat32_t sve_f32 __attribute__((arm_sve_vector_bits(MCV2_SVE_BITS)));
typedef svfloat64_t sve_f64 __attribute__((arm_sve_vector_bits(MCV2_SVE_BITS)));

struct VI {
  static constexpr int N = MCV2_SVE_BITS / 32;
  sve_i32 v;
  static VI load(const int32_t *p) { return {svld1_s32(svptrue_b32(), p)}; }
  void store(int32_t *p) const { svst1_s32(svptrue_b32(), p, v); }
  static VI set1(int32_t x) { return {svdup_n_s32(x)}; }
  static VI zero() { return {svdup_n_s32(0)}; }
  static VI iota() { return {svindex_s32(0, 1)}; }
  static VI loadu8(const uint8_t *p) { return {svreinterpret_s32_u32(svld1ub_u32(svptrue_b32(), p))}; }
  friend VI operator+(VI a, VI b) { return {svadd_s32_x(svptrue_b32(), a.v, b.v)}; }
  friend VI operator-(VI a, VI b) { return {svsub_s32_x(svptrue_b32(), a.v, b.v)}; }
  friend VI operator*(VI a, VI b) { return {svmul_s32_x(svptrue_b32(), a.v, b.v)}; }
  friend VI operator&(VI a, VI b) { return {svand_s32_x(svptrue_b32(), a.v, b.v)}; }
  VI shl(int32_t k) const { return {svlsl_n_s32_x(svptrue_b32(), v, (uint32_t)(k & 31))}; }
  VI sar(int32_t k) const { return {svasr_n_s32_x(svptrue_b32(), v, (uint32_t)(k & 31))}; }
  static VI min(VI a, VI b) { return {svmin_s32_x(svptrue_b32(), a.v, b.v)}; }
  static VI max(VI a, VI b) { return {svmax_s32_x(svptrue_b32(), a.v, b.v)}; }
  static VI abs(VI a) { return {svabs_s32_x(svptrue_b32(), a.v)}; }
  static VI less(VI a, VI b) {
    return {svsel_s32(svcmplt_s32(svptrue_b32(), a.v, b.v), svdup_n_s32(-1), svdup_n_s32(0))};
  }
  // the 64-bit sum of the lanes, cut to 32 bits: the same as summing them with wrapping, as Java does
  int32_t sum() const { return (int32_t)(uint32_t)(uint64_t)svaddv_s32(svptrue_b32(), v); }
  static void load3(const int32_t *p, VI &a, VI &b, VI &c) {
    const svint32x3_t t = svld3_s32(svptrue_b32(), p);
    a.v = svget3_s32(t, 0);
    b.v = svget3_s32(t, 1);
    c.v = svget3_s32(t, 2);
  }
  static void store3(int32_t *p, VI a, VI b, VI c) { svst3_s32(svptrue_b32(), p, svcreate3_s32(a.v, b.v, c.v)); }
  // the N bits of lanes 0.. from bit i of the byte array, i a multiple of 8
  static VI bits(const int8_t *p, int32_t i) {
    const int32_t at = i >> 3;
    uint32_t word = (uint8_t)p[at];
    if (N > 8) {
      word |= (uint32_t)(uint8_t)p[at + 1] << 8;
    }
    const svuint32_t lanes = svindex_u32(0, 1);
    const svuint32_t bit = svand_n_u32_x(svptrue_b32(), svlsr_u32_x(svptrue_b32(), svdup_n_u32(word), lanes), 1);
    return {svsel_s32(svcmpne_n_u32(svptrue_b32(), bit, 0), svdup_n_s32(-1), svdup_n_s32(0))};
  }
  static VI evens(VI a, VI b) { return {svuzp1_s32(a.v, b.v)}; }
};

struct VF {
  sve_f32 v;
  static VF set1(float x) { return {svdup_n_f32(x)}; }
  static VF from(VI a) { return {svcvt_f32_s32_x(svptrue_b32(), a.v)}; }
  static VF bits(VI a) { return {svreinterpret_f32_s32(a.v)}; }
  VI bits() const { return {svreinterpret_s32_f32(v)}; }
  friend VF operator+(VF a, VF b) { return {svadd_f32_x(svptrue_b32(), a.v, b.v)}; }
  friend VF operator-(VF a, VF b) { return {svsub_f32_x(svptrue_b32(), a.v, b.v)}; }
  friend VF operator*(VF a, VF b) { return {svmul_f32_x(svptrue_b32(), a.v, b.v)}; }
  friend VF operator-(VF a) { return {svneg_f32_x(svptrue_b32(), a.v)}; }
};

struct VD {
  static constexpr int N = MCV2_SVE_BITS / 64;
  sve_f64 v;
  static VD set1(double x) { return {svdup_n_f64(x)}; }
  // each float into the low half of a 64-bit lane, where the conversion reads it
  static VD loadf(const float *p) {
    const sve_u64 words = svld1uw_u64(svptrue_b64(), (const uint32_t *)p);
    return {svcvt_f64_f32_x(svptrue_b64(), svreinterpret_f32_u64(words))};
  }
  friend VD operator+(VD a, VD b) { return {svadd_f64_x(svptrue_b64(), a.v, b.v)}; }
  friend VD operator*(VD a, VD b) { return {svmul_f64_x(svptrue_b64(), a.v, b.v)}; }
  void store(double *p) const { svst1_f64(svptrue_b64(), p, v); }
};

#endif

} // namespace mcv2

#endif
