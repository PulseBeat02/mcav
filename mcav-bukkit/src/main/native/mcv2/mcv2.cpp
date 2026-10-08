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

// The native pixel kernels of MCV2's encoder (MCV2.java), in one file: the C interface, the vector types of every
// dispatch level, the kernels written once over them, their exports, and the CPU detection. Every kernel computes
// exactly what the Java kernel of the same name in MCV2.java computes, operation for operation: integer arithmetic
// wraps as Java's does, and floating-point values are computed in the same precision and order, since -ffp-contract=off
// keeps the compiler from fusing a product into a sum. Java validates every size, offset and array length before a
// call: a kernel reads and writes only inside the arrays it is given, allocates nothing, and keeps no state between
// calls.
//
// A level's translation unit (level_<level>.cpp, the build glue build.sh compiles with that level's flags) defines
// exactly one MCV2_SIMD_<LEVEL> and MCV2_PREFIX(name), and includes this file; the scalar unit also defines MCV2_CPU,
// which adds the CPU detection. Everything but the exports has internal linkage, so the levels' copies never meet at
// link time. MCV2_DECLARATIONS_ONLY gives just the interface, for the kernels' standalone test.

#include <stdint.h>
#include <string.h>

#if defined(_WIN32)
#define MCV2_EXPORT __declspec(dllexport)
#else
#define MCV2_EXPORT __attribute__((visibility("default")))
#endif

// The version of this interface: Java refuses a library whose version differs from the one it was written for.
#define MCV2_ABI 5

// The dispatch levels, as bits of mcv2_cpu_levels().
#define MCV2_LEVEL_SCALAR 1
#define MCV2_LEVEL_SSE41 2
#define MCV2_LEVEL_AVX2 4
#define MCV2_LEVEL_NEON 8
#define MCV2_LEVEL_SSE2 16
#define MCV2_LEVEL_AVX512 32
#define MCV2_LEVEL_SVE256 64
#define MCV2_LEVEL_SVE512 128

// The auxiliary vector's AT_HWCAP bit that says the Linux kernel lets programs run SVE (HWCAP_SVE).
#define MCV2_HWCAP_SVE (1LL << 22)

// The kernels of one level: X(return type, name, parameters). The scored reconstructions return the block's
// distortion once every row is measured, or -1 at the first row after which the candidate can no longer be cheaper
// than the limit, where Java's measure stops.
#define MCV2_KERNELS(X)                                                                                                \
  X(int64_t, predicted,                                                                                                \
    (const int32_t *prediction, int32_t size, int32_t *out, const int32_t *source, double rate, double limit))         \
  X(int64_t, solid, (int32_t color, int32_t size, int32_t * out, const int32_t *source, double rate, double limit))    \
  X(int64_t, palette,                                                                                                  \
    (const int8_t *record, int32_t size, int32_t *out, const int32_t *source, double rate, double limit))              \
  X(int64_t, compact,                                                                                                  \
    (const int32_t *prediction, const int8_t *record, int32_t q, int32_t size, int32_t *out, const int32_t *source,    \
     double rate, double limit))                                                                                       \
  X(void, predict,                                                                                                     \
    (const uint8_t *reference, int32_t width, int32_t height, int32_t x, int32_t y, int32_t size, int32_t mx,          \
     int32_t my, int32_t *out))                                                                                        \
  X(void, fit, (const float *values, int32_t size, const float *matrix, float *out))                                   \
  X(void, cluster, (const int32_t *source, int32_t size, int32_t *endpoints))                                          \
  X(void, assign, (const int32_t *source, int32_t count, const int32_t *colors, int8_t *selectors))                    \
  X(int32_t, assign_pattern, (const int32_t *source, int32_t size, const int32_t *colors, int8_t *selectors))          \
  X(int32_t, seeded,                                                                                                   \
    (const uint8_t *reference, int32_t width, int32_t height, const int32_t *source, int32_t x, int32_t y,             \
     int32_t size, int32_t range, const int32_t *seeds, int32_t seed_count))                                           \
  X(void, load_source,                                                                                                 \
    (const uint8_t *image, int32_t width, int32_t height, int32_t x, int32_t y, int32_t size, int32_t *source))        \
  X(void, halve, (const int32_t *block, int32_t size, int32_t *out))                                                   \
  X(void, residual_target, (const int32_t *source, const int32_t *prediction, int32_t count, float *target))

extern "C" {
// The dispatch levels this CPU runs, as a bit set of MCV2_LEVEL_*: scalar always, the others only where the CPU and
// the operating system support their instructions. hwcap is AT_HWCAP of the process's auxiliary vector on Linux, which
// Java reads (/proc/self/auxv) so the library needs no C library, and 0 elsewhere; on AArch64 its SVE bit decides
// whether the SVE levels may run, and the vector length which one.
MCV2_EXPORT int32_t mcv2_cpu_levels(int64_t hwcap);

// MCV2_ABI of the library.
MCV2_EXPORT int32_t mcv2_abi(void);
}

#if !defined(MCV2_DECLARATIONS_ONLY)
// The vector types of one dispatch level. A translation unit defines exactly one of MCV2_SIMD_SCALAR, MCV2_SIMD_SSE2,
// MCV2_SIMD_SSE41, MCV2_SIMD_AVX2, MCV2_SIMD_AVX512, MCV2_SIMD_NEON, MCV2_SIMD_SVE256 or MCV2_SIMD_SVE512 before
// including this file, and each unit is compiled with its own extensions only, so no instruction can run on a CPU
// that the dispatch did not find it on.
//
// VI holds N int32 lanes with Java's arithmetic: sums and products wrap, shifts to the right are arithmetic. The
// kernels are written once over VI, so every level runs the same operations on the same values; a level only decides
// how many pixels one operation covers.

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
#include <arm_neon.h>
#include <arm_sve.h>
#elif !defined(MCV2_SIMD_SCALAR)
#error "define one of the MCV2_SIMD_ levels"
#endif

namespace mcv2 {
// Every level's types and helpers have the same names, so they must not be visible outside their translation unit: an
// inline function the compiler keeps out of line would otherwise be one weak symbol for every level, and the linker
// could give the SSE2 kernels the AVX-512 copy.
namespace {

// Whether load3 and store3 are shuffles rather than instructions: SSE takes a dozen of them for four pixels, so a
// kernel that only adds a few values of each pixel (halve) runs faster as plain code there.
#if defined(MCV2_SIMD_SSE2) || defined(MCV2_SIMD_SSE41)
constexpr bool SHUFFLED_LOAD3 = true;
#else
constexpr bool SHUFFLED_LOAD3 = false;
#endif

// Java's int arithmetic on one value: wrapping, and arithmetic shifts.
inline int32_t wrap_add(int32_t a, int32_t b) { return (int32_t)((uint32_t)a + (uint32_t)b); }
inline int32_t wrap_sub(int32_t a, int32_t b) { return (int32_t)((uint32_t)a - (uint32_t)b); }
inline int32_t wrap_mul(int32_t a, int32_t b) { return (int32_t)((uint32_t)a * (uint32_t)b); }
inline int32_t shl(int32_t a, int32_t k) { return (int32_t)((uint32_t)a << (k & 31)); }
inline int32_t sar(int32_t a, int32_t k) { return a >> (k & 31); }

// A pixel's three bytes as the low bytes of a word, its top byte zero: read byte by byte, so never past the pixel.
inline uint32_t pixel_word(const uint8_t *p) { return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16; }

// One int lane: the scalar level's VI, and every level's for a kernel whose lanes do not pay there (the compiler then
// vectorizes the one-lane code itself).
struct VI1 {
  static constexpr int N = 1;
  int32_t v;
  static VI1 load(const int32_t *p) { return {p[0]}; }
  void store(int32_t *p) const { p[0] = v; }
  // each lane's low byte, lanes holding 0 or 1
  void store_bytes(int8_t *p) const { p[0] = (int8_t)v; }
  static VI1 set1(int32_t x) { return {x}; }
  static VI1 zero() { return {0}; }
  static VI1 iota() { return {0}; }
  static VI1 loadu8(const uint8_t *p) { return {p[0]}; }
  friend VI1 operator+(VI1 a, VI1 b) { return {wrap_add(a.v, b.v)}; }
  friend VI1 operator-(VI1 a, VI1 b) { return {wrap_sub(a.v, b.v)}; }
  friend VI1 operator*(VI1 a, VI1 b) { return {wrap_mul(a.v, b.v)}; }
  friend VI1 operator&(VI1 a, VI1 b) { return {a.v & b.v}; }
  friend VI1 operator|(VI1 a, VI1 b) { return {a.v | b.v}; }
  VI1 shl(int32_t k) const { return {mcv2::shl(v, k)}; }
  VI1 sar(int32_t k) const { return {mcv2::sar(v, k)}; }
  static VI1 min(VI1 a, VI1 b) { return {a.v < b.v ? a.v : b.v}; }
  static VI1 max(VI1 a, VI1 b) { return {a.v > b.v ? a.v : b.v}; }
  static VI1 abs(VI1 a) { return {a.v < 0 ? wrap_sub(0, a.v) : a.v}; }
  // all ones where a < b, else zero
  static VI1 less(VI1 a, VI1 b) { return {a.v < b.v ? -1 : 0}; }
  int32_t sum() const { return v; }
  static void load3(const int32_t *p, VI1 &a, VI1 &b, VI1 &c) {
    a.v = p[0];
    b.v = p[1];
    c.v = p[2];
  }
  static void store3(int32_t *p, VI1 a, VI1 b, VI1 c) {
    p[0] = a.v;
    p[1] = b.v;
    p[2] = c.v;
  }
  // all ones in the lanes whose bit is set, the bits of lanes 0.. starting at bit i of the byte array
  static VI1 bits(const int8_t *p, int32_t i) { return {((p[i >> 3] >> (i & 7)) & 1) ? -1 : 0}; }
  // the even lanes of a, then the even lanes of b
  static VI1 evens(VI1 a, VI1) { return a; }
  // the odd lanes of a, then the odd lanes of b
  static VI1 odds(VI1, VI1 b) { return b; }
};

#if defined(MCV2_SIMD_SCALAR)

using VI = VI1;

// the sum of the absolute differences of the bytes of four words and of sixteen bytes
inline int32_t sad4(uint32_t a, uint32_t b, uint32_t c, uint32_t d, const uint8_t *s) {
  const uint32_t words[4] = {a, b, c, d};
  int32_t sum = 0;
  for (int32_t k = 0; k < 16; k++) {
    const int32_t difference = (int32_t)((words[k / 4] >> (8 * (k % 4))) & 0xFF) - s[k];
    sum += difference < 0 ? -difference : difference;
  }
  return sum;
}

// Float lanes match the integer lanes for residual conversion.
struct VF {
  float v;
  static VF set1(float x) { return {x}; }
  static VF from(VI a) { return {(float)a.v}; }
  friend VF operator+(VF a, VF b) { return {a.v + b.v}; }
  friend VF operator-(VF a, VF b) { return {a.v - b.v}; }
  friend VF operator*(VF a, VF b) { return {a.v * b.v}; }
  void store(float *p) const { p[0] = v; }
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
// blendps rather than pblendw: it issues on any vector port, where pblendw competes with the shuffles for one
template <int M> inline __m128i blend32(__m128i a, __m128i b) {
  return _mm_castps_si128(_mm_blend_ps(_mm_castsi128_ps(a), _mm_castsi128_ps(b), M));
}
inline __m128i mullo32(__m128i a, __m128i b) { return _mm_mullo_epi32(a, b); }
inline __m128i min32(__m128i a, __m128i b) { return _mm_min_epi32(a, b); }
inline __m128i max32(__m128i a, __m128i b) { return _mm_max_epi32(a, b); }
inline __m128i abs32(__m128i a) { return _mm_abs_epi32(a); }
inline __m128i widen8(__m128i bytes) { return _mm_cvtepu8_epi32(bytes); }
#else
// the 32-bit lanes whose bit of M is set taken from b, the others from a
template <int M> inline __m128i blend32(__m128i a, __m128i b) {
  const __m128i mask = _mm_setr_epi32((M & 1) ? -1 : 0, (M & 2) ? -1 : 0, (M & 4) ? -1 : 0, (M & 8) ? -1 : 0);
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

inline int32_t sad4(uint32_t a, uint32_t b, uint32_t c, uint32_t d, const uint8_t *s) {
  const __m128i sad =
      _mm_sad_epu8(_mm_setr_epi32((int32_t)a, (int32_t)b, (int32_t)c, (int32_t)d), _mm_loadu_si128((const __m128i *)s));
  return _mm_cvtsi128_si32(sad) + _mm_cvtsi128_si32(_mm_srli_si128(sad, 8));
}

struct VI {
  static constexpr int N = 4;
  __m128i v;
  static VI load(const int32_t *p) { return {_mm_loadu_si128((const __m128i *)p)}; }
  void store(int32_t *p) const { _mm_storeu_si128((__m128i *)p, v); }
  void store_bytes(int8_t *p) const {
    const int32_t word = _mm_cvtsi128_si32(_mm_packs_epi16(_mm_packs_epi32(v, v), _mm_setzero_si128()));
    memcpy(p, &word, sizeof(word));
  }
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
  friend VI operator|(VI a, VI b) { return {_mm_or_si128(a.v, b.v)}; }
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
  // (lane k is bit k of the blend mask), then one shuffle puts them in pixel order
  static void load3(const int32_t *p, VI &a, VI &b, VI &c) {
    const __m128i v0 = _mm_loadu_si128((const __m128i *)p);
    const __m128i v1 = _mm_loadu_si128((const __m128i *)(p + 4));
    const __m128i v2 = _mm_loadu_si128((const __m128i *)(p + 8));
    // r0 r3 r2 r1
    const __m128i r = blend32<0x2>(blend32<0x4>(v0, v1), v2);
    // g1 g0 g3 g2
    const __m128i g = blend32<0x4>(blend32<0x2>(v1, v0), v2);
    // b2 b1 b0 b3
    const __m128i bl = blend32<0x4>(blend32<0x2>(v2, v1), v0);
    a.v = _mm_shuffle_epi32(r, _MM_SHUFFLE(1, 2, 3, 0));
    b.v = _mm_shuffle_epi32(g, _MM_SHUFFLE(2, 3, 0, 1));
    c.v = _mm_shuffle_epi32(bl, _MM_SHUFFLE(3, 0, 1, 2));
  }
  static void store3(int32_t *p, VI a, VI b, VI c) {
    // v0 = r0 g0 b0 r1
    const __m128i v0 = blend32<0x4>(
        blend32<0x2>(_mm_shuffle_epi32(a.v, _MM_SHUFFLE(1, 0, 0, 0)), _mm_shuffle_epi32(b.v, _MM_SHUFFLE(0, 0, 0, 0))),
        _mm_shuffle_epi32(c.v, _MM_SHUFFLE(0, 0, 0, 0)));
    // v1 = g1 b1 r2 g2
    const __m128i v1 = blend32<0x4>(
        blend32<0x2>(_mm_shuffle_epi32(b.v, _MM_SHUFFLE(2, 0, 0, 1)), _mm_shuffle_epi32(c.v, _MM_SHUFFLE(1, 1, 1, 1))),
        _mm_shuffle_epi32(a.v, _MM_SHUFFLE(2, 2, 2, 2)));
    // v2 = b2 r3 g3 b3
    const __m128i v2 = blend32<0x4>(
        blend32<0x2>(_mm_shuffle_epi32(c.v, _MM_SHUFFLE(3, 0, 0, 2)), _mm_shuffle_epi32(a.v, _MM_SHUFFLE(3, 3, 3, 3))),
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
  static VI odds(VI a, VI b) {
    return {_mm_castps_si128(_mm_shuffle_ps(_mm_castsi128_ps(a.v), _mm_castsi128_ps(b.v), _MM_SHUFFLE(3, 1, 3, 1)))};
  }
};

struct VF {
  __m128 v;
  static VF set1(float x) { return {_mm_set1_ps(x)}; }
  static VF from(VI a) { return {_mm_cvtepi32_ps(a.v)}; }
  friend VF operator+(VF a, VF b) { return {_mm_add_ps(a.v, b.v)}; }
  friend VF operator-(VF a, VF b) { return {_mm_sub_ps(a.v, b.v)}; }
  friend VF operator*(VF a, VF b) { return {_mm_mul_ps(a.v, b.v)}; }
  void store(float *p) const { _mm_storeu_ps(p, v); }
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

inline int32_t sad4(uint32_t a, uint32_t b, uint32_t c, uint32_t d, const uint8_t *s) {
  const __m128i sad =
      _mm_sad_epu8(_mm_setr_epi32((int32_t)a, (int32_t)b, (int32_t)c, (int32_t)d), _mm_loadu_si128((const __m128i *)s));
  return _mm_cvtsi128_si32(sad) + _mm_cvtsi128_si32(_mm_srli_si128(sad, 8));
}

struct VI {
  static constexpr int N = 8;
  __m256i v;
  static VI load(const int32_t *p) { return {_mm256_loadu_si256((const __m256i *)p)}; }
  void store_bytes(int8_t *p) const {
    const __m128i words = _mm_packs_epi32(_mm256_castsi256_si128(v), _mm256_extracti128_si256(v, 1));
    _mm_storel_epi64((__m128i *)p, _mm_packs_epi16(words, _mm_setzero_si128()));
  }
  void store(int32_t *p) const { _mm256_storeu_si256((__m256i *)p, v); }
  static VI set1(int32_t x) { return {_mm256_set1_epi32(x)}; }
  static VI zero() { return {_mm256_setzero_si256()}; }
  static VI iota() { return {_mm256_setr_epi32(0, 1, 2, 3, 4, 5, 6, 7)}; }
  static VI loadu8(const uint8_t *p) { return {_mm256_cvtepu8_epi32(_mm_loadl_epi64((const __m128i *)p))}; }
  friend VI operator+(VI a, VI b) { return {_mm256_add_epi32(a.v, b.v)}; }
  friend VI operator-(VI a, VI b) { return {_mm256_sub_epi32(a.v, b.v)}; }
  friend VI operator*(VI a, VI b) { return {_mm256_mullo_epi32(a.v, b.v)}; }
  friend VI operator&(VI a, VI b) { return {_mm256_and_si256(a.v, b.v)}; }
  friend VI operator|(VI a, VI b) { return {_mm256_or_si256(a.v, b.v)}; }
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
  static VI odds(VI a, VI b) {
    const __m256 pairs = _mm256_shuffle_ps(_mm256_castsi256_ps(a.v), _mm256_castsi256_ps(b.v), _MM_SHUFFLE(3, 1, 3, 1));
    return {_mm256_permute4x64_epi64(_mm256_castps_si256(pairs), _MM_SHUFFLE(3, 1, 2, 0))};
  }
};

struct VF {
  __m256 v;
  static VF set1(float x) { return {_mm256_set1_ps(x)}; }
  static VF from(VI a) { return {_mm256_cvtepi32_ps(a.v)}; }
  friend VF operator+(VF a, VF b) { return {_mm256_add_ps(a.v, b.v)}; }
  friend VF operator-(VF a, VF b) { return {_mm256_sub_ps(a.v, b.v)}; }
  friend VF operator*(VF a, VF b) { return {_mm256_mul_ps(a.v, b.v)}; }
  void store(float *p) const { _mm256_storeu_ps(p, v); }
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

// 16 lanes; a block narrower than 16 pixels goes to the AVX2 kernels through the exported wrappers, so every row a
// kernel steps through is whole vectors. Only CPUs with the Ice Lake feature set run it, never the ones that slow down
// at 512 bits.
inline int32_t sad4(uint32_t a, uint32_t b, uint32_t c, uint32_t d, const uint8_t *s) {
  const __m128i sad =
      _mm_sad_epu8(_mm_setr_epi32((int32_t)a, (int32_t)b, (int32_t)c, (int32_t)d), _mm_loadu_si128((const __m128i *)s));
  return _mm_cvtsi128_si32(sad) + _mm_cvtsi128_si32(_mm_srli_si128(sad, 8));
}

struct VI {
  static constexpr int N = 16;
  __m512i v;
  static VI load(const int32_t *p) { return {_mm512_loadu_si512(p)}; }
  void store_bytes(int8_t *p) const { _mm_storeu_si128((__m128i *)p, _mm512_cvtepi32_epi8(v)); }
  void store(int32_t *p) const { _mm512_storeu_si512(p, v); }
  static VI set1(int32_t x) { return {_mm512_set1_epi32(x)}; }
  static VI zero() { return {_mm512_setzero_si512()}; }
  static VI iota() { return {_mm512_set_epi32(15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0)}; }
  static VI loadu8(const uint8_t *p) { return {_mm512_cvtepu8_epi32(_mm_loadu_si128((const __m128i *)p))}; }
  friend VI operator+(VI a, VI b) { return {_mm512_add_epi32(a.v, b.v)}; }
  friend VI operator-(VI a, VI b) { return {_mm512_sub_epi32(a.v, b.v)}; }
  friend VI operator*(VI a, VI b) { return {_mm512_mullo_epi32(a.v, b.v)}; }
  friend VI operator&(VI a, VI b) { return {_mm512_and_si512(a.v, b.v)}; }
  friend VI operator|(VI a, VI b) { return {_mm512_or_si512(a.v, b.v)}; }
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
  static VI odds(VI a, VI b) {
    const __m512i index = _mm512_set_epi32(31, 29, 27, 25, 23, 21, 19, 17, 15, 13, 11, 9, 7, 5, 3, 1);
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
  friend VF operator+(VF a, VF b) { return {_mm512_add_ps(a.v, b.v)}; }
  friend VF operator-(VF a, VF b) { return {_mm512_sub_ps(a.v, b.v)}; }
  friend VF operator*(VF a, VF b) { return {_mm512_mul_ps(a.v, b.v)}; }
  void store(float *p) const { _mm512_storeu_ps(p, v); }
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

inline int32_t sad4(uint32_t a, uint32_t b, uint32_t c, uint32_t d, const uint8_t *s) {
  const uint32_t words[4] = {a, b, c, d};
  return (int32_t)vaddlvq_u8(vabdq_u8(vreinterpretq_u8_u32(vld1q_u32(words)), vld1q_u8(s)));
}

struct VI {
  static constexpr int N = 4;
  int32x4_t v;
  static VI load(const int32_t *p) { return {vld1q_s32(p)}; }
  void store_bytes(int8_t *p) const {
    const int8x8_t bytes = vmovn_s16(vcombine_s16(vmovn_s32(v), vdup_n_s16(0)));
    vst1_lane_s32((int32_t *)p, vreinterpret_s32_s8(bytes), 0);
  }
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
  friend VI operator|(VI a, VI b) { return {vorrq_s32(a.v, b.v)}; }
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
  static VI odds(VI a, VI b) { return {vuzp2q_s32(a.v, b.v)}; }
};

struct VF {
  float32x4_t v;
  static VF set1(float x) { return {vdupq_n_f32(x)}; }
  static VF from(VI a) { return {vcvtq_f32_s32(a.v)}; }
  friend VF operator+(VF a, VF b) { return {vaddq_f32(a.v, b.v)}; }
  friend VF operator-(VF a, VF b) { return {vsubq_f32(a.v, b.v)}; }
  friend VF operator*(VF a, VF b) { return {vmulq_f32(a.v, b.v)}; }
  void store(float *p) const { vst1q_f32(p, v); }
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
// whose vector length is exactly that (svcntb). The 512-bit unit hands blocks narrower than 16 pixels to NEON.
#if defined(MCV2_SIMD_SVE256)
#define MCV2_SVE_BITS 256
#else
#define MCV2_SVE_BITS 512
#endif
typedef svint32_t sve_i32 __attribute__((arm_sve_vector_bits(MCV2_SVE_BITS)));
typedef svuint64_t sve_u64 __attribute__((arm_sve_vector_bits(MCV2_SVE_BITS)));
typedef svfloat32_t sve_f32 __attribute__((arm_sve_vector_bits(MCV2_SVE_BITS)));
typedef svfloat64_t sve_f64 __attribute__((arm_sve_vector_bits(MCV2_SVE_BITS)));

// NEON's, which every SVE CPU runs
inline int32_t sad4(uint32_t a, uint32_t b, uint32_t c, uint32_t d, const uint8_t *s) {
  const uint32_t words[4] = {a, b, c, d};
  return (int32_t)vaddlvq_u8(vabdq_u8(vreinterpretq_u8_u32(vld1q_u32(words)), vld1q_u8(s)));
}

struct VI {
  static constexpr int N = MCV2_SVE_BITS / 32;
  sve_i32 v;
  static VI load(const int32_t *p) { return {svld1_s32(svptrue_b32(), p)}; }
  void store_bytes(int8_t *p) const { svst1b_s32(svptrue_b32(), p, v); }
  void store(int32_t *p) const { svst1_s32(svptrue_b32(), p, v); }
  static VI set1(int32_t x) { return {svdup_n_s32(x)}; }
  static VI zero() { return {svdup_n_s32(0)}; }
  static VI iota() { return {svindex_s32(0, 1)}; }
  static VI loadu8(const uint8_t *p) { return {svreinterpret_s32_u32(svld1ub_u32(svptrue_b32(), p))}; }
  friend VI operator+(VI a, VI b) { return {svadd_s32_x(svptrue_b32(), a.v, b.v)}; }
  friend VI operator-(VI a, VI b) { return {svsub_s32_x(svptrue_b32(), a.v, b.v)}; }
  friend VI operator*(VI a, VI b) { return {svmul_s32_x(svptrue_b32(), a.v, b.v)}; }
  friend VI operator&(VI a, VI b) { return {svand_s32_x(svptrue_b32(), a.v, b.v)}; }
  friend VI operator|(VI a, VI b) { return {svorr_s32_x(svptrue_b32(), a.v, b.v)}; }
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
  static VI odds(VI a, VI b) { return {svuzp2_s32(a.v, b.v)}; }
};

struct VF {
  sve_f32 v;
  static VF set1(float x) { return {svdup_n_f32(x)}; }
  static VF from(VI a) { return {svcvt_f32_s32_x(svptrue_b32(), a.v)}; }
  friend VF operator+(VF a, VF b) { return {svadd_f32_x(svptrue_b32(), a.v, b.v)}; }
  friend VF operator-(VF a, VF b) { return {svsub_f32_x(svptrue_b32(), a.v, b.v)}; }
  friend VF operator*(VF a, VF b) { return {svmul_f32_x(svptrue_b32(), a.v, b.v)}; }
  void store(float *p) const { svst1_f32(svptrue_b32(), p, v); }
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

} // namespace
} // namespace mcv2

// The kernels of one dispatch level, written once over the level's VI and VD. Each is the Java method
// named in its comment, operation for operation: integer arithmetic wraps as Java's does, and floating-point values
// are computed in the same precision and order, since -ffp-contract=off keeps the compiler from fusing a product into
// a sum. Everything here has internal linkage, so the levels' copies never meet at link time.

namespace mcv2 {
namespace {

constexpr int32_t CHANNELS = 3;
constexpr int32_t MAX_CHANNEL = 255;
constexpr int32_t GRID = 4;
constexpr int32_t ROOT_SIZE = 32;
constexpr int32_t BLOCK_SIZES = 3;
constexpr int32_t SELECTORS_AT = 6;
constexpr double DISTORTION_SCALE = 96.0;
constexpr int32_t NIBBLE_BITS = 4;
constexpr int32_t NIBBLE_MASK = 15;

inline int32_t log2i(int32_t v) { return __builtin_ctz((unsigned)v); }
inline int32_t size_index(int32_t size) { return log2i(size) - 3; }
inline int32_t min32(int32_t a, int32_t b) { return a < b ? a : b; }
inline int32_t max32(int32_t a, int32_t b) { return a > b ? a : b; }
inline int32_t clamp32(int32_t v, int32_t low, int32_t high) { return min32(max32(v, low), high); }

// the compact grid's interpolation tables (MCV2.GRID_LOWER, GRID_UPPER, GRID_WEIGHTS): for pixel p of a block, the
// lower and upper node and the distance to the lower one in units of 1 / (2 size)
struct Axis {
  int32_t lower[ROOT_SIZE];
  int32_t upper[ROOT_SIZE];
  int32_t weight[ROOT_SIZE];
};

struct Axes {
  Axis axes[BLOCK_SIZES];
};

constexpr Axes make_axes() {
  Axes t{};
  for (int32_t s = 0; s < BLOCK_SIZES; s++) {
    const int32_t size = 8 << s;
    const int32_t span = 2 * size;
    for (int32_t p = 0; p < size; p++) {
      int32_t position = (2 * p + 1) * GRID - size;
      position = position < 0 ? 0 : position;
      position = position > (GRID - 1) * span ? (GRID - 1) * span : position;
      const int32_t lower = position / span;
      t.axes[s].lower[p] = lower;
      t.axes[s].upper[p] = lower + 1 < GRID - 1 ? lower + 1 : GRID - 1;
      t.axes[s].weight[p] = position - lower * span;
    }
  }
  return t;
}

constexpr Axes AXES = make_axes();

inline int32_t shift_of(int32_t size) { return 2 * (log2i(size) + 1); }

inline VI round(VI value, int32_t shift) {
  return VI::min(VI::max((value + VI::set1(1 << (shift - 1))).sar(shift), VI::zero()), VI::set1(MAX_CHANNEL));
}

// MCV2.Score: the measure of a reconstruction, row by row
struct Measure {
  const int32_t *source;
  double rate;
  double limit;
  int64_t distortion;
};

// MCV2.Score.row: adds a row's error; false once the candidate can no longer be cheaper than the limit. The row's sum
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

// MCV2.JavaKernels.horizontal: every row of the 4x4 nodes interpolated across the block's width, times 2 size
inline void horizontal(const int32_t *nodes, int32_t size, int32_t *rows) {
  const Axis &a = AXES.axes[size_index(size)];
  const int32_t span = 2 * size;
  for (int32_t j = 0; j < GRID; j++) {
    const int32_t at = j * size;
    for (int32_t x = 0; x < size; x++) {
      const int32_t w = a.weight[x];
      rows[at + x] =
          wrap_add(wrap_mul(nodes[j * GRID + a.lower[x]], span - w), wrap_mul(nodes[j * GRID + a.upper[x]], w));
    }
  }
}

// MCV2.JavaKernels.vertical: one row of the interpolated plane, times (2 size)^2
inline void vertical(const int32_t *rows, int32_t size, int32_t y, int32_t *line) {
  const Axis &a = AXES.axes[size_index(size)];
  const int32_t top = a.lower[y] * size;
  const int32_t bottom = a.upper[y] * size;
  const VI w = VI::set1(a.weight[y]);
  const VI v = VI::set1(2 * size - a.weight[y]);
  for (int32_t x = 0; x < size; x += VI::N) {
    (VI::load(rows + top + x) * v + VI::load(rows + bottom + x) * w).store(line + x);
  }
}

int64_t predicted(const int32_t *prediction, int32_t size, int32_t *out, Measure measure) {
  const int32_t row_length = size * CHANNELS;
  for (int32_t row_index = 0; row_index < size; row_index++) {
    const int32_t from = row_index * row_length;
    for (int32_t offset = from; offset < from + row_length; offset += VI::N) {
      VI::load(prediction + offset).store(out + offset);
    }
    if (!row(measure, out, from, size)) {
      return -1;
    }
  }
  return measure.distortion;
}

// MCV2.JavaKernels.solid
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

int64_t palette(const int8_t *record, int32_t size, int32_t *out, Measure measure) {
  const VI first_red = VI::set1((uint8_t)record[0]);
  const VI first_green = VI::set1((uint8_t)record[1]);
  const VI first_blue = VI::set1((uint8_t)record[2]);
  const VI red_delta = VI::set1((uint8_t)record[3] - (uint8_t)record[0]);
  const VI green_delta = VI::set1((uint8_t)record[4] - (uint8_t)record[1]);
  const VI blue_delta = VI::set1((uint8_t)record[5] - (uint8_t)record[2]);
  for (int32_t row_index = 0; row_index < size; row_index++) {
    for (int32_t column = 0; column < size; column += VI::N) {
      const int32_t pixel = row_index * size + column;
      const VI bit = VI::bits(record + SELECTORS_AT, pixel);
      VI::store3(out + pixel * CHANNELS, first_red + (red_delta & bit), first_green + (green_delta & bit),
                 first_blue + (blue_delta & bit));
    }
    if (!row(measure, out, row_index * size * CHANNELS, size)) {
      return -1;
    }
  }
  return measure.distortion;
}

int64_t compact(const int32_t *prediction, const int8_t *record, int32_t quantizer, int32_t size, int32_t *out,
                Measure measure) {
  int32_t nodes[GRID * GRID];
  for (int32_t index = 0; index < GRID * GRID; index++) {
    const int32_t packed = (uint8_t)record[2 + index / 2];
    const int32_t nibble = (packed >> ((index % 2) * NIBBLE_BITS)) & NIBBLE_MASK;
    // Mcv2Decoder's signed 4-bit value
    nodes[index] = nibble >= 8 ? nibble - 16 : nibble;
  }
  int32_t rows[GRID * ROOT_SIZE];
  horizontal(nodes, size, rows);
  const int32_t shift = shift_of(size);
  const VI scale = VI::set1(4 * size * size);
  int32_t luma[ROOT_SIZE];
  for (int32_t row_index = 0; row_index < size; row_index++) {
    vertical(rows, size, row_index, luma);
    const int32_t from = row_index * size * CHANNELS;
    for (int32_t column = 0; column < size; column += VI::N) {
      const VI residual = VI::load(luma + column).shl(quantizer);
      VI predicted_red, predicted_green, predicted_blue;
      VI::load3(prediction + from + column * CHANNELS, predicted_red, predicted_green, predicted_blue);
      VI::store3(out + from + column * CHANNELS, round(predicted_red * scale + residual, shift),
                 round(predicted_green * scale + residual, shift), round(predicted_blue * scale + residual, shift));
    }
    if (!row(measure, out, from, size)) {
      return -1;
    }
  }
  return measure.distortion;
}

void predict(const uint8_t *reference, int32_t width, int32_t height, int32_t block_left, int32_t block_top,
             int32_t size, int32_t motion_x, int32_t motion_y, int32_t *out) {
  const int32_t left = block_left + motion_x;
  const int32_t top = block_top + motion_y;
  const int32_t row_length = size * CHANNELS;
  if (left >= 0 && top >= 0 && left + size <= width && top + size <= height) {
    for (int32_t row_index = 0; row_index < size; row_index++) {
      const uint8_t *source = reference + ((int64_t)(top + row_index) * width + left) * CHANNELS;
      int32_t *target = out + row_index * row_length;
      for (int32_t offset = 0; offset < row_length; offset += VI::N) {
        VI::loadu8(source + offset).store(target + offset);
      }
    }
    return;
  }
  for (int32_t row_index = 0; row_index < size; row_index++) {
    const int32_t source_row = clamp32(top + row_index, 0, height - 1);
    for (int32_t column = 0; column < size; column++) {
      const int32_t source_column = clamp32(left + column, 0, width - 1);
      const uint8_t *source = reference + ((int64_t)source_row * width + source_column) * CHANNELS;
      int32_t *target = out + (row_index * size + column) * CHANNELS;
      for (int32_t channel = 0; channel < CHANNELS; channel++) {
        target[channel] = source[channel];
      }
    }
  }
}

// Keep Java's summation order: each lane accumulates one transposed row so its next value is contiguous.
void fit(const float *values, int32_t size, const float *matrix, float *out) {
  const int32_t grid = GRID;
  float plane[ROOT_SIZE * ROOT_SIZE];
  for (int32_t y = 0; y < size; y++) {
    for (int32_t x = 0; x < size; x++) {
      plane[x * size + y] = values[y * size + x];
    }
  }
  double scratch[ROOT_SIZE * GRID];
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
      out[i * grid + j] = (float)sum;
    }
  }
}

// whether count values between low and high sum in int lanes, and the lanes to their total, without overflowing, so
// they sum as Java's longs do
inline bool lanes_fit(int32_t low, int32_t high, int32_t count) {
  const int64_t largest = -(int64_t)low > (int64_t)high ? -(int64_t)low : (int64_t)high;
  return largest * ((int64_t)count + VI::N) <= INT32_MAX;
}

void cluster_pixels(const int32_t *source, int32_t size, int32_t step, int32_t *endpoints) {
  const int32_t first_red = endpoints[0];
  const int32_t first_green = endpoints[1];
  const int32_t first_blue = endpoints[2];
  const int32_t second_red = endpoints[3];
  const int32_t second_green = endpoints[4];
  const int32_t second_blue = endpoints[5];
  int64_t sums[8] = {0, 0, 0, 0, 0, 0, 0, 0};
  for (int32_t row_index = 0; row_index < size; row_index += step) {
    for (int32_t column = 0; column < size; column += step) {
      const int32_t at = (row_index * size + column) * CHANNELS;
      const int32_t red = source[at];
      const int32_t green = source[at + 1];
      const int32_t blue = source[at + 2];
      const int32_t first_distance =
          wrap_add(wrap_add(wrap_mul(wrap_sub(red, first_red), wrap_sub(red, first_red)),
                            wrap_mul(wrap_sub(green, first_green), wrap_sub(green, first_green))),
                   wrap_mul(wrap_sub(blue, first_blue), wrap_sub(blue, first_blue)));
      const int32_t second_distance =
          wrap_add(wrap_add(wrap_mul(wrap_sub(red, second_red), wrap_sub(red, second_red)),
                            wrap_mul(wrap_sub(green, second_green), wrap_sub(green, second_green))),
                   wrap_mul(wrap_sub(blue, second_blue), wrap_sub(blue, second_blue)));
      const int32_t endpoint = second_distance < first_distance ? 1 : 0;
      sums[endpoint * CHANNELS] += red;
      sums[endpoint * CHANNELS + 1] += green;
      sums[endpoint * CHANNELS + 2] += blue;
      sums[6 + endpoint]++;
    }
  }
  for (int32_t endpoint = 0; endpoint < 2; endpoint++) {
    const int64_t members = sums[6 + endpoint];
    if (members > 0) {
      for (int32_t channel = 0; channel < CHANNELS; channel++) {
        endpoints[endpoint * CHANNELS + channel] =
            (int32_t)((sums[endpoint * CHANNELS + channel] + members / 2) / members);
      }
    }
  }
}

template <int STEP> void cluster_lanes(const int32_t *source, int32_t size, int32_t *endpoints) {
  const int32_t pixels = (size / STEP) * (size / STEP);
  const VI first_red = VI::set1(endpoints[0]);
  const VI first_green = VI::set1(endpoints[1]);
  const VI first_blue = VI::set1(endpoints[2]);
  const VI second_red = VI::set1(endpoints[3]);
  const VI second_green = VI::set1(endpoints[4]);
  const VI second_blue = VI::set1(endpoints[5]);
  // the pixels nearer endpoint 1 in lanes, and all of them: endpoint 0 has the difference
  VI near_red = VI::zero();
  VI near_green = VI::zero();
  VI near_blue = VI::zero();
  VI near_count = VI::zero();
  VI all_red = VI::zero();
  VI all_green = VI::zero();
  VI all_blue = VI::zero();
  for (int32_t row_index = 0; row_index < size; row_index += STEP) {
    for (int32_t column = 0; column < size; column += STEP * VI::N) {
      VI red, green, blue;
      VI::load3(source + (row_index * size + column) * CHANNELS, red, green, blue);
      if (STEP == 2) {
        VI next_red, next_green, next_blue;
        VI::load3(source + (row_index * size + column + VI::N) * CHANNELS, next_red, next_green, next_blue);
        red = VI::evens(red, next_red);
        green = VI::evens(green, next_green);
        blue = VI::evens(blue, next_blue);
      }
      const VI first_distance = (red - first_red) * (red - first_red) + (green - first_green) * (green - first_green) +
                                (blue - first_blue) * (blue - first_blue);
      const VI second_distance = (red - second_red) * (red - second_red) +
                                 (green - second_green) * (green - second_green) +
                                 (blue - second_blue) * (blue - second_blue);
      const VI nearer = VI::less(second_distance, first_distance);
      near_red = near_red + (red & nearer);
      near_green = near_green + (green & nearer);
      near_blue = near_blue + (blue & nearer);
      near_count = near_count - nearer;
      all_red = all_red + red;
      all_green = all_green + green;
      all_blue = all_blue + blue;
    }
  }
  const int64_t counts[2] = {pixels - near_count.sum(), near_count.sum()};
  const int64_t near[CHANNELS] = {near_red.sum(), near_green.sum(), near_blue.sum()};
  const int64_t all[CHANNELS] = {all_red.sum(), all_green.sum(), all_blue.sum()};
  for (int32_t endpoint = 0; endpoint < 2; endpoint++) {
    const int64_t members = counts[endpoint];
    if (members > 0) {
      for (int32_t channel = 0; channel < CHANNELS; channel++) {
        const int64_t sum = endpoint == 1 ? near[channel] : all[channel] - near[channel];
        endpoints[endpoint * CHANNELS + channel] = (int32_t)((sum + members / 2) / members);
      }
    }
  }
}

// MCV2.cluster's starting endpoints, the sampled pixels of least and greatest luma (the first of each in raster
// order), N at a time: each lane keeps its own and the offset of its first, then the lanes are compared; returns the
// values or'ed together, which bound them all where none is negative
template <int STEP> int32_t cluster_extrema(const int32_t *source, int32_t size, int32_t *endpoints) {
  VI low_lanes = VI::set1(INT32_MAX);
  VI high_lanes = VI::set1(INT32_MIN);
  VI low_at = VI::zero();
  VI high_at = VI::zero();
  VI ored = VI::zero();
  const VI across = VI::iota() * VI::set1(STEP * CHANNELS);
  for (int32_t row_index = 0; row_index < size; row_index += STEP) {
    for (int32_t column = 0; column < size; column += STEP * VI::N) {
      VI red, green, blue;
      VI::load3(source + (row_index * size + column) * CHANNELS, red, green, blue);
      if (STEP == 2) {
        VI next_red, next_green, next_blue;
        VI::load3(source + (row_index * size + column + VI::N) * CHANNELS, next_red, next_green, next_blue);
        red = VI::evens(red, next_red);
        green = VI::evens(green, next_green);
        blue = VI::evens(blue, next_blue);
      }
      ored = ored | red | green | blue;
      const VI luma = red + green + green + blue;
      const VI at = VI::set1((row_index * size + column) * CHANNELS) + across;
      const VI lower = VI::less(luma, low_lanes);
      low_lanes = low_lanes + ((luma - low_lanes) & lower);
      low_at = low_at + ((at - low_at) & lower);
      const VI higher = VI::less(high_lanes, luma);
      high_lanes = high_lanes + ((luma - high_lanes) & higher);
      high_at = high_at + ((at - high_at) & higher);
    }
  }
  int32_t lows[VI::N];
  int32_t lows_at[VI::N];
  int32_t highs[VI::N];
  int32_t highs_at[VI::N];
  int32_t values[VI::N];
  low_lanes.store(lows);
  low_at.store(lows_at);
  high_lanes.store(highs);
  high_at.store(highs_at);
  ored.store(values);
  int32_t low = 0;
  int32_t high = 0;
  int32_t low_luma = INT32_MAX;
  int32_t high_luma = INT32_MIN;
  int32_t all = 0;
  for (int32_t lane = 0; lane < VI::N; lane++) {
    if (lows[lane] < low_luma || (lows[lane] == low_luma && lows_at[lane] < low)) {
      low_luma = lows[lane];
      low = lows_at[lane];
    }
    if (highs[lane] > high_luma || (highs[lane] == high_luma && highs_at[lane] < high)) {
      high_luma = highs[lane];
      high = highs_at[lane];
    }
    all |= values[lane];
  }
  for (int32_t channel = 0; channel < CHANNELS; channel++) {
    endpoints[channel] = source[low + channel];
    endpoints[CHANNELS + channel] = source[high + channel];
  }
  return all;
}

void cluster(const int32_t *source, int32_t size, int32_t *endpoints) {
  const int32_t step = size >= 16 ? 2 : 1;
  const int32_t all =
      step == 2 ? cluster_extrema<2>(source, size, endpoints) : cluster_extrema<1>(source, size, endpoints);
  if (all >= 0 && lanes_fit(0, all, (size / step) * (size / step))) {
    for (int32_t iteration = 0; iteration < 2; iteration++) {
      if (step == 2) {
        cluster_lanes<2>(source, size, endpoints);
      } else {
        cluster_lanes<1>(source, size, endpoints);
      }
    }
  } else {
    // values whose sums could overflow an int lane
    cluster_pixels(source, size, step, endpoints);
    cluster_pixels(source, size, step, endpoints);
  }
}

void assign(const int32_t *source, int32_t count, const int32_t *colors, int8_t *selectors) {
  const VI r0 = VI::set1(colors[0]);
  const VI g0 = VI::set1(colors[1]);
  const VI b0 = VI::set1(colors[2]);
  const VI r1 = VI::set1(colors[3]);
  const VI g1 = VI::set1(colors[4]);
  const VI b1 = VI::set1(colors[5]);
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
    (VI::less(e1, e0) & VI::set1(1)).store_bytes(selectors + i);
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

// MCV2.SAMPLES: the sampled rows and columns of a block, min(k size / 4 + size / 8, size - 1)
inline int32_t sample(int32_t size, int32_t k) { return min32((k * size) / 4 + size / 8, size - 1); }

constexpr int32_t DIRECTIONS[4][2] = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

inline int32_t pack(int32_t x, int32_t y) { return shl(x, 16) | (y & 0xFFFF); }
inline int32_t unpack_x(int32_t vector) { return vector >> 16; }
inline int32_t unpack_y(int32_t vector) { return (int16_t)vector; }

int64_t inside(const uint8_t *reference, int32_t width, const int32_t *source_samples, int32_t block_left,
               int32_t block_top, const int32_t *samples, int32_t motion_x, int32_t motion_y) {
  int64_t sum = 0;
  for (int32_t sample_row = 0; sample_row < 4; sample_row++) {
    const uint8_t *line =
        reference + ((int64_t)(block_top + samples[sample_row] + motion_y) * width + block_left + motion_x) * CHANNELS;
    const int32_t *source_row = source_samples + sample_row * 4 * CHANNELS;
    for (int32_t sample_column = 0; sample_column < 4; sample_column++) {
      const uint8_t *reference_pixel = line + samples[sample_column] * CHANNELS;
      for (int32_t channel = 0; channel < CHANNELS; channel++) {
        // Math.abs of an int, which leaves Integer.MIN_VALUE negative
        const int32_t difference = wrap_sub(reference_pixel[channel], source_row[sample_column * CHANNELS + channel]);
        sum += difference < 0 ? wrap_sub(0, difference) : difference;
      }
    }
  }
  return sum;
}

int64_t inside_bytes(const uint8_t *reference, int32_t width, const uint8_t *bytes, int32_t block_left,
                     int32_t block_top, const int32_t *samples, int32_t motion_x, int32_t motion_y) {
  int32_t sum = 0;
  for (int32_t sample_row = 0; sample_row < 4; sample_row++) {
    const uint8_t *line =
        reference + ((int64_t)(block_top + samples[sample_row] + motion_y) * width + block_left + motion_x) * CHANNELS;
    sum += sad4(pixel_word(line + samples[0] * CHANNELS), pixel_word(line + samples[1] * CHANNELS),
                pixel_word(line + samples[2] * CHANNELS), pixel_word(line + samples[3] * CHANNELS),
                bytes + 16 * sample_row);
  }
  return sum;
}

int64_t cost(const uint8_t *reference, int32_t width, int32_t height, const int32_t *source_samples,
             const uint8_t *bytes, int32_t block_left, int32_t block_top, const int32_t *samples, int32_t motion_x,
             int32_t motion_y) {
  const int32_t left = block_left + samples[0] + motion_x;
  const int32_t right = block_left + samples[3] + motion_x;
  const int32_t top = block_top + samples[0] + motion_y;
  const int32_t bottom = block_top + samples[3] + motion_y;
  if (left >= 0 && top >= 0 && right < width && bottom < height) {
    return bytes != nullptr
               ? inside_bytes(reference, width, bytes, block_left, block_top, samples, motion_x, motion_y)
               : inside(reference, width, source_samples, block_left, block_top, samples, motion_x, motion_y);
  }
  int64_t sum = 0;
  for (int32_t sample_row = 0; sample_row < 4; sample_row++) {
    const int32_t row_at = clamp32(block_top + samples[sample_row] + motion_y, 0, height - 1);
    for (int32_t sample_column = 0; sample_column < 4; sample_column++) {
      const int32_t column = clamp32(block_left + samples[sample_column] + motion_x, 0, width - 1);
      const uint8_t *reference_pixel = reference + ((int64_t)row_at * width + column) * CHANNELS;
      const int32_t *source_row = source_samples + (sample_row * 4 + sample_column) * CHANNELS;
      for (int32_t channel = 0; channel < CHANNELS; channel++) {
        const int32_t difference = wrap_sub(reference_pixel[channel], source_row[channel]);
        sum += difference < 0 ? wrap_sub(0, difference) : difference;
      }
    }
  }
  return sum;
}

// The vectors a search has measured: any of them costs at least the best found since, so measuring one again can
// never replace the best, and a search that skips them decides exactly as MCV2.seededMotion does
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

// MCV2.seededMotion: the best of no motion and the seeds, then one-pixel steps to the best of four neighbours while one
// is better; a vector replaces the best only when strictly better
int32_t seeded(const uint8_t *reference, int32_t width, int32_t height, const int32_t *source, int32_t block_left,
               int32_t block_top, int32_t size, int32_t range, const int32_t *seeds, int32_t seed_count) {
  const int32_t samples[4] = {sample(size, 0), sample(size, 1), sample(size, 2), sample(size, 3)};
  int32_t source_samples[16 * CHANNELS];
  uint8_t sampled[4 * 16] = {0};
  bool bytes = true;
  for (int32_t sample_row = 0; sample_row < 4; sample_row++) {
    for (int32_t sample_column = 0; sample_column < 4; sample_column++) {
      for (int32_t channel = 0; channel < CHANNELS; channel++) {
        const int32_t value = source[(samples[sample_row] * size + samples[sample_column]) * CHANNELS + channel];
        source_samples[(sample_row * 4 + sample_column) * CHANNELS + channel] = value;
        bytes &= (uint32_t)value <= 255;
        sampled[16 * sample_row + 4 * sample_column + channel] = (uint8_t)value;
      }
    }
  }
  const uint8_t *packed = bytes ? sampled : nullptr;
  Measured measured;
  int32_t best_x = 0;
  int32_t best_y = 0;
  int64_t best = cost(reference, width, height, source_samples, packed, block_left, block_top, samples, best_x, best_y);
  measured.add(best_x, best_y);
  for (int32_t seed_index = 0; seed_index < seed_count; seed_index++) {
    const int32_t seed_x = clamp32(unpack_x(seeds[seed_index]), -range, range);
    const int32_t seed_y = clamp32(unpack_y(seeds[seed_index]), -range, range);
    if (!measured.contains(seed_x, seed_y)) {
      measured.add(seed_x, seed_y);
      const int64_t error =
          cost(reference, width, height, source_samples, packed, block_left, block_top, samples, seed_x, seed_y);
      if (error < best) {
        best = error;
        best_x = seed_x;
        best_y = seed_y;
      }
    }
  }
  for (int32_t steps = 0; steps < 2 * range; steps++) {
    const int32_t center_x = best_x;
    const int32_t center_y = best_y;
    for (int32_t direction = 0; direction < 4; direction++) {
      const int32_t candidate_x = clamp32(center_x + DIRECTIONS[direction][0], -range, range);
      const int32_t candidate_y = clamp32(center_y + DIRECTIONS[direction][1], -range, range);
      if (measured.contains(candidate_x, candidate_y)) {
        continue;
      }
      measured.add(candidate_x, candidate_y);
      const int64_t error = cost(reference, width, height, source_samples, packed, block_left, block_top, samples,
                                 candidate_x, candidate_y);
      if (error < best) {
        best = error;
        best_x = candidate_x;
        best_y = candidate_y;
      }
    }
    if (best_x == center_x && best_y == center_y) {
      break;
    }
  }
  return pack(best_x, best_y);
}

// MCV2.JavaKernels.loadSource of a superblock: the block's channels, the picture's last row and column repeated past
// its edges
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

// the pairs of rows summed, then the even pixels and the odd ones, whose wrapping sums are Java's in any order: two
// vectors of a row pair at a time
template <class V> void halve_lanes(const int32_t *block, int32_t size, int32_t *out) {
  const int32_t half = size / 2;
  const int32_t row_length = size * CHANNELS;
  const V two = V::set1(2);
  for (int32_t py = 0; py < half; py++) {
    const int32_t *top = block + 2 * py * row_length;
    const int32_t *bottom = top + row_length;
    int32_t *line = out + py * half * CHANNELS;
    for (int32_t x = 0; x < size; x += 2 * V::N) {
      V r0, g0, b0, r1, g1, b1, r2, g2, b2, r3, g3, b3;
      V::load3(top + x * CHANNELS, r0, g0, b0);
      V::load3(top + (x + V::N) * CHANNELS, r1, g1, b1);
      V::load3(bottom + x * CHANNELS, r2, g2, b2);
      V::load3(bottom + (x + V::N) * CHANNELS, r3, g3, b3);
      const V ra = r0 + r2;
      const V rb = r1 + r3;
      const V ga = g0 + g2;
      const V gb = g1 + g3;
      const V ba = b0 + b2;
      const V bb = b1 + b3;
      const V r = V::evens(ra, rb) + V::odds(ra, rb) + two;
      const V g = V::evens(ga, gb) + V::odds(ga, gb) + two;
      const V b = V::evens(ba, bb) + V::odds(ba, bb) + two;
      V::store3(line + (x / 2) * CHANNELS, r.sar(2), g.sar(2), b.sar(2));
    }
  }
}

void halve(const int32_t *block, int32_t size, int32_t *out) {
  // one lane where the lanes' shuffles cost more than they save, or a row has fewer than two vectors
  if (SHUFFLED_LOAD3 || size < 2 * VI::N) {
    halve_lanes<VI1>(block, size, out);
  } else {
    halve_lanes<VI>(block, size, out);
  }
}

void residual_target(const int32_t *source, const int32_t *prediction, int32_t count, float *target) {
  const VF quarter = VF::set1(0.25f);
  const VF two = VF::set1(2.0f);
  int32_t pixel = 0;
  for (; pixel + VI::N <= count; pixel += VI::N) {
    VI source_red, source_green, source_blue;
    VI::load3(source + pixel * CHANNELS, source_red, source_green, source_blue);
    const VF luma = VF::from(source_red + source_green.shl(1) + source_blue) * quarter;
    VI predicted_red, predicted_green, predicted_blue;
    VI::load3(prediction + pixel * CHANNELS, predicted_red, predicted_green, predicted_blue);
    const VF red = VF::from(predicted_red);
    const VF green = VF::from(predicted_green);
    const VF blue = VF::from(predicted_blue);
    (luma - ((red + two * green) + blue) * quarter).store(target + pixel);
  }
  for (; pixel < count; pixel++) {
    const int32_t at = pixel * CHANNELS;
    const float luma = (float)(source[at] + 2 * source[at + 1] + source[at + 2]) * 0.25f;
    const float red = (float)prediction[at];
    const float green = (float)prediction[at + 1];
    const float blue = (float)prediction[at + 2];
    target[pixel] = luma - (red + 2 * green + blue) * 0.25f;
  }
}

} // namespace
} // namespace mcv2

// The exported kernels of one level, after its translation unit defined MCV2_PREFIX(name). The
// declarations come from the MCV2_KERNELS list above, so a definition that strays from it does not compile.
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
MCV2_EXPORT void MCV2_FIT_TO(fit)(const float *values, int32_t size, const float *matrix, float *out);
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

int64_t MCV2_PREFIX(palette)(const int8_t *record, int32_t size, int32_t *out, const int32_t *source, double rate,
                             double limit) {
  MCV2_HAND_OFF(palette, record, size, out, source, rate, limit)
  return mcv2::palette(record, size, out, {source, rate, limit, 0});
}

int64_t MCV2_PREFIX(compact)(const int32_t *prediction, const int8_t *record, int32_t q, int32_t size, int32_t *out,
                             const int32_t *source, double rate, double limit) {
  MCV2_HAND_OFF(compact, prediction, record, q, size, out, source, rate, limit)
  return mcv2::compact(prediction, record, q, size, out, {source, rate, limit, 0});
}

void MCV2_PREFIX(predict)(const uint8_t *reference, int32_t width, int32_t height, int32_t x, int32_t y, int32_t size,
                          int32_t mx, int32_t my, int32_t *out) {
  MCV2_HAND_OFF(predict, reference, width, height, x, y, size, mx, my, out)
  mcv2::predict(reference, width, height, x, y, size, mx, my, out);
}

void MCV2_PREFIX(fit)(const float *values, int32_t size, const float *matrix, float *out) {
  MCV2_HAND_OFF(fit, values, size, matrix, out)
#if defined(MCV2_FIT_TO)
  if (size == MCV2_FIT_AT) {
    return MCV2_FIT_TO(fit)(values, size, matrix, out);
  }
#endif
  mcv2::fit(values, size, matrix, out);
}

void MCV2_PREFIX(cluster)(const int32_t *source, int32_t size, int32_t *endpoints) {
  // every other pixel of a block of 16 or more, two vectors of a row at a time
  MCV2_HAND_OFF_BELOW(2 * MCV2_NARROW_BELOW, cluster, source, size, endpoints)
  mcv2::cluster(source, size, endpoints);
}

void MCV2_PREFIX(assign)(const int32_t *source, int32_t count, const int32_t *colors, int8_t *selectors) {
  mcv2::assign(source, count, colors, selectors);
}

int32_t MCV2_PREFIX(assign_pattern)(const int32_t *source, int32_t size, const int32_t *colors, int8_t *selectors) {
  MCV2_HAND_OFF(assign_pattern, source, size, colors, selectors)
  return mcv2::assign_pattern(source, size, colors, selectors);
}

int32_t MCV2_PREFIX(seeded)(const uint8_t *reference, int32_t width, int32_t height, const int32_t *source, int32_t x,
                            int32_t y, int32_t size, int32_t range, const int32_t *seeds, int32_t seed_count) {
  MCV2_HAND_OFF(seeded, reference, width, height, source, x, y, size, range, seeds, seed_count)
  return mcv2::seeded(reference, width, height, source, x, y, size, range, seeds, seed_count);
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

void MCV2_PREFIX(residual_target)(const int32_t *source, const int32_t *prediction, int32_t count, float *target) {
  mcv2::residual_target(source, prediction, count, target);
}
}

#if defined(MCV2_CPU)
// Which levels this CPU runs. Compiled without any extended instruction set, so it runs on every CPU of its
// architecture. An x86-64 level needs its instructions (cpuid) and, for AVX2 and AVX-512, the operating system saving
// their registers (xgetbv); AVX-512 runs only with the Ice Lake feature set, never on the CPUs that slow down at 512
// bits. On AArch64 NEON is always there, and SVE where the Linux kernel says so in AT_HWCAP, which Java passes in: the
// vector length then chooses the fixed-length level, and at 128 bits NEON stays.
#if defined(__x86_64__) || defined(_M_X64)
#include <cpuid.h>

namespace {

constexpr unsigned SSE41_BIT = 1u << 19;
constexpr unsigned OSXSAVE_BIT = 1u << 27;
constexpr unsigned AVX_BIT = 1u << 28;
constexpr unsigned AVX2_BIT = 1u << 5;
// XCR0: the XMM and YMM state
constexpr unsigned long long YMM_STATE = 0x6;
#if !defined(__APPLE__)
// leaf 7: AVX-512 F, DQ, BW and VL in EBX; VBMI, VBMI2, VNNI and BITALG in ECX
constexpr unsigned AVX512_EBX = (1u << 16) | (1u << 17) | (1u << 30) | (1u << 31);
constexpr unsigned AVX512_ECX = (1u << 1) | (1u << 6) | (1u << 11) | (1u << 12);
// XCR0: with the XMM and YMM state, the opmask and both halves of the ZMM state
constexpr unsigned long long ZMM_STATE = 0xE6;
#endif

unsigned long long xcr0() {
  unsigned low;
  unsigned high;
  __asm__ volatile("xgetbv" : "=a"(low), "=d"(high) : "c"(0));
  return ((unsigned long long)high << 32) | low;
}

} // namespace

extern "C" int32_t mcv2_cpu_levels(int64_t) {
  // SSE2 is part of x86-64
  int32_t levels = MCV2_LEVEL_SCALAR | MCV2_LEVEL_SSE2;
  unsigned eax;
  unsigned ebx;
  unsigned ecx;
  unsigned edx;
  if (!__get_cpuid(1, &eax, &ebx, &ecx, &edx)) {
    return levels;
  }
  if (ecx & SSE41_BIT) {
    levels |= MCV2_LEVEL_SSE41;
  }
  const unsigned long long state = (ecx & OSXSAVE_BIT) && (ecx & AVX_BIT) ? xcr0() : 0;
  if ((state & YMM_STATE) != YMM_STATE || !__get_cpuid_count(7, 0, &eax, &ebx, &ecx, &edx)) {
    return levels;
  }
  if (ebx & AVX2_BIT) {
    levels |= MCV2_LEVEL_AVX2;
  }
#if !defined(__APPLE__)
  // macOS saves the ZMM state only once a program has used it, so XCR0 cannot tell: macOS stays on AVX2
  if ((state & ZMM_STATE) == ZMM_STATE && (ebx & AVX512_EBX) == AVX512_EBX && (ecx & AVX512_ECX) == AVX512_ECX) {
    levels |= MCV2_LEVEL_AVX512;
  }
#endif
  return levels;
}

#elif defined(__aarch64__) || defined(_M_ARM64)

extern "C" int32_t mcv2_cpu_levels(int64_t hwcap) {
  int32_t levels = MCV2_LEVEL_SCALAR | MCV2_LEVEL_NEON;
#if defined(__linux__)
  if (hwcap & MCV2_HWCAP_SVE) {
    // the vector length in bytes: an SVE instruction, run only where the kernel said SVE is there
    unsigned long long bytes;
    __asm__ volatile(".arch_extension sve\n\trdvl %0, #1" : "=r"(bytes));
    if (bytes == 32) {
      levels |= MCV2_LEVEL_SVE256;
    } else if (bytes == 64) {
      levels |= MCV2_LEVEL_SVE512;
    }
  }
#else
  (void)hwcap;
#endif
  return levels;
}

#else

extern "C" int32_t mcv2_cpu_levels(int64_t) { return MCV2_LEVEL_SCALAR; }

#endif

extern "C" int32_t mcv2_abi(void) { return MCV2_ABI; }
#endif
#endif
