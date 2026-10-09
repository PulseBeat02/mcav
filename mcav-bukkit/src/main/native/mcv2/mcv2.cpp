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

// Floating-point contraction changes Java's rounding; compile with -ffp-contract=off.

#include <stdint.h>
#include <string.h>

#if defined(_WIN32)
#define MCV2_EXPORT __declspec(dllexport)
#else
#define MCV2_EXPORT __attribute__((visibility("default")))
#endif

#define MCV2_INTERFACE_VERSION 5

#define MCV2_LEVEL_SCALAR 1
#define MCV2_LEVEL_SSE41 2
#define MCV2_LEVEL_AVX2 4
#define MCV2_LEVEL_NEON 8
#define MCV2_LEVEL_SSE2 16
#define MCV2_LEVEL_AVX512 32
#define MCV2_LEVEL_SVE256 64
#define MCV2_LEVEL_SVE512 128

#define MCV2_HWCAP_SVE (1LL << 22)

#define MCV2_KERNELS(X)                                                                                                \
  X(int64_t, predicted,                                                                                                \
    (const int32_t *prediction, int32_t size, int32_t *output, const int32_t *source, double rate, double limit))      \
  X(int64_t, solid, (int32_t color, int32_t size, int32_t * output, const int32_t *source, double rate, double limit)) \
  X(int64_t, palette,                                                                                                  \
    (const int8_t *record, int32_t size, int32_t *output, const int32_t *source, double rate, double limit))           \
  X(int64_t, compact,                                                                                                  \
    (const int32_t *prediction, const int8_t *record, int32_t quantizer, int32_t size, int32_t *output,                \
     const int32_t *source, double rate, double limit))                                                                \
  X(void, predict,                                                                                                     \
    (const uint8_t *reference, int32_t width, int32_t height, int32_t horizontal_position, int32_t vertical_position,  \
     int32_t size, int32_t horizontal_motion, int32_t vertical_motion, int32_t *output))                               \
  X(void, fit, (const float *values, int32_t size, const float *matrix, float *output))                                \
  X(void, cluster, (const int32_t *source, int32_t size, int32_t *endpoints))                                          \
  X(void, assign, (const int32_t *source, int32_t count, const int32_t *colors, int8_t *selectors))                    \
  X(int32_t, assign_pattern, (const int32_t *source, int32_t size, const int32_t *colors, int8_t *selectors))          \
  X(int32_t, seeded,                                                                                                   \
    (const uint8_t *reference, int32_t width, int32_t height, const int32_t *source, int32_t horizontal_position,      \
     int32_t vertical_position, int32_t size, int32_t range, const int32_t *seeds, int32_t seed_count))                \
  X(void, load_source,                                                                                                 \
    (const uint8_t *image, int32_t width, int32_t height, int32_t horizontal_position, int32_t vertical_position,      \
     int32_t size, int32_t *source))                                                                                   \
  X(void, halve, (const int32_t *block, int32_t size, int32_t *output))                                                \
  X(void, residual_target, (const int32_t *source, const int32_t *prediction, int32_t count, float *target))

extern "C" {

// Java supplies AT_HWCAP because this library imports no C-library functions.

MCV2_EXPORT int32_t mcv2_cpu_levels(int64_t kernel_capabilities);

MCV2_EXPORT int32_t mcv2_abi(void);
}

#if !defined(MCV2_DECLARATIONS_ONLY)

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
// Internal linkage prevents the linker from substituting another SIMD level's weak helper symbol.

namespace {

// SSE load/store shuffles cost more than the arithmetic in the halving kernel.

#if defined(MCV2_SIMD_SSE2) || defined(MCV2_SIMD_SSE41)
constexpr bool SHUFFLED_CHANNEL_LOAD = true;
#else
constexpr bool SHUFFLED_CHANNEL_LOAD = false;
#endif

inline int32_t wrapping_addition(int32_t first, int32_t second) {
  return (int32_t)((uint32_t)first + (uint32_t)second);
}
inline int32_t wrapping_subtraction(int32_t first, int32_t second) {
  return (int32_t)((uint32_t)first - (uint32_t)second);
}
inline int32_t wrapping_multiplication(int32_t first, int32_t second) {
  return (int32_t)((uint32_t)first * (uint32_t)second);
}
inline int32_t wrapping_shift_left(int32_t first, int32_t shift_distance) {
  return (int32_t)((uint32_t)first << (shift_distance & 31));
}
inline int32_t wrapping_shift_right(int32_t first, int32_t shift_distance) { return first >> (shift_distance & 31); }

inline uint32_t pixel_word(const uint8_t *buffer) {
  return (uint32_t)buffer[0] | (uint32_t)buffer[1] << 8 | (uint32_t)buffer[2] << 16;
}

struct ScalarIntVector {
  static constexpr int LANES = 1;
  int32_t lanes;
  static ScalarIntVector load(const int32_t *buffer) { return {buffer[0]}; }
  void store(int32_t *buffer) const { buffer[0] = lanes; }

  void store_bytes(int8_t *buffer) const { buffer[0] = (int8_t)lanes; }
  static ScalarIntVector broadcast(int32_t value) { return {value}; }
  static ScalarIntVector zero() { return {0}; }
  static ScalarIntVector lane_indices() { return {0}; }
  static ScalarIntVector load_unsigned_bytes(const uint8_t *buffer) { return {buffer[0]}; }
  friend ScalarIntVector operator+(ScalarIntVector first, ScalarIntVector second) {
    return {wrapping_addition(first.lanes, second.lanes)};
  }
  friend ScalarIntVector operator-(ScalarIntVector first, ScalarIntVector second) {
    return {wrapping_subtraction(first.lanes, second.lanes)};
  }
  friend ScalarIntVector operator*(ScalarIntVector first, ScalarIntVector second) {
    return {wrapping_multiplication(first.lanes, second.lanes)};
  }
  friend ScalarIntVector operator&(ScalarIntVector first, ScalarIntVector second) {
    return {first.lanes & second.lanes};
  }
  friend ScalarIntVector operator|(ScalarIntVector first, ScalarIntVector second) {
    return {first.lanes | second.lanes};
  }
  ScalarIntVector shift_left(int32_t shift_distance) const { return {wrapping_shift_left(lanes, shift_distance)}; }
  ScalarIntVector shift_right(int32_t shift_distance) const { return {wrapping_shift_right(lanes, shift_distance)}; }
  static ScalarIntVector minimum(ScalarIntVector first, ScalarIntVector second) {
    return {first.lanes < second.lanes ? first.lanes : second.lanes};
  }
  static ScalarIntVector maximum(ScalarIntVector first, ScalarIntVector second) {
    return {first.lanes > second.lanes ? first.lanes : second.lanes};
  }
  static ScalarIntVector absolute(ScalarIntVector first) {
    return {first.lanes < 0 ? wrapping_subtraction(0, first.lanes) : first.lanes};
  }

  static ScalarIntVector less(ScalarIntVector first, ScalarIntVector second) {
    return {first.lanes < second.lanes ? -1 : 0};
  }
  int32_t sum() const { return lanes; }
  static void load_channels(const int32_t *buffer, ScalarIntVector &red_channels, ScalarIntVector &green_channels,
                            ScalarIntVector &blue_channels) {
    red_channels.lanes = buffer[0];
    green_channels.lanes = buffer[1];
    blue_channels.lanes = buffer[2];
  }
  static void store_channels(int32_t *buffer, ScalarIntVector red_channels, ScalarIntVector green_channels,
                             ScalarIntVector blue_channels) {
    buffer[0] = red_channels.lanes;
    buffer[1] = green_channels.lanes;
    buffer[2] = blue_channels.lanes;
  }

  static ScalarIntVector bits(const int8_t *buffer, int32_t index) {
    return {((buffer[index >> 3] >> (index & 7)) & 1) ? -1 : 0};
  }

  static ScalarIntVector evens(ScalarIntVector first, ScalarIntVector) { return first; }

  static ScalarIntVector odds(ScalarIntVector, ScalarIntVector second) { return second; }
};

#if defined(MCV2_SIMD_SCALAR)

using IntVector = ScalarIntVector;

inline int32_t four_pixel_difference(uint32_t first_pixel, uint32_t second_pixel, uint32_t third_pixel,
                                     uint32_t fourth_pixel, const uint8_t *sample_bytes) {
  const uint32_t words[4] = {first_pixel, second_pixel, third_pixel, fourth_pixel};
  int32_t sum = 0;
  for (int32_t lane_index = 0; lane_index < 16; lane_index++) {
    const int32_t difference =
        (int32_t)((words[lane_index / 4] >> (8 * (lane_index % 4))) & 0xFF) - sample_bytes[lane_index];
    sum += difference < 0 ? -difference : difference;
  }
  return sum;
}

struct FloatVector {
  float lanes;
  static FloatVector broadcast(float value) { return {value}; }
  static FloatVector from_integers(IntVector first) { return {(float)first.lanes}; }
  friend FloatVector operator+(FloatVector first, FloatVector second) { return {first.lanes + second.lanes}; }
  friend FloatVector operator-(FloatVector first, FloatVector second) { return {first.lanes - second.lanes}; }
  friend FloatVector operator*(FloatVector first, FloatVector second) { return {first.lanes * second.lanes}; }
  void store(float *buffer) const { buffer[0] = lanes; }
};

struct DoubleVector {
  static constexpr int LANES = 1;
  double lanes;
  static DoubleVector broadcast(double value) { return {value}; }
  static DoubleVector load_floats(const float *buffer) { return {(double)buffer[0]}; }
  friend DoubleVector operator+(DoubleVector first, DoubleVector second) { return {first.lanes + second.lanes}; }
  friend DoubleVector operator*(DoubleVector first, DoubleVector second) { return {first.lanes * second.lanes}; }
  void store(double *buffer) const { buffer[0] = lanes; }
};

#elif defined(MCV2_SIMD_SSE41) || defined(MCV2_SIMD_SSE2)

#if defined(MCV2_SIMD_SSE41)
template <int BLEND_MASK> inline __m128i blend_integer_lanes(__m128i first, __m128i second) {
  return _mm_castps_si128(_mm_blend_ps(_mm_castsi128_ps(first), _mm_castsi128_ps(second), BLEND_MASK));
}
inline __m128i multiply_low_int(__m128i first, __m128i second) { return _mm_mullo_epi32(first, second); }
inline __m128i minimum_int(__m128i first, __m128i second) { return _mm_min_epi32(first, second); }
inline __m128i maximum_int(__m128i first, __m128i second) { return _mm_max_epi32(first, second); }
inline __m128i absolute_int(__m128i first) { return _mm_abs_epi32(first); }
inline __m128i widen_unsigned_bytes(__m128i bytes) { return _mm_cvtepu8_epi32(bytes); }
#else

template <int BLEND_MASK> inline __m128i blend_integer_lanes(__m128i first, __m128i second) {
  const __m128i mask = _mm_setr_epi32((BLEND_MASK & 1) ? -1 : 0, (BLEND_MASK & 2) ? -1 : 0, (BLEND_MASK & 4) ? -1 : 0,
                                      (BLEND_MASK & 8) ? -1 : 0);
  return _mm_or_si128(_mm_and_si128(mask, second), _mm_andnot_si128(mask, first));
}
// Signed and unsigned products share the low 32 bits required by Java's wrapping arithmetic.
inline __m128i multiply_low_int(__m128i first, __m128i second) {
  const __m128i even = _mm_mul_epu32(first, second);
  const __m128i odd = _mm_mul_epu32(_mm_srli_epi64(first, 32), _mm_srli_epi64(second, 32));
  return _mm_unpacklo_epi32(_mm_shuffle_epi32(even, _MM_SHUFFLE(0, 0, 2, 0)),
                            _mm_shuffle_epi32(odd, _MM_SHUFFLE(0, 0, 2, 0)));
}
inline __m128i minimum_int(__m128i first, __m128i second) {
  const __m128i less = _mm_cmplt_epi32(first, second);
  return _mm_or_si128(_mm_and_si128(less, first), _mm_andnot_si128(less, second));
}
inline __m128i maximum_int(__m128i first, __m128i second) {
  const __m128i greater = _mm_cmpgt_epi32(first, second);
  return _mm_or_si128(_mm_and_si128(greater, first), _mm_andnot_si128(greater, second));
}
// Java Math.abs leaves Integer.MIN_VALUE negative.
inline __m128i absolute_int(__m128i first) {
  const __m128i sign = _mm_srai_epi32(first, 31);
  return _mm_sub_epi32(_mm_xor_si128(first, sign), sign);
}
inline __m128i widen_unsigned_bytes(__m128i bytes) {
  const __m128i zero = _mm_setzero_si128();
  return _mm_unpacklo_epi16(_mm_unpacklo_epi8(bytes, zero), zero);
}
#endif

inline int32_t four_pixel_difference(uint32_t first_pixel, uint32_t second_pixel, uint32_t third_pixel,
                                     uint32_t fourth_pixel, const uint8_t *sample_bytes) {
  const __m128i absolute_differences = _mm_sad_epu8(
      _mm_setr_epi32((int32_t)first_pixel, (int32_t)second_pixel, (int32_t)third_pixel, (int32_t)fourth_pixel),
      _mm_loadu_si128((const __m128i *)sample_bytes));
  return _mm_cvtsi128_si32(absolute_differences) + _mm_cvtsi128_si32(_mm_srli_si128(absolute_differences, 8));
}

struct IntVector {
  static constexpr int LANES = 4;
  __m128i lanes;
  static IntVector load(const int32_t *buffer) { return {_mm_loadu_si128((const __m128i *)buffer)}; }
  void store(int32_t *buffer) const { _mm_storeu_si128((__m128i *)buffer, lanes); }
  void store_bytes(int8_t *buffer) const {
    const int32_t word = _mm_cvtsi128_si32(_mm_packs_epi16(_mm_packs_epi32(lanes, lanes), _mm_setzero_si128()));
    memcpy(buffer, &word, sizeof(word));
  }
  static IntVector broadcast(int32_t value) { return {_mm_set1_epi32(value)}; }
  static IntVector zero() { return {_mm_setzero_si128()}; }
  static IntVector lane_indices() { return {_mm_setr_epi32(0, 1, 2, 3)}; }
  static IntVector load_unsigned_bytes(const uint8_t *buffer) {
    int32_t word;
    memcpy(&word, buffer, sizeof(word));
    return {widen_unsigned_bytes(_mm_cvtsi32_si128(word))};
  }
  friend IntVector operator+(IntVector first, IntVector second) { return {_mm_add_epi32(first.lanes, second.lanes)}; }
  friend IntVector operator-(IntVector first, IntVector second) { return {_mm_sub_epi32(first.lanes, second.lanes)}; }
  friend IntVector operator*(IntVector first, IntVector second) {
    return {multiply_low_int(first.lanes, second.lanes)};
  }
  friend IntVector operator&(IntVector first, IntVector second) { return {_mm_and_si128(first.lanes, second.lanes)}; }
  friend IntVector operator|(IntVector first, IntVector second) { return {_mm_or_si128(first.lanes, second.lanes)}; }
  IntVector shift_left(int32_t shift_distance) const {
    return {_mm_sll_epi32(lanes, _mm_cvtsi32_si128(shift_distance & 31))};
  }
  IntVector shift_right(int32_t shift_distance) const {
    return {_mm_sra_epi32(lanes, _mm_cvtsi32_si128(shift_distance & 31))};
  }
  static IntVector minimum(IntVector first, IntVector second) { return {minimum_int(first.lanes, second.lanes)}; }
  static IntVector maximum(IntVector first, IntVector second) { return {maximum_int(first.lanes, second.lanes)}; }
  static IntVector absolute(IntVector first) { return {absolute_int(first.lanes)}; }
  static IntVector less(IntVector first, IntVector second) { return {_mm_cmplt_epi32(first.lanes, second.lanes)}; }
  int32_t sum() const {
    __m128i partial_sum = _mm_add_epi32(lanes, _mm_shuffle_epi32(lanes, _MM_SHUFFLE(1, 0, 3, 2)));
    partial_sum = _mm_add_epi32(partial_sum, _mm_shuffle_epi32(partial_sum, _MM_SHUFFLE(2, 3, 0, 1)));
    return _mm_cvtsi128_si32(partial_sum);
  }

  static void load_channels(const int32_t *buffer, IntVector &red_channels, IntVector &green_channels,
                            IntVector &blue_channels) {
    const __m128i first_vector = _mm_loadu_si128((const __m128i *)buffer);
    const __m128i second_vector = _mm_loadu_si128((const __m128i *)(buffer + 4));
    const __m128i third_vector = _mm_loadu_si128((const __m128i *)(buffer + 8));

    const __m128i shuffled_red =
        blend_integer_lanes<0x2>(blend_integer_lanes<0x4>(first_vector, second_vector), third_vector);

    const __m128i shuffled_green =
        blend_integer_lanes<0x4>(blend_integer_lanes<0x2>(second_vector, first_vector), third_vector);

    const __m128i shuffled_blue =
        blend_integer_lanes<0x4>(blend_integer_lanes<0x2>(third_vector, second_vector), first_vector);
    red_channels.lanes = _mm_shuffle_epi32(shuffled_red, _MM_SHUFFLE(1, 2, 3, 0));
    green_channels.lanes = _mm_shuffle_epi32(shuffled_green, _MM_SHUFFLE(2, 3, 0, 1));
    blue_channels.lanes = _mm_shuffle_epi32(shuffled_blue, _MM_SHUFFLE(3, 0, 1, 2));
  }
  static void store_channels(int32_t *buffer, IntVector red_channels, IntVector green_channels,
                             IntVector blue_channels) {

    const __m128i first_vector = blend_integer_lanes<0x4>(
        blend_integer_lanes<0x2>(_mm_shuffle_epi32(red_channels.lanes, _MM_SHUFFLE(1, 0, 0, 0)),
                                 _mm_shuffle_epi32(green_channels.lanes, _MM_SHUFFLE(0, 0, 0, 0))),
        _mm_shuffle_epi32(blue_channels.lanes, _MM_SHUFFLE(0, 0, 0, 0)));

    const __m128i second_vector = blend_integer_lanes<0x4>(
        blend_integer_lanes<0x2>(_mm_shuffle_epi32(green_channels.lanes, _MM_SHUFFLE(2, 0, 0, 1)),
                                 _mm_shuffle_epi32(blue_channels.lanes, _MM_SHUFFLE(1, 1, 1, 1))),
        _mm_shuffle_epi32(red_channels.lanes, _MM_SHUFFLE(2, 2, 2, 2)));

    const __m128i third_vector = blend_integer_lanes<0x4>(
        blend_integer_lanes<0x2>(_mm_shuffle_epi32(blue_channels.lanes, _MM_SHUFFLE(3, 0, 0, 2)),
                                 _mm_shuffle_epi32(red_channels.lanes, _MM_SHUFFLE(3, 3, 3, 3))),
        _mm_shuffle_epi32(green_channels.lanes, _MM_SHUFFLE(3, 3, 3, 3)));
    _mm_storeu_si128((__m128i *)buffer, first_vector);
    _mm_storeu_si128((__m128i *)(buffer + 4), second_vector);
    _mm_storeu_si128((__m128i *)(buffer + 8), third_vector);
  }
  static IntVector bits(const int8_t *buffer, int32_t index) {
    const __m128i byte = _mm_set1_epi32((buffer[index >> 3] >> (index & 7)) & 0xF);
    const __m128i lanes = _mm_setr_epi32(1, 2, 4, 8);
    return {_mm_cmpeq_epi32(_mm_and_si128(byte, lanes), lanes)};
  }
  static IntVector evens(IntVector first, IntVector second) {
    return {_mm_castps_si128(
        _mm_shuffle_ps(_mm_castsi128_ps(first.lanes), _mm_castsi128_ps(second.lanes), _MM_SHUFFLE(2, 0, 2, 0)))};
  }
  static IntVector odds(IntVector first, IntVector second) {
    return {_mm_castps_si128(
        _mm_shuffle_ps(_mm_castsi128_ps(first.lanes), _mm_castsi128_ps(second.lanes), _MM_SHUFFLE(3, 1, 3, 1)))};
  }
};

struct FloatVector {
  __m128 lanes;
  static FloatVector broadcast(float value) { return {_mm_set1_ps(value)}; }
  static FloatVector from_integers(IntVector first) { return {_mm_cvtepi32_ps(first.lanes)}; }
  friend FloatVector operator+(FloatVector first, FloatVector second) {
    return {_mm_add_ps(first.lanes, second.lanes)};
  }
  friend FloatVector operator-(FloatVector first, FloatVector second) {
    return {_mm_sub_ps(first.lanes, second.lanes)};
  }
  friend FloatVector operator*(FloatVector first, FloatVector second) {
    return {_mm_mul_ps(first.lanes, second.lanes)};
  }
  void store(float *buffer) const { _mm_storeu_ps(buffer, lanes); }
};

struct DoubleVector {
  static constexpr int LANES = 2;
  __m128d lanes;
  static DoubleVector broadcast(double value) { return {_mm_set1_pd(value)}; }
  static DoubleVector load_floats(const float *buffer) {
    int64_t pair;
    memcpy(&pair, buffer, sizeof(pair));
    return {_mm_cvtps_pd(_mm_castsi128_ps(_mm_cvtsi64_si128(pair)))};
  }
  friend DoubleVector operator+(DoubleVector first, DoubleVector second) {
    return {_mm_add_pd(first.lanes, second.lanes)};
  }
  friend DoubleVector operator*(DoubleVector first, DoubleVector second) {
    return {_mm_mul_pd(first.lanes, second.lanes)};
  }
  void store(double *buffer) const { _mm_storeu_pd(buffer, lanes); }
};

#elif defined(MCV2_SIMD_AVX2)

inline int32_t four_pixel_difference(uint32_t first_pixel, uint32_t second_pixel, uint32_t third_pixel,
                                     uint32_t fourth_pixel, const uint8_t *sample_bytes) {
  const __m128i absolute_differences = _mm_sad_epu8(
      _mm_setr_epi32((int32_t)first_pixel, (int32_t)second_pixel, (int32_t)third_pixel, (int32_t)fourth_pixel),
      _mm_loadu_si128((const __m128i *)sample_bytes));
  return _mm_cvtsi128_si32(absolute_differences) + _mm_cvtsi128_si32(_mm_srli_si128(absolute_differences, 8));
}

struct IntVector {
  static constexpr int LANES = 8;
  __m256i lanes;
  static IntVector load(const int32_t *buffer) { return {_mm256_loadu_si256((const __m256i *)buffer)}; }
  void store_bytes(int8_t *buffer) const {
    const __m128i words = _mm_packs_epi32(_mm256_castsi256_si128(lanes), _mm256_extracti128_si256(lanes, 1));
    _mm_storel_epi64((__m128i *)buffer, _mm_packs_epi16(words, _mm_setzero_si128()));
  }
  void store(int32_t *buffer) const { _mm256_storeu_si256((__m256i *)buffer, lanes); }
  static IntVector broadcast(int32_t value) { return {_mm256_set1_epi32(value)}; }
  static IntVector zero() { return {_mm256_setzero_si256()}; }
  static IntVector lane_indices() { return {_mm256_setr_epi32(0, 1, 2, 3, 4, 5, 6, 7)}; }
  static IntVector load_unsigned_bytes(const uint8_t *buffer) {
    return {_mm256_cvtepu8_epi32(_mm_loadl_epi64((const __m128i *)buffer))};
  }
  friend IntVector operator+(IntVector first, IntVector second) {
    return {_mm256_add_epi32(first.lanes, second.lanes)};
  }
  friend IntVector operator-(IntVector first, IntVector second) {
    return {_mm256_sub_epi32(first.lanes, second.lanes)};
  }
  friend IntVector operator*(IntVector first, IntVector second) {
    return {_mm256_mullo_epi32(first.lanes, second.lanes)};
  }
  friend IntVector operator&(IntVector first, IntVector second) {
    return {_mm256_and_si256(first.lanes, second.lanes)};
  }
  friend IntVector operator|(IntVector first, IntVector second) { return {_mm256_or_si256(first.lanes, second.lanes)}; }
  IntVector shift_left(int32_t shift_distance) const {
    return {_mm256_sll_epi32(lanes, _mm_cvtsi32_si128(shift_distance & 31))};
  }
  IntVector shift_right(int32_t shift_distance) const {
    return {_mm256_sra_epi32(lanes, _mm_cvtsi32_si128(shift_distance & 31))};
  }
  static IntVector minimum(IntVector first, IntVector second) { return {_mm256_min_epi32(first.lanes, second.lanes)}; }
  static IntVector maximum(IntVector first, IntVector second) { return {_mm256_max_epi32(first.lanes, second.lanes)}; }
  static IntVector absolute(IntVector first) { return {_mm256_abs_epi32(first.lanes)}; }
  static IntVector less(IntVector first, IntVector second) { return {_mm256_cmpgt_epi32(second.lanes, first.lanes)}; }
  int32_t sum() const {
    __m128i partial_sum = _mm_add_epi32(_mm256_castsi256_si128(lanes), _mm256_extracti128_si256(lanes, 1));
    partial_sum = _mm_add_epi32(partial_sum, _mm_shuffle_epi32(partial_sum, _MM_SHUFFLE(1, 0, 3, 2)));
    partial_sum = _mm_add_epi32(partial_sum, _mm_shuffle_epi32(partial_sum, _MM_SHUFFLE(2, 3, 0, 1)));
    return _mm_cvtsi128_si32(partial_sum);
  }

  static void load_channels(const int32_t *buffer, IntVector &red_channels, IntVector &green_channels,
                            IntVector &blue_channels) {
    const __m256i first_vector = _mm256_loadu_si256((const __m256i *)buffer);
    const __m256i second_vector = _mm256_loadu_si256((const __m256i *)(buffer + 8));
    const __m256i third_vector = _mm256_loadu_si256((const __m256i *)(buffer + 16));

    const __m256i shuffled_red =
        _mm256_blend_epi32(_mm256_blend_epi32(first_vector, second_vector, 0x92), third_vector, 0x24);

    const __m256i shuffled_green =
        _mm256_blend_epi32(_mm256_blend_epi32(first_vector, second_vector, 0x24), third_vector, 0x49);

    const __m256i shuffled_blue =
        _mm256_blend_epi32(_mm256_blend_epi32(first_vector, second_vector, 0x49), third_vector, 0x92);
    red_channels.lanes = _mm256_permutevar8x32_epi32(shuffled_red, _mm256_setr_epi32(0, 3, 6, 1, 4, 7, 2, 5));
    green_channels.lanes = _mm256_permutevar8x32_epi32(shuffled_green, _mm256_setr_epi32(1, 4, 7, 2, 5, 0, 3, 6));
    blue_channels.lanes = _mm256_permutevar8x32_epi32(shuffled_blue, _mm256_setr_epi32(2, 5, 0, 3, 6, 1, 4, 7));
  }
  static void store_channels(int32_t *buffer, IntVector red_channels, IntVector green_channels,
                             IntVector blue_channels) {
    const __m256i shuffled_red =
        _mm256_permutevar8x32_epi32(red_channels.lanes, _mm256_setr_epi32(0, 3, 6, 1, 4, 7, 2, 5));
    const __m256i shuffled_green =
        _mm256_permutevar8x32_epi32(green_channels.lanes, _mm256_setr_epi32(5, 0, 3, 6, 1, 4, 7, 2));
    const __m256i shuffled_blue =
        _mm256_permutevar8x32_epi32(blue_channels.lanes, _mm256_setr_epi32(2, 5, 0, 3, 6, 1, 4, 7));
    _mm256_storeu_si256((__m256i *)buffer, _mm256_blend_epi32(_mm256_blend_epi32(shuffled_red, shuffled_green, 0x92),
                                                              shuffled_blue, 0x24));
    _mm256_storeu_si256(
        (__m256i *)(buffer + 8),
        _mm256_blend_epi32(_mm256_blend_epi32(shuffled_blue, shuffled_red, 0x92), shuffled_green, 0x24));
    _mm256_storeu_si256(
        (__m256i *)(buffer + 16),
        _mm256_blend_epi32(_mm256_blend_epi32(shuffled_green, shuffled_blue, 0x92), shuffled_red, 0x24));
  }
  static IntVector bits(const int8_t *buffer, int32_t index) {
    const __m256i byte = _mm256_set1_epi32(buffer[index >> 3] & 0xFF);
    const __m256i lanes = _mm256_setr_epi32(1, 2, 4, 8, 16, 32, 64, 128);
    return {_mm256_cmpeq_epi32(_mm256_and_si256(byte, lanes), lanes)};
  }

  static IntVector evens(IntVector first, IntVector second) {
    const __m256 pairs =
        _mm256_shuffle_ps(_mm256_castsi256_ps(first.lanes), _mm256_castsi256_ps(second.lanes), _MM_SHUFFLE(2, 0, 2, 0));
    return {_mm256_permute4x64_epi64(_mm256_castps_si256(pairs), _MM_SHUFFLE(3, 1, 2, 0))};
  }
  static IntVector odds(IntVector first, IntVector second) {
    const __m256 pairs =
        _mm256_shuffle_ps(_mm256_castsi256_ps(first.lanes), _mm256_castsi256_ps(second.lanes), _MM_SHUFFLE(3, 1, 3, 1));
    return {_mm256_permute4x64_epi64(_mm256_castps_si256(pairs), _MM_SHUFFLE(3, 1, 2, 0))};
  }
};

struct FloatVector {
  __m256 lanes;
  static FloatVector broadcast(float value) { return {_mm256_set1_ps(value)}; }
  static FloatVector from_integers(IntVector first) { return {_mm256_cvtepi32_ps(first.lanes)}; }
  friend FloatVector operator+(FloatVector first, FloatVector second) {
    return {_mm256_add_ps(first.lanes, second.lanes)};
  }
  friend FloatVector operator-(FloatVector first, FloatVector second) {
    return {_mm256_sub_ps(first.lanes, second.lanes)};
  }
  friend FloatVector operator*(FloatVector first, FloatVector second) {
    return {_mm256_mul_ps(first.lanes, second.lanes)};
  }
  void store(float *buffer) const { _mm256_storeu_ps(buffer, lanes); }
};

struct DoubleVector {
  static constexpr int LANES = 4;
  __m256d lanes;
  static DoubleVector broadcast(double value) { return {_mm256_set1_pd(value)}; }
  static DoubleVector load_floats(const float *buffer) { return {_mm256_cvtps_pd(_mm_loadu_ps(buffer))}; }
  friend DoubleVector operator+(DoubleVector first, DoubleVector second) {
    return {_mm256_add_pd(first.lanes, second.lanes)};
  }
  friend DoubleVector operator*(DoubleVector first, DoubleVector second) {
    return {_mm256_mul_pd(first.lanes, second.lanes)};
  }
  void store(double *buffer) const { _mm256_storeu_pd(buffer, lanes); }
};

#elif defined(MCV2_SIMD_AVX512)

inline int32_t four_pixel_difference(uint32_t first_pixel, uint32_t second_pixel, uint32_t third_pixel,
                                     uint32_t fourth_pixel, const uint8_t *sample_bytes) {
  const __m128i absolute_differences = _mm_sad_epu8(
      _mm_setr_epi32((int32_t)first_pixel, (int32_t)second_pixel, (int32_t)third_pixel, (int32_t)fourth_pixel),
      _mm_loadu_si128((const __m128i *)sample_bytes));
  return _mm_cvtsi128_si32(absolute_differences) + _mm_cvtsi128_si32(_mm_srli_si128(absolute_differences, 8));
}

struct IntVector {
  static constexpr int LANES = 16;
  __m512i lanes;
  static IntVector load(const int32_t *buffer) { return {_mm512_loadu_si512(buffer)}; }
  void store_bytes(int8_t *buffer) const { _mm_storeu_si128((__m128i *)buffer, _mm512_cvtepi32_epi8(lanes)); }
  void store(int32_t *buffer) const { _mm512_storeu_si512(buffer, lanes); }
  static IntVector broadcast(int32_t value) { return {_mm512_set1_epi32(value)}; }
  static IntVector zero() { return {_mm512_setzero_si512()}; }
  static IntVector lane_indices() { return {_mm512_set_epi32(15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0)}; }
  static IntVector load_unsigned_bytes(const uint8_t *buffer) {
    return {_mm512_cvtepu8_epi32(_mm_loadu_si128((const __m128i *)buffer))};
  }
  friend IntVector operator+(IntVector first, IntVector second) {
    return {_mm512_add_epi32(first.lanes, second.lanes)};
  }
  friend IntVector operator-(IntVector first, IntVector second) {
    return {_mm512_sub_epi32(first.lanes, second.lanes)};
  }
  friend IntVector operator*(IntVector first, IntVector second) {
    return {_mm512_mullo_epi32(first.lanes, second.lanes)};
  }
  friend IntVector operator&(IntVector first, IntVector second) {
    return {_mm512_and_si512(first.lanes, second.lanes)};
  }
  friend IntVector operator|(IntVector first, IntVector second) { return {_mm512_or_si512(first.lanes, second.lanes)}; }
  IntVector shift_left(int32_t shift_distance) const {
    return {_mm512_sll_epi32(lanes, _mm_cvtsi32_si128(shift_distance & 31))};
  }
  IntVector shift_right(int32_t shift_distance) const {
    return {_mm512_sra_epi32(lanes, _mm_cvtsi32_si128(shift_distance & 31))};
  }
  static IntVector minimum(IntVector first, IntVector second) { return {_mm512_min_epi32(first.lanes, second.lanes)}; }
  static IntVector maximum(IntVector first, IntVector second) { return {_mm512_max_epi32(first.lanes, second.lanes)}; }
  static IntVector absolute(IntVector first) { return {_mm512_abs_epi32(first.lanes)}; }
  static IntVector less(IntVector first, IntVector second) {
    return {_mm512_movm_epi32(_mm512_cmplt_epi32_mask(first.lanes, second.lanes))};
  }
  int32_t sum() const { return _mm512_reduce_add_epi32(lanes); }

  static void load_channels(const int32_t *buffer, IntVector &red_channels, IntVector &green_channels,
                            IntVector &blue_channels) {
    const __m512i first_vector = _mm512_loadu_si512(buffer);
    const __m512i second_vector = _mm512_loadu_si512(buffer + 16);
    const __m512i third_vector = _mm512_loadu_si512(buffer + 32);
    red_channels.lanes = gather(first_vector, second_vector, third_vector, 0);
    green_channels.lanes = gather(first_vector, second_vector, third_vector, 1);
    blue_channels.lanes = gather(first_vector, second_vector, third_vector, 2);
  }
  static void store_channels(int32_t *buffer, IntVector red_channels, IntVector green_channels,
                             IntVector blue_channels) {
    _mm512_storeu_si512(buffer, scatter(red_channels.lanes, green_channels.lanes, blue_channels.lanes, 0));
    _mm512_storeu_si512(buffer + 16, scatter(red_channels.lanes, green_channels.lanes, blue_channels.lanes, 1));
    _mm512_storeu_si512(buffer + 32, scatter(red_channels.lanes, green_channels.lanes, blue_channels.lanes, 2));
  }

  static IntVector bits(const int8_t *buffer, int32_t index) {
    const int32_t array_offset = index >> 3;
    const uint32_t word = (uint32_t)(uint8_t)buffer[array_offset] | ((uint32_t)(uint8_t)buffer[array_offset + 1] << 8);
    return {_mm512_movm_epi32((__mmask16)word)};
  }
  static IntVector evens(IntVector first, IntVector second) {
    const __m512i index = _mm512_set_epi32(30, 28, 26, 24, 22, 20, 18, 16, 14, 12, 10, 8, 6, 4, 2, 0);
    return {_mm512_permutex2var_epi32(first.lanes, index, second.lanes)};
  }
  static IntVector odds(IntVector first, IntVector second) {
    const __m512i index = _mm512_set_epi32(31, 29, 27, 25, 23, 21, 19, 17, 15, 13, 11, 9, 7, 5, 3, 1);
    return {_mm512_permutex2var_epi32(first.lanes, index, second.lanes)};
  }

private:
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

  static __m512i gather(__m512i first_vector, __m512i second_vector, __m512i third_vector, int32_t channel) {
    const __m512i low =
        _mm512_permutex2var_epi32(first_vector, _mm512_load_si512(GATHER_FIRST[channel]), second_vector);
    return _mm512_permutex2var_epi32(low, _mm512_load_si512(GATHER_SECOND[channel]), third_vector);
  }
  static __m512i scatter(__m512i first, __m512i second, __m512i channel_index, int32_t element_index) {
    const __m512i absolute_difference =
        _mm512_permutex2var_epi32(first, _mm512_load_si512(SCATTER_FIRST[element_index]), second);
    return _mm512_permutex2var_epi32(absolute_difference, _mm512_load_si512(SCATTER_SECOND[element_index]),
                                     channel_index);
  }
};

struct FloatVector {
  __m512 lanes;
  static FloatVector broadcast(float value) { return {_mm512_set1_ps(value)}; }
  static FloatVector from_integers(IntVector first) { return {_mm512_cvtepi32_ps(first.lanes)}; }
  friend FloatVector operator+(FloatVector first, FloatVector second) {
    return {_mm512_add_ps(first.lanes, second.lanes)};
  }
  friend FloatVector operator-(FloatVector first, FloatVector second) {
    return {_mm512_sub_ps(first.lanes, second.lanes)};
  }
  friend FloatVector operator*(FloatVector first, FloatVector second) {
    return {_mm512_mul_ps(first.lanes, second.lanes)};
  }
  void store(float *buffer) const { _mm512_storeu_ps(buffer, lanes); }
};

struct DoubleVector {
  static constexpr int LANES = 8;
  __m512d lanes;
  static DoubleVector broadcast(double value) { return {_mm512_set1_pd(value)}; }
  static DoubleVector load_floats(const float *buffer) { return {_mm512_cvtps_pd(_mm256_loadu_ps(buffer))}; }
  friend DoubleVector operator+(DoubleVector first, DoubleVector second) {
    return {_mm512_add_pd(first.lanes, second.lanes)};
  }
  friend DoubleVector operator*(DoubleVector first, DoubleVector second) {
    return {_mm512_mul_pd(first.lanes, second.lanes)};
  }
  void store(double *buffer) const { _mm512_storeu_pd(buffer, lanes); }
};

#elif defined(MCV2_SIMD_NEON)

inline int32_t four_pixel_difference(uint32_t first_pixel, uint32_t second_pixel, uint32_t third_pixel,
                                     uint32_t fourth_pixel, const uint8_t *sample_bytes) {
  const uint32_t words[4] = {first_pixel, second_pixel, third_pixel, fourth_pixel};
  return (int32_t)vaddlvq_u8(vabdq_u8(vreinterpretq_u8_u32(vld1q_u32(words)), vld1q_u8(sample_bytes)));
}

struct IntVector {
  static constexpr int LANES = 4;
  int32x4_t lanes;
  static IntVector load(const int32_t *buffer) { return {vld1q_s32(buffer)}; }
  void store_bytes(int8_t *buffer) const {
    const int8x8_t bytes = vmovn_s16(vcombine_s16(vmovn_s32(lanes), vdup_n_s16(0)));
    vst1_lane_s32((int32_t *)buffer, vreinterpret_s32_s8(bytes), 0);
  }
  void store(int32_t *buffer) const { vst1q_s32(buffer, lanes); }
  static IntVector broadcast(int32_t value) { return {vdupq_n_s32(value)}; }
  static IntVector zero() { return {vdupq_n_s32(0)}; }
  static IntVector lane_indices() {
    static const int32_t lanes[4] = {0, 1, 2, 3};
    return {vld1q_s32(lanes)};
  }
  static IntVector load_unsigned_bytes(const uint8_t *buffer) {
    uint32_t word;
    memcpy(&word, buffer, sizeof(word));
    const uint8x8_t bytes = vreinterpret_u8_u32(vdup_n_u32(word));
    return {vreinterpretq_s32_u32(vmovl_u16(vget_low_u16(vmovl_u8(bytes))))};
  }
  friend IntVector operator+(IntVector first, IntVector second) { return {vaddq_s32(first.lanes, second.lanes)}; }
  friend IntVector operator-(IntVector first, IntVector second) { return {vsubq_s32(first.lanes, second.lanes)}; }
  friend IntVector operator*(IntVector first, IntVector second) { return {vmulq_s32(first.lanes, second.lanes)}; }
  friend IntVector operator&(IntVector first, IntVector second) { return {vandq_s32(first.lanes, second.lanes)}; }
  friend IntVector operator|(IntVector first, IntVector second) { return {vorrq_s32(first.lanes, second.lanes)}; }
  IntVector shift_left(int32_t shift_distance) const { return {vshlq_s32(lanes, vdupq_n_s32(shift_distance & 31))}; }
  IntVector shift_right(int32_t shift_distance) const {
    return {vshlq_s32(lanes, vdupq_n_s32(-(shift_distance & 31)))};
  }
  static IntVector minimum(IntVector first, IntVector second) { return {vminq_s32(first.lanes, second.lanes)}; }
  static IntVector maximum(IntVector first, IntVector second) { return {vmaxq_s32(first.lanes, second.lanes)}; }
  static IntVector absolute(IntVector first) { return {vabsq_s32(first.lanes)}; }
  static IntVector less(IntVector first, IntVector second) {
    return {vreinterpretq_s32_u32(vcltq_s32(first.lanes, second.lanes))};
  }
  int32_t sum() const { return vaddvq_s32(lanes); }
  static void load_channels(const int32_t *buffer, IntVector &red_channels, IntVector &green_channels,
                            IntVector &blue_channels) {
    const int32x4x3_t channel_vectors = vld3q_s32(buffer);
    red_channels.lanes = channel_vectors.val[0];
    green_channels.lanes = channel_vectors.val[1];
    blue_channels.lanes = channel_vectors.val[2];
  }
  static void store_channels(int32_t *buffer, IntVector red_channels, IntVector green_channels,
                             IntVector blue_channels) {
    int32x4x3_t channel_vectors;
    channel_vectors.val[0] = red_channels.lanes;
    channel_vectors.val[1] = green_channels.lanes;
    channel_vectors.val[2] = blue_channels.lanes;
    vst3q_s32(buffer, channel_vectors);
  }
  static IntVector bits(const int8_t *buffer, int32_t index) {
    static const int32_t lanes[4] = {1, 2, 4, 8};
    const int32x4_t byte = vdupq_n_s32((buffer[index >> 3] >> (index & 7)) & 0xF);
    return {vreinterpretq_s32_u32(vtstq_s32(byte, vld1q_s32(lanes)))};
  }
  static IntVector evens(IntVector first, IntVector second) { return {vuzp1q_s32(first.lanes, second.lanes)}; }
  static IntVector odds(IntVector first, IntVector second) { return {vuzp2q_s32(first.lanes, second.lanes)}; }
};

struct FloatVector {
  float32x4_t lanes;
  static FloatVector broadcast(float value) { return {vdupq_n_f32(value)}; }
  static FloatVector from_integers(IntVector first) { return {vcvtq_f32_s32(first.lanes)}; }
  friend FloatVector operator+(FloatVector first, FloatVector second) { return {vaddq_f32(first.lanes, second.lanes)}; }
  friend FloatVector operator-(FloatVector first, FloatVector second) { return {vsubq_f32(first.lanes, second.lanes)}; }
  friend FloatVector operator*(FloatVector first, FloatVector second) { return {vmulq_f32(first.lanes, second.lanes)}; }
  void store(float *buffer) const { vst1q_f32(buffer, lanes); }
};

struct DoubleVector {
  static constexpr int LANES = 2;
  float64x2_t lanes;
  static DoubleVector broadcast(double value) { return {vdupq_n_f64(value)}; }
  static DoubleVector load_floats(const float *buffer) { return {vcvt_f64_f32(vld1_f32(buffer))}; }
  friend DoubleVector operator+(DoubleVector first, DoubleVector second) {
    return {vaddq_f64(first.lanes, second.lanes)};
  }
  friend DoubleVector operator*(DoubleVector first, DoubleVector second) {
    return {vmulq_f64(first.lanes, second.lanes)};
  }
  void store(double *buffer) const { vst1q_f64(buffer, lanes); }
};

#elif defined(MCV2_SIMD_SVE256) || defined(MCV2_SIMD_SVE512)

// Fixed-length SVE code requires the exact vector length selected at dispatch.

#if defined(MCV2_SIMD_SVE256)
#define MCV2_SVE_BITS 256
#else
#define MCV2_SVE_BITS 512
#endif
typedef svint32_t SveIntLanes __attribute__((arm_sve_vector_bits(MCV2_SVE_BITS)));
typedef svuint64_t SveUnsignedLongLanes __attribute__((arm_sve_vector_bits(MCV2_SVE_BITS)));
typedef svfloat32_t SveFloatLanes __attribute__((arm_sve_vector_bits(MCV2_SVE_BITS)));
typedef svfloat64_t SveDoubleLanes __attribute__((arm_sve_vector_bits(MCV2_SVE_BITS)));

inline int32_t four_pixel_difference(uint32_t first_pixel, uint32_t second_pixel, uint32_t third_pixel,
                                     uint32_t fourth_pixel, const uint8_t *sample_bytes) {
  const uint32_t words[4] = {first_pixel, second_pixel, third_pixel, fourth_pixel};
  return (int32_t)vaddlvq_u8(vabdq_u8(vreinterpretq_u8_u32(vld1q_u32(words)), vld1q_u8(sample_bytes)));
}

struct IntVector {
  static constexpr int LANES = MCV2_SVE_BITS / 32;
  SveIntLanes lanes;
  static IntVector load(const int32_t *buffer) { return {svld1_s32(svptrue_b32(), buffer)}; }
  void store_bytes(int8_t *buffer) const { svst1b_s32(svptrue_b32(), buffer, lanes); }
  void store(int32_t *buffer) const { svst1_s32(svptrue_b32(), buffer, lanes); }
  static IntVector broadcast(int32_t value) { return {svdup_n_s32(value)}; }
  static IntVector zero() { return {svdup_n_s32(0)}; }
  static IntVector lane_indices() { return {svindex_s32(0, 1)}; }
  static IntVector load_unsigned_bytes(const uint8_t *buffer) {
    return {svreinterpret_s32_u32(svld1ub_u32(svptrue_b32(), buffer))};
  }
  friend IntVector operator+(IntVector first, IntVector second) {
    return {svadd_s32_x(svptrue_b32(), first.lanes, second.lanes)};
  }
  friend IntVector operator-(IntVector first, IntVector second) {
    return {svsub_s32_x(svptrue_b32(), first.lanes, second.lanes)};
  }
  friend IntVector operator*(IntVector first, IntVector second) {
    return {svmul_s32_x(svptrue_b32(), first.lanes, second.lanes)};
  }
  friend IntVector operator&(IntVector first, IntVector second) {
    return {svand_s32_x(svptrue_b32(), first.lanes, second.lanes)};
  }
  friend IntVector operator|(IntVector first, IntVector second) {
    return {svorr_s32_x(svptrue_b32(), first.lanes, second.lanes)};
  }
  IntVector shift_left(int32_t shift_distance) const {
    return {svlsl_n_s32_x(svptrue_b32(), lanes, (uint32_t)(shift_distance & 31))};
  }
  IntVector shift_right(int32_t shift_distance) const {
    return {svasr_n_s32_x(svptrue_b32(), lanes, (uint32_t)(shift_distance & 31))};
  }
  static IntVector minimum(IntVector first, IntVector second) {
    return {svmin_s32_x(svptrue_b32(), first.lanes, second.lanes)};
  }
  static IntVector maximum(IntVector first, IntVector second) {
    return {svmax_s32_x(svptrue_b32(), first.lanes, second.lanes)};
  }
  static IntVector absolute(IntVector first) { return {svabs_s32_x(svptrue_b32(), first.lanes)}; }
  static IntVector less(IntVector first, IntVector second) {
    return {svsel_s32(svcmplt_s32(svptrue_b32(), first.lanes, second.lanes), svdup_n_s32(-1), svdup_n_s32(0))};
  }

  int32_t sum() const { return (int32_t)(uint32_t)(uint64_t)svaddv_s32(svptrue_b32(), lanes); }
  static void load_channels(const int32_t *buffer, IntVector &red_channels, IntVector &green_channels,
                            IntVector &blue_channels) {
    const svint32x3_t channel_vectors = svld3_s32(svptrue_b32(), buffer);
    red_channels.lanes = svget3_s32(channel_vectors, 0);
    green_channels.lanes = svget3_s32(channel_vectors, 1);
    blue_channels.lanes = svget3_s32(channel_vectors, 2);
  }
  static void store_channels(int32_t *buffer, IntVector red_channels, IntVector green_channels,
                             IntVector blue_channels) {
    svst3_s32(svptrue_b32(), buffer, svcreate3_s32(red_channels.lanes, green_channels.lanes, blue_channels.lanes));
  }

  static IntVector bits(const int8_t *buffer, int32_t index) {
    const int32_t array_offset = index >> 3;
    uint32_t word = (uint8_t)buffer[array_offset];
    if (LANES > 8) {
      word |= (uint32_t)(uint8_t)buffer[array_offset + 1] << 8;
    }
    const svuint32_t lanes = svindex_u32(0, 1);
    const svuint32_t bit = svand_n_u32_x(svptrue_b32(), svlsr_u32_x(svptrue_b32(), svdup_n_u32(word), lanes), 1);
    return {svsel_s32(svcmpne_n_u32(svptrue_b32(), bit, 0), svdup_n_s32(-1), svdup_n_s32(0))};
  }
  static IntVector evens(IntVector first, IntVector second) { return {svuzp1_s32(first.lanes, second.lanes)}; }
  static IntVector odds(IntVector first, IntVector second) { return {svuzp2_s32(first.lanes, second.lanes)}; }
};

struct FloatVector {
  SveFloatLanes lanes;
  static FloatVector broadcast(float value) { return {svdup_n_f32(value)}; }
  static FloatVector from_integers(IntVector first) { return {svcvt_f32_s32_x(svptrue_b32(), first.lanes)}; }
  friend FloatVector operator+(FloatVector first, FloatVector second) {
    return {svadd_f32_x(svptrue_b32(), first.lanes, second.lanes)};
  }
  friend FloatVector operator-(FloatVector first, FloatVector second) {
    return {svsub_f32_x(svptrue_b32(), first.lanes, second.lanes)};
  }
  friend FloatVector operator*(FloatVector first, FloatVector second) {
    return {svmul_f32_x(svptrue_b32(), first.lanes, second.lanes)};
  }
  void store(float *buffer) const { svst1_f32(svptrue_b32(), buffer, lanes); }
};

struct DoubleVector {
  static constexpr int LANES = MCV2_SVE_BITS / 64;
  SveDoubleLanes lanes;
  static DoubleVector broadcast(double value) { return {svdup_n_f64(value)}; }

  static DoubleVector load_floats(const float *buffer) {
    const SveUnsignedLongLanes words = svld1uw_u64(svptrue_b64(), (const uint32_t *)buffer);
    return {svcvt_f64_f32_x(svptrue_b64(), svreinterpret_f32_u64(words))};
  }
  friend DoubleVector operator+(DoubleVector first, DoubleVector second) {
    return {svadd_f64_x(svptrue_b64(), first.lanes, second.lanes)};
  }
  friend DoubleVector operator*(DoubleVector first, DoubleVector second) {
    return {svmul_f64_x(svptrue_b64(), first.lanes, second.lanes)};
  }
  void store(double *buffer) const { svst1_f64(svptrue_b64(), buffer, lanes); }
};

#endif

}
}

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

inline int32_t logarithm_base_two(int32_t lanes) { return __builtin_ctz((unsigned)lanes); }
inline int32_t size_index(int32_t size) { return logarithm_base_two(size) - 3; }
inline int32_t minimum_int(int32_t first, int32_t second) { return first < second ? first : second; }
inline int32_t maximum_int(int32_t first, int32_t second) { return first > second ? first : second; }
inline int32_t clamp_int(int32_t lanes, int32_t low, int32_t high) {
  return minimum_int(maximum_int(lanes, low), high);
}

struct Axis {
  int32_t lower[ROOT_SIZE];
  int32_t upper[ROOT_SIZE];
  int32_t weight[ROOT_SIZE];
};

struct Axes {
  Axis axes[BLOCK_SIZES];
};

constexpr Axes make_axes() {
  Axes tables{};
  for (int32_t size_index = 0; size_index < BLOCK_SIZES; size_index++) {
    const int32_t size = 8 << size_index;
    const int32_t span = 2 * size;
    for (int32_t pixel_index = 0; pixel_index < size; pixel_index++) {
      int32_t position = (2 * pixel_index + 1) * GRID - size;
      position = position < 0 ? 0 : position;
      position = position > (GRID - 1) * span ? (GRID - 1) * span : position;
      const int32_t lower = position / span;
      tables.axes[size_index].lower[pixel_index] = lower;
      tables.axes[size_index].upper[pixel_index] = lower + 1 < GRID - 1 ? lower + 1 : GRID - 1;
      tables.axes[size_index].weight[pixel_index] = position - lower * span;
    }
  }
  return tables;
}

constexpr Axes AXES = make_axes();

inline int32_t shift_of(int32_t size) { return 2 * (logarithm_base_two(size) + 1); }

inline IntVector round(IntVector value, int32_t shift) {
  return IntVector::minimum(
      IntVector::maximum((value + IntVector::broadcast(1 << (shift - 1))).shift_right(shift), IntVector::zero()),
      IntVector::broadcast(MAX_CHANNEL));
}

struct Measure {
  const int32_t *source;
  double rate;
  double limit;
  int64_t distortion;
};

inline bool row(Measure &measure, const int32_t *output, int32_t source_position, int32_t pixels) {
  IntVector sum = IntVector::zero();
  for (int32_t pixel_index = 0; pixel_index < pixels; pixel_index += IntVector::LANES) {
    IntVector source_red, source_green, source_blue, reconstructed_red, reconstructed_green, reconstructed_blue;
    IntVector::load_channels(measure.source + source_position + pixel_index * CHANNELS, source_red, source_green,
                             source_blue);
    IntVector::load_channels(output + source_position + pixel_index * CHANNELS, reconstructed_red, reconstructed_green,
                             reconstructed_blue);
    const IntVector red_difference = source_red - reconstructed_red;
    const IntVector green_difference = source_green - reconstructed_green;
    const IntVector blue_difference = source_blue - reconstructed_blue;
    const IntVector luma = red_difference + green_difference.shift_left(1) + blue_difference;
    const IntVector red_blue_difference = red_difference - blue_difference;
    const IntVector green_difference_plane = green_difference.shift_left(1) - red_difference - blue_difference;
    sum = sum + (luma * luma + red_blue_difference * red_blue_difference).shift_left(2) +
          green_difference_plane * green_difference_plane;
  }
  measure.distortion += sum.sum();
  return (double)measure.distortion / DISTORTION_SCALE + measure.rate < measure.limit;
}

inline void horizontal(const int32_t *nodes, int32_t size, int32_t *rows) {
  const Axis &axis = AXES.axes[size_index(size)];
  const int32_t span = 2 * size;
  for (int32_t node_row = 0; node_row < GRID; node_row++) {
    const int32_t array_offset = node_row * size;
    for (int32_t pixel_column = 0; pixel_column < size; pixel_column++) {
      const int32_t weight = axis.weight[pixel_column];
      rows[array_offset + pixel_column] =
          wrapping_addition(wrapping_multiplication(nodes[node_row * GRID + axis.lower[pixel_column]], span - weight),
                            wrapping_multiplication(nodes[node_row * GRID + axis.upper[pixel_column]], weight));
    }
  }
}

inline void vertical(const int32_t *rows, int32_t size, int32_t pixel_row, int32_t *line) {
  const Axis &axis = AXES.axes[size_index(size)];
  const int32_t top = axis.lower[pixel_row] * size;
  const int32_t bottom = axis.upper[pixel_row] * size;
  const IntVector upper_weight = IntVector::broadcast(axis.weight[pixel_row]);
  const IntVector lower_weight = IntVector::broadcast(2 * size - axis.weight[pixel_row]);
  for (int32_t pixel_column = 0; pixel_column < size; pixel_column += IntVector::LANES) {
    (IntVector::load(rows + top + pixel_column) * lower_weight +
     IntVector::load(rows + bottom + pixel_column) * upper_weight)
        .store(line + pixel_column);
  }
}

int64_t predicted(const int32_t *prediction, int32_t size, int32_t *output, Measure measure) {
  const int32_t row_length = size * CHANNELS;
  for (int32_t row_index = 0; row_index < size; row_index++) {
    const int32_t source_position = row_index * row_length;
    for (int32_t offset = source_position; offset < source_position + row_length; offset += IntVector::LANES) {
      IntVector::load(prediction + offset).store(output + offset);
    }
    if (!row(measure, output, source_position, size)) {
      return -1;
    }
  }
  return measure.distortion;
}

int64_t solid(int32_t color, int32_t size, int32_t *output, Measure measure) {
  const IntVector red_values = IntVector::broadcast((color >> 16) & 0xFF);
  const IntVector green_values = IntVector::broadcast((color >> 8) & 0xFF);
  const IntVector second = IntVector::broadcast(color & 0xFF);
  for (int32_t vertical_position = 0; vertical_position < size; vertical_position++) {
    const int32_t source_position = vertical_position * size * CHANNELS;
    for (int32_t horizontal_position = 0; horizontal_position < size; horizontal_position += IntVector::LANES) {
      IntVector::store_channels(output + source_position + horizontal_position * CHANNELS, red_values, green_values,
                                second);
    }
    if (!row(measure, output, source_position, size)) {
      return -1;
    }
  }
  return measure.distortion;
}

int64_t palette(const int8_t *record, int32_t size, int32_t *output, Measure measure) {
  const IntVector first_red = IntVector::broadcast((uint8_t)record[0]);
  const IntVector first_green = IntVector::broadcast((uint8_t)record[1]);
  const IntVector first_blue = IntVector::broadcast((uint8_t)record[2]);
  const IntVector red_delta = IntVector::broadcast((uint8_t)record[3] - (uint8_t)record[0]);
  const IntVector green_delta = IntVector::broadcast((uint8_t)record[4] - (uint8_t)record[1]);
  const IntVector blue_delta = IntVector::broadcast((uint8_t)record[5] - (uint8_t)record[2]);
  for (int32_t row_index = 0; row_index < size; row_index++) {
    for (int32_t column = 0; column < size; column += IntVector::LANES) {
      const int32_t pixel = row_index * size + column;
      const IntVector bit = IntVector::bits(record + SELECTORS_AT, pixel);
      IntVector::store_channels(output + pixel * CHANNELS, first_red + (red_delta & bit),
                                first_green + (green_delta & bit), first_blue + (blue_delta & bit));
    }
    if (!row(measure, output, row_index * size * CHANNELS, size)) {
      return -1;
    }
  }
  return measure.distortion;
}

int64_t compact(const int32_t *prediction, const int8_t *record, int32_t quantizer, int32_t size, int32_t *output,
                Measure measure) {
  int32_t nodes[GRID * GRID];
  for (int32_t index = 0; index < GRID * GRID; index++) {
    const int32_t packed = (uint8_t)record[2 + index / 2];
    const int32_t nibble = (packed >> ((index % 2) * NIBBLE_BITS)) & NIBBLE_MASK;

    nodes[index] = nibble >= 8 ? nibble - 16 : nibble;
  }
  int32_t rows[GRID * ROOT_SIZE];
  horizontal(nodes, size, rows);
  const int32_t shift = shift_of(size);
  const IntVector scale = IntVector::broadcast(4 * size * size);
  int32_t luma[ROOT_SIZE];
  for (int32_t row_index = 0; row_index < size; row_index++) {
    vertical(rows, size, row_index, luma);
    const int32_t source_position = row_index * size * CHANNELS;
    for (int32_t column = 0; column < size; column += IntVector::LANES) {
      const IntVector residual = IntVector::load(luma + column).shift_left(quantizer);
      IntVector predicted_red, predicted_green, predicted_blue;
      IntVector::load_channels(prediction + source_position + column * CHANNELS, predicted_red, predicted_green,
                               predicted_blue);
      IntVector::store_channels(
          output + source_position + column * CHANNELS, round(predicted_red * scale + residual, shift),
          round(predicted_green * scale + residual, shift), round(predicted_blue * scale + residual, shift));
    }
    if (!row(measure, output, source_position, size)) {
      return -1;
    }
  }
  return measure.distortion;
}

void predict(const uint8_t *reference, int32_t width, int32_t height, int32_t block_left, int32_t block_top,
             int32_t size, int32_t motion_x, int32_t motion_y, int32_t *output) {
  const int32_t left = block_left + motion_x;
  const int32_t top = block_top + motion_y;
  const int32_t row_length = size * CHANNELS;
  if (left >= 0 && top >= 0 && left + size <= width && top + size <= height) {
    for (int32_t row_index = 0; row_index < size; row_index++) {
      const uint8_t *source = reference + ((int64_t)(top + row_index) * width + left) * CHANNELS;
      int32_t *target = output + row_index * row_length;
      for (int32_t offset = 0; offset < row_length; offset += IntVector::LANES) {
        IntVector::load_unsigned_bytes(source + offset).store(target + offset);
      }
    }
    return;
  }
  for (int32_t row_index = 0; row_index < size; row_index++) {
    const int32_t source_row = clamp_int(top + row_index, 0, height - 1);
    for (int32_t column = 0; column < size; column++) {
      const int32_t source_column = clamp_int(left + column, 0, width - 1);
      const uint8_t *source = reference + ((int64_t)source_row * width + source_column) * CHANNELS;
      int32_t *target = output + (row_index * size + column) * CHANNELS;
      for (int32_t channel = 0; channel < CHANNELS; channel++) {
        target[channel] = source[channel];
      }
    }
  }
}

// Each lane must retain Java's summation order for bit-exact fits.
void fit(const float *values, int32_t size, const float *matrix, float *output) {
  const int32_t grid = GRID;
  float plane[ROOT_SIZE * ROOT_SIZE];
  for (int32_t vertical_position = 0; vertical_position < size; vertical_position++) {
    for (int32_t horizontal_position = 0; horizontal_position < size; horizontal_position++) {
      plane[horizontal_position * size + vertical_position] = values[vertical_position * size + horizontal_position];
    }
  }
  double scratch[ROOT_SIZE * GRID];
  double lanes[4 * DoubleVector::LANES];
  for (int32_t element_index = 0; element_index < grid; element_index++) {
    const float *weights = matrix + element_index * size;
    int32_t first_source_row = 0;

    for (; first_source_row + 4 * DoubleVector::LANES <= size; first_source_row += 4 * DoubleVector::LANES) {
      DoubleVector first_sum = DoubleVector::broadcast(0.0);
      DoubleVector second_sum = first_sum;
      DoubleVector third_sum = first_sum;
      DoubleVector fourth_sum = first_sum;
      for (int32_t horizontal_position = 0; horizontal_position < size; horizontal_position++) {
        const DoubleVector weight = DoubleVector::broadcast((double)weights[horizontal_position]);
        const float *column = plane + horizontal_position * size + first_source_row;
        first_sum = first_sum + DoubleVector::load_floats(column) * weight;
        second_sum = second_sum + DoubleVector::load_floats(column + DoubleVector::LANES) * weight;
        third_sum = third_sum + DoubleVector::load_floats(column + 2 * DoubleVector::LANES) * weight;
        fourth_sum = fourth_sum + DoubleVector::load_floats(column + 3 * DoubleVector::LANES) * weight;
      }
      first_sum.store(lanes);
      second_sum.store(lanes + DoubleVector::LANES);
      third_sum.store(lanes + 2 * DoubleVector::LANES);
      fourth_sum.store(lanes + 3 * DoubleVector::LANES);
      for (int32_t lane_index = 0; lane_index < 4 * DoubleVector::LANES; lane_index++) {
        scratch[(first_source_row + lane_index) * grid + element_index] = lanes[lane_index];
      }
    }
    for (; first_source_row < size; first_source_row += DoubleVector::LANES) {
      DoubleVector sum = DoubleVector::broadcast(0.0);
      for (int32_t horizontal_position = 0; horizontal_position < size; horizontal_position++) {
        sum = sum + DoubleVector::load_floats(plane + horizontal_position * size + first_source_row) *
                        DoubleVector::broadcast((double)weights[horizontal_position]);
      }
      sum.store(lanes);
      for (int32_t lane_index = 0; lane_index < DoubleVector::LANES; lane_index++) {
        scratch[(first_source_row + lane_index) * grid + element_index] = lanes[lane_index];
      }
    }
  }
  for (int32_t index = 0; index < grid; index++) {
    for (int32_t element_index = 0; element_index < grid; element_index++) {
      double sum = 0;
      for (int32_t vertical_position = 0; vertical_position < size; vertical_position++) {
        sum += (double)matrix[index * size + vertical_position] * scratch[vertical_position * grid + element_index];
      }
      output[index * grid + element_index] = (float)sum;
    }
  }
}

inline bool lanes_fit(int32_t low, int32_t high, int32_t count) {
  const int64_t largest = -(int64_t)low > (int64_t)high ? -(int64_t)low : (int64_t)high;
  return largest * ((int64_t)count + IntVector::LANES) <= INT32_MAX;
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
      const int32_t array_offset = (row_index * size + column) * CHANNELS;
      const int32_t red = source[array_offset];
      const int32_t green = source[array_offset + 1];
      const int32_t blue = source[array_offset + 2];
      const int32_t first_distance = wrapping_addition(
          wrapping_addition(
              wrapping_multiplication(wrapping_subtraction(red, first_red), wrapping_subtraction(red, first_red)),
              wrapping_multiplication(wrapping_subtraction(green, first_green),
                                      wrapping_subtraction(green, first_green))),
          wrapping_multiplication(wrapping_subtraction(blue, first_blue), wrapping_subtraction(blue, first_blue)));
      const int32_t second_distance = wrapping_addition(
          wrapping_addition(
              wrapping_multiplication(wrapping_subtraction(red, second_red), wrapping_subtraction(red, second_red)),
              wrapping_multiplication(wrapping_subtraction(green, second_green),
                                      wrapping_subtraction(green, second_green))),
          wrapping_multiplication(wrapping_subtraction(blue, second_blue), wrapping_subtraction(blue, second_blue)));
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
  const IntVector first_red = IntVector::broadcast(endpoints[0]);
  const IntVector first_green = IntVector::broadcast(endpoints[1]);
  const IntVector first_blue = IntVector::broadcast(endpoints[2]);
  const IntVector second_red = IntVector::broadcast(endpoints[3]);
  const IntVector second_green = IntVector::broadcast(endpoints[4]);
  const IntVector second_blue = IntVector::broadcast(endpoints[5]);

  IntVector near_red = IntVector::zero();
  IntVector near_green = IntVector::zero();
  IntVector near_blue = IntVector::zero();
  IntVector near_count = IntVector::zero();
  IntVector all_red = IntVector::zero();
  IntVector all_green = IntVector::zero();
  IntVector all_blue = IntVector::zero();
  for (int32_t row_index = 0; row_index < size; row_index += STEP) {
    for (int32_t column = 0; column < size; column += STEP * IntVector::LANES) {
      IntVector red, green, blue;
      IntVector::load_channels(source + (row_index * size + column) * CHANNELS, red, green, blue);
      if (STEP == 2) {
        IntVector next_red, next_green, next_blue;
        IntVector::load_channels(source + (row_index * size + column + IntVector::LANES) * CHANNELS, next_red,
                                 next_green, next_blue);
        red = IntVector::evens(red, next_red);
        green = IntVector::evens(green, next_green);
        blue = IntVector::evens(blue, next_blue);
      }
      const IntVector first_distance = (red - first_red) * (red - first_red) +
                                       (green - first_green) * (green - first_green) +
                                       (blue - first_blue) * (blue - first_blue);
      const IntVector second_distance = (red - second_red) * (red - second_red) +
                                        (green - second_green) * (green - second_green) +
                                        (blue - second_blue) * (blue - second_blue);
      const IntVector nearer = IntVector::less(second_distance, first_distance);
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

template <int STEP> int32_t cluster_extrema(const int32_t *source, int32_t size, int32_t *endpoints) {
  IntVector low_lanes = IntVector::broadcast(INT32_MAX);
  IntVector high_lanes = IntVector::broadcast(INT32_MIN);
  IntVector low_at = IntVector::zero();
  IntVector high_at = IntVector::zero();
  IntVector combined_bits = IntVector::zero();
  const IntVector across = IntVector::lane_indices() * IntVector::broadcast(STEP * CHANNELS);
  for (int32_t row_index = 0; row_index < size; row_index += STEP) {
    for (int32_t column = 0; column < size; column += STEP * IntVector::LANES) {
      IntVector red, green, blue;
      IntVector::load_channels(source + (row_index * size + column) * CHANNELS, red, green, blue);
      if (STEP == 2) {
        IntVector next_red, next_green, next_blue;
        IntVector::load_channels(source + (row_index * size + column + IntVector::LANES) * CHANNELS, next_red,
                                 next_green, next_blue);
        red = IntVector::evens(red, next_red);
        green = IntVector::evens(green, next_green);
        blue = IntVector::evens(blue, next_blue);
      }
      combined_bits = combined_bits | red | green | blue;
      const IntVector luma = red + green + green + blue;
      const IntVector array_offset = IntVector::broadcast((row_index * size + column) * CHANNELS) + across;
      const IntVector lower = IntVector::less(luma, low_lanes);
      low_lanes = low_lanes + ((luma - low_lanes) & lower);
      low_at = low_at + ((array_offset - low_at) & lower);
      const IntVector higher = IntVector::less(high_lanes, luma);
      high_lanes = high_lanes + ((luma - high_lanes) & higher);
      high_at = high_at + ((array_offset - high_at) & higher);
    }
  }
  int32_t lows[IntVector::LANES];
  int32_t lows_at[IntVector::LANES];
  int32_t highs[IntVector::LANES];
  int32_t highs_at[IntVector::LANES];
  int32_t values[IntVector::LANES];
  low_lanes.store(lows);
  low_at.store(lows_at);
  high_lanes.store(highs);
  high_at.store(highs_at);
  combined_bits.store(values);
  int32_t low = 0;
  int32_t high = 0;
  int32_t low_luma = INT32_MAX;
  int32_t high_luma = INT32_MIN;
  int32_t all = 0;
  for (int32_t lane = 0; lane < IntVector::LANES; lane++) {
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

    cluster_pixels(source, size, step, endpoints);
    cluster_pixels(source, size, step, endpoints);
  }
}

void assign(const int32_t *source, int32_t count, const int32_t *colors, int8_t *selectors) {
  const IntVector first_red = IntVector::broadcast(colors[0]);
  const IntVector first_green = IntVector::broadcast(colors[1]);
  const IntVector first_blue = IntVector::broadcast(colors[2]);
  const IntVector second_red = IntVector::broadcast(colors[3]);
  const IntVector second_green = IntVector::broadcast(colors[4]);
  const IntVector second_blue = IntVector::broadcast(colors[5]);
  int32_t index = 0;
  for (; index + IntVector::LANES <= count; index += IntVector::LANES) {
    IntVector red_values, green_values, second;
    IntVector::load_channels(source + index * CHANNELS, red_values, green_values, second);
    const IntVector first_red_difference = red_values - first_red;
    const IntVector first_green_difference = green_values - first_green;
    const IntVector first_blue_difference = second - first_blue;
    const IntVector second_red_difference = red_values - second_red;
    const IntVector second_green_difference = green_values - second_green;
    const IntVector second_blue_difference = second - second_blue;
    const IntVector first_distance = first_red_difference * first_red_difference +
                                     first_green_difference * first_green_difference +
                                     first_blue_difference * first_blue_difference;
    const IntVector second_distance = second_red_difference * second_red_difference +
                                      second_green_difference * second_green_difference +
                                      second_blue_difference * second_blue_difference;
    (IntVector::less(second_distance, first_distance) & IntVector::broadcast(1)).store_bytes(selectors + index);
  }
  for (; index < count; index++) {
    const int32_t *samples = source + index * CHANNELS;
    const int32_t first_distance = (samples[0] - colors[0]) * (samples[0] - colors[0]) +
                                   (samples[1] - colors[1]) * (samples[1] - colors[1]) +
                                   (samples[2] - colors[2]) * (samples[2] - colors[2]);
    const int32_t second_distance = (samples[0] - colors[3]) * (samples[0] - colors[3]) +
                                    (samples[1] - colors[4]) * (samples[1] - colors[4]) +
                                    (samples[2] - colors[5]) * (samples[2] - colors[5]);
    selectors[index] = (int8_t)(second_distance < first_distance ? 1 : 0);
  }
}

int32_t assign_pattern(const int32_t *source, int32_t size, const int32_t *colors, int8_t *selectors) {
  bool columns = true;
  bool rows = true;
  for (int32_t vertical_position = 0; vertical_position < size && (columns || rows); vertical_position++) {
    assign(source + vertical_position * size * CHANNELS, size, colors, selectors + vertical_position * size);
    for (int32_t horizontal_position = 0; horizontal_position < size; horizontal_position++) {
      const int32_t index = vertical_position * size + horizontal_position;
      columns &= selectors[index] == selectors[horizontal_position];
      rows &= selectors[index] == selectors[vertical_position * size];
    }
  }
  return columns || rows ? 1 : 0;
}

inline int32_t sample(int32_t size, int32_t lane_index) {
  return minimum_int((lane_index * size) / 4 + size / 8, size - 1);
}

constexpr int32_t DIRECTIONS[4][2] = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

inline int32_t pack_motion(int32_t horizontal_position, int32_t vertical_position) {
  return wrapping_shift_left(horizontal_position, 16) | (vertical_position & 0xFFFF);
}
inline int32_t unpack_horizontal_motion(int32_t vector) { return vector >> 16; }
inline int32_t unpack_vertical_motion(int32_t vector) { return (int16_t)vector; }

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

        const int32_t difference =
            wrapping_subtraction(reference_pixel[channel], source_row[sample_column * CHANNELS + channel]);
        sum += difference < 0 ? wrapping_subtraction(0, difference) : difference;
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
    sum += four_pixel_difference(pixel_word(line + samples[0] * CHANNELS), pixel_word(line + samples[1] * CHANNELS),
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
    const int32_t row_at = clamp_int(block_top + samples[sample_row] + motion_y, 0, height - 1);
    for (int32_t sample_column = 0; sample_column < 4; sample_column++) {
      const int32_t column = clamp_int(block_left + samples[sample_column] + motion_x, 0, width - 1);
      const uint8_t *reference_pixel = reference + ((int64_t)row_at * width + column) * CHANNELS;
      const int32_t *source_row = source_samples + (sample_row * 4 + sample_column) * CHANNELS;
      for (int32_t channel = 0; channel < CHANNELS; channel++) {
        const int32_t difference = wrapping_subtraction(reference_pixel[channel], source_row[channel]);
        sum += difference < 0 ? wrapping_subtraction(0, difference) : difference;
      }
    }
  }
  return sum;
}

struct Measured {
  static constexpr int32_t CAPACITY = 256;
  int64_t vectors[CAPACITY];
  int32_t count = 0;
  // Full coordinates avoid collisions caused by truncating packed motion vectors.
  static int64_t key(int32_t horizontal_position, int32_t vertical_position) {
    return (int64_t)((uint64_t)(uint32_t)horizontal_position << 32 | (uint32_t)vertical_position);
  }
  bool contains(int32_t horizontal_position, int32_t vertical_position) const {
    const int64_t vector = key(horizontal_position, vertical_position);
    for (int32_t lane_index = 0; lane_index < count; lane_index++) {
      if (vectors[lane_index] == vector) {
        return true;
      }
    }
    return false;
  }
  void add(int32_t horizontal_position, int32_t vertical_position) {
    if (count < CAPACITY) {
      vectors[count++] = key(horizontal_position, vertical_position);
    }
  }
};

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
    const int32_t seed_x = clamp_int(unpack_horizontal_motion(seeds[seed_index]), -range, range);
    const int32_t seed_y = clamp_int(unpack_vertical_motion(seeds[seed_index]), -range, range);
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
      const int32_t candidate_x = clamp_int(center_x + DIRECTIONS[direction][0], -range, range);
      const int32_t candidate_y = clamp_int(center_y + DIRECTIONS[direction][1], -range, range);
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
  return pack_motion(best_x, best_y);
}

void load_source(const uint8_t *image, int32_t width, int32_t height, int32_t horizontal_position,
                 int32_t vertical_position, int32_t size, int32_t *source) {
  for (int32_t pixel_row = 0; pixel_row < size; pixel_row++) {
    const int32_t source_row = minimum_int(vertical_position + pixel_row, height - 1);
    const uint8_t *line = image + (int64_t)source_row * width * CHANNELS;
    int32_t *destination = source + pixel_row * size * CHANNELS;
    if (horizontal_position + size <= width) {
      const uint8_t *source_position = line + (int64_t)horizontal_position * CHANNELS;
      for (int32_t index = 0; index < size * CHANNELS; index += IntVector::LANES) {
        IntVector::load_unsigned_bytes(source_position + index).store(destination + index);
      }
      continue;
    }
    for (int32_t pixel_column = 0; pixel_column < size; pixel_column++) {
      const int32_t source_column = minimum_int(horizontal_position + pixel_column, width - 1);
      for (int32_t channel_index = 0; channel_index < CHANNELS; channel_index++) {
        destination[pixel_column * CHANNELS + channel_index] = line[(int64_t)source_column * CHANNELS + channel_index];
      }
    }
  }
}

template <class Vector> void halve_lanes(const int32_t *block, int32_t size, int32_t *output) {
  const int32_t half = size / 2;
  const int32_t row_length = size * CHANNELS;
  const Vector two = Vector::broadcast(2);
  for (int32_t pixel_row = 0; pixel_row < half; pixel_row++) {
    const int32_t *top = block + 2 * pixel_row * row_length;
    const int32_t *bottom = top + row_length;
    int32_t *line = output + pixel_row * half * CHANNELS;
    for (int32_t horizontal_position = 0; horizontal_position < size; horizontal_position += 2 * Vector::LANES) {
      Vector first_red, first_green, first_blue, second_red, second_green, second_blue, third_red, third_green,
          third_blue, fourth_red, fourth_green, fourth_blue;
      Vector::load_channels(top + horizontal_position * CHANNELS, first_red, first_green, first_blue);
      Vector::load_channels(top + (horizontal_position + Vector::LANES) * CHANNELS, second_red, second_green,
                            second_blue);
      Vector::load_channels(bottom + horizontal_position * CHANNELS, third_red, third_green, third_blue);
      Vector::load_channels(bottom + (horizontal_position + Vector::LANES) * CHANNELS, fourth_red, fourth_green,
                            fourth_blue);
      const Vector first_red_sum = first_red + third_red;
      const Vector second_red_sum = second_red + fourth_red;
      const Vector first_green_sum = first_green + third_green;
      const Vector second_green_sum = second_green + fourth_green;
      const Vector first_blue_sum = first_blue + third_blue;
      const Vector second_blue_sum = second_blue + fourth_blue;
      const Vector red_sum =
          Vector::evens(first_red_sum, second_red_sum) + Vector::odds(first_red_sum, second_red_sum) + two;
      const Vector green_sum =
          Vector::evens(first_green_sum, second_green_sum) + Vector::odds(first_green_sum, second_green_sum) + two;
      const Vector blue_sum =
          Vector::evens(first_blue_sum, second_blue_sum) + Vector::odds(first_blue_sum, second_blue_sum) + two;
      Vector::store_channels(line + (horizontal_position / 2) * CHANNELS, red_sum.shift_right(2),
                             green_sum.shift_right(2), blue_sum.shift_right(2));
    }
  }
}

void halve(const int32_t *block, int32_t size, int32_t *output) {

  if (SHUFFLED_CHANNEL_LOAD || size < 2 * IntVector::LANES) {
    halve_lanes<ScalarIntVector>(block, size, output);
  } else {
    halve_lanes<IntVector>(block, size, output);
  }
}

void residual_target(const int32_t *source, const int32_t *prediction, int32_t count, float *target) {
  const FloatVector quarter = FloatVector::broadcast(0.25f);
  const FloatVector two = FloatVector::broadcast(2.0f);
  int32_t pixel = 0;
  for (; pixel + IntVector::LANES <= count; pixel += IntVector::LANES) {
    IntVector source_red, source_green, source_blue;
    IntVector::load_channels(source + pixel * CHANNELS, source_red, source_green, source_blue);
    const FloatVector luma =
        FloatVector::from_integers(source_red + source_green.shift_left(1) + source_blue) * quarter;
    IntVector predicted_red, predicted_green, predicted_blue;
    IntVector::load_channels(prediction + pixel * CHANNELS, predicted_red, predicted_green, predicted_blue);
    const FloatVector red = FloatVector::from_integers(predicted_red);
    const FloatVector green = FloatVector::from_integers(predicted_green);
    const FloatVector blue = FloatVector::from_integers(predicted_blue);
    (luma - ((red + two * green) + blue) * quarter).store(target + pixel);
  }
  for (; pixel < count; pixel++) {
    const int32_t array_offset = pixel * CHANNELS;
    const float luma = (float)(source[array_offset] + 2 * source[array_offset + 1] + source[array_offset + 2]) * 0.25f;
    const float red = (float)prediction[array_offset];
    const float green = (float)prediction[array_offset + 1];
    const float blue = (float)prediction[array_offset + 2];
    target[pixel] = luma - (red + 2 * green + blue) * 0.25f;
  }
}

}
}

// Narrow blocks require a smaller SIMD level to avoid reading past their rows.

using mcv2::assign;
using mcv2::assign_pattern;
using mcv2::cluster;
using mcv2::compact;
using mcv2::fit;
using mcv2::halve;
using mcv2::load_source;
using mcv2::palette;
using mcv2::predict;
using mcv2::predicted;
using mcv2::residual_target;
using mcv2::seeded;
using mcv2::solid;

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

#if defined(MCV2_FIT_TO)
MCV2_EXPORT void MCV2_FIT_TO(fit)(const float *values, int32_t size, const float *matrix, float *output);
#endif

int64_t MCV2_PREFIX(predicted)(const int32_t *prediction, int32_t size, int32_t *output, const int32_t *source,
                               double rate, double limit) {
  MCV2_HAND_OFF(predicted, prediction, size, output, source, rate, limit)
  return predicted(prediction, size, output, {source, rate, limit, 0});
}

int64_t MCV2_PREFIX(solid)(int32_t color, int32_t size, int32_t *output, const int32_t *source, double rate,
                           double limit) {
  MCV2_HAND_OFF(solid, color, size, output, source, rate, limit)
  return solid(color, size, output, {source, rate, limit, 0});
}

int64_t MCV2_PREFIX(palette)(const int8_t *record, int32_t size, int32_t *output, const int32_t *source, double rate,
                             double limit) {
  MCV2_HAND_OFF(palette, record, size, output, source, rate, limit)
  return palette(record, size, output, {source, rate, limit, 0});
}

int64_t MCV2_PREFIX(compact)(const int32_t *prediction, const int8_t *record, int32_t quantizer, int32_t size,
                             int32_t *output, const int32_t *source, double rate, double limit) {
  MCV2_HAND_OFF(compact, prediction, record, quantizer, size, output, source, rate, limit)
  return compact(prediction, record, quantizer, size, output, {source, rate, limit, 0});
}

void MCV2_PREFIX(predict)(const uint8_t *reference, int32_t width, int32_t height, int32_t horizontal_position,
                          int32_t vertical_position, int32_t size, int32_t horizontal_motion, int32_t vertical_motion,
                          int32_t *output) {
  MCV2_HAND_OFF(predict, reference, width, height, horizontal_position, vertical_position, size, horizontal_motion,
                vertical_motion, output)
  predict(reference, width, height, horizontal_position, vertical_position, size, horizontal_motion, vertical_motion,
          output);
}

void MCV2_PREFIX(fit)(const float *values, int32_t size, const float *matrix, float *output) {
  MCV2_HAND_OFF(fit, values, size, matrix, output)
#if defined(MCV2_FIT_TO)
  if (size == MCV2_FIT_AT) {
    return MCV2_FIT_TO(fit)(values, size, matrix, output);
  }
#endif
  fit(values, size, matrix, output);
}

void MCV2_PREFIX(cluster)(const int32_t *source, int32_t size, int32_t *endpoints) {
  // Blocks narrower than two vectors would make the sampled clustering kernel overread.
  MCV2_HAND_OFF_BELOW(2 * MCV2_NARROW_BELOW, cluster, source, size, endpoints)
  cluster(source, size, endpoints);
}

void MCV2_PREFIX(assign)(const int32_t *source, int32_t count, const int32_t *colors, int8_t *selectors) {
  assign(source, count, colors, selectors);
}

int32_t MCV2_PREFIX(assign_pattern)(const int32_t *source, int32_t size, const int32_t *colors, int8_t *selectors) {
  MCV2_HAND_OFF(assign_pattern, source, size, colors, selectors)
  return assign_pattern(source, size, colors, selectors);
}

int32_t MCV2_PREFIX(seeded)(const uint8_t *reference, int32_t width, int32_t height, const int32_t *source,
                            int32_t horizontal_position, int32_t vertical_position, int32_t size, int32_t range,
                            const int32_t *seeds, int32_t seed_count) {
  MCV2_HAND_OFF(seeded, reference, width, height, source, horizontal_position, vertical_position, size, range, seeds,
                seed_count)
  return seeded(reference, width, height, source, horizontal_position, vertical_position, size, range, seeds,
                seed_count);
}

void MCV2_PREFIX(load_source)(const uint8_t *image, int32_t width, int32_t height, int32_t horizontal_position,
                              int32_t vertical_position, int32_t size, int32_t *source) {
  MCV2_HAND_OFF(load_source, image, width, height, horizontal_position, vertical_position, size, source)
  load_source(image, width, height, horizontal_position, vertical_position, size, source);
}

void MCV2_PREFIX(halve)(const int32_t *block, int32_t size, int32_t *output) {
  // Blocks narrower than two vectors would make the halving kernel overread.
  MCV2_HAND_OFF_BELOW(2 * MCV2_NARROW_BELOW, halve, block, size, output)
  halve(block, size, output);
}

void MCV2_PREFIX(residual_target)(const int32_t *source, const int32_t *prediction, int32_t count, float *target) {
  residual_target(source, prediction, count, target);
}
}

#if defined(MCV2_CPU)

#if defined(__x86_64__) || defined(_M_X64)
#include <cpuid.h>

namespace {

constexpr unsigned SSE41_BIT = 1u << 19;
constexpr unsigned OSXSAVE_BIT = 1u << 27;
constexpr unsigned AVX_BIT = 1u << 28;
constexpr unsigned AVX2_BIT = 1u << 5;

constexpr unsigned long long YMM_STATE = 0x6;
#if !defined(__APPLE__)

constexpr unsigned AVX512_EBX = (1u << 16) | (1u << 17) | (1u << 30) | (1u << 31);
constexpr unsigned AVX512_ECX = (1u << 1) | (1u << 6) | (1u << 11) | (1u << 12);

constexpr unsigned long long ZMM_STATE = 0xE6;
#endif

unsigned long long extended_control_state() {
  unsigned low;
  unsigned high;
  __asm__ volatile("xgetbv" : "=a"(low), "=d"(high) : "c"(0));
  return ((unsigned long long)high << 32) | low;
}

}

extern "C" int32_t mcv2_cpu_levels(int64_t) {

  int32_t levels = MCV2_LEVEL_SCALAR | MCV2_LEVEL_SSE2;
  unsigned register_a;
  unsigned register_b;
  unsigned register_c;
  unsigned register_d;
  if (!__get_cpuid(1, &register_a, &register_b, &register_c, &register_d)) {
    return levels;
  }
  if (register_c & SSE41_BIT) {
    levels |= MCV2_LEVEL_SSE41;
  }
  const unsigned long long state = (register_c & OSXSAVE_BIT) && (register_c & AVX_BIT) ? extended_control_state() : 0;
  if ((state & YMM_STATE) != YMM_STATE ||
      !__get_cpuid_count(7, 0, &register_a, &register_b, &register_c, &register_d)) {
    return levels;
  }
  if (register_b & AVX2_BIT) {
    levels |= MCV2_LEVEL_AVX2;
  }
#if !defined(__APPLE__)
  // macOS saves ZMM state lazily, so XCR0 cannot authorize AVX-512.
  if ((state & ZMM_STATE) == ZMM_STATE && (register_b & AVX512_EBX) == AVX512_EBX &&
      (register_c & AVX512_ECX) == AVX512_ECX) {
    levels |= MCV2_LEVEL_AVX512;
  }
#endif
  return levels;
}

#elif defined(__aarch64__) || defined(_M_ARM64)

extern "C" int32_t mcv2_cpu_levels(int64_t kernel_capabilities) {
  int32_t levels = MCV2_LEVEL_SCALAR | MCV2_LEVEL_NEON;
#if defined(__linux__)
  if (kernel_capabilities & MCV2_HWCAP_SVE) {

    unsigned long long bytes;
    __asm__ volatile(".arch_extension sve\n\trdvl %0, #1" : "=r"(bytes));
    if (bytes == 32) {
      levels |= MCV2_LEVEL_SVE256;
    } else if (bytes == 64) {
      levels |= MCV2_LEVEL_SVE512;
    }
  }
#else
  (void)kernel_capabilities;
#endif
  return levels;
}

#else

extern "C" int32_t mcv2_cpu_levels(int64_t) { return MCV2_LEVEL_SCALAR; }

#endif

extern "C" int32_t mcv2_abi(void) { return MCV2_INTERFACE_VERSION; }
#endif
#endif
