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

#define MCV2_HARDWARE_CAPABILITY_SVE (1LL << 22)

#define MCV2_KERNELS(KERNEL)                                                                                           \
  KERNEL(int64_t, predicted,                                                                                           \
         (const int32_t* prediction, int32_t size, int32_t* output, const int32_t* source, double rate, double limit)) \
  KERNEL(int64_t, solid,                                                                                               \
         (int32_t color, int32_t size, int32_t* output, const int32_t* source, double rate, double limit))             \
  KERNEL(int64_t, palette,                                                                                             \
         (const int8_t* record, int32_t size, int32_t* output, const int32_t* source, double rate, double limit))      \
  KERNEL(int64_t, compact,                                                                                             \
         (const int32_t* prediction, const int8_t* record, int32_t quantizer, int32_t size, int32_t* output,           \
          const int32_t* source, double rate, double limit))                                                           \
  KERNEL(void, predict,                                                                                                \
         (const uint8_t* reference, int32_t width, int32_t height, int32_t horizontal_position,                        \
          int32_t vertical_position, int32_t size, int32_t horizontal_motion, int32_t vertical_motion,                 \
          int32_t* output))                                                                                            \
  KERNEL(void, fit, (const float* values, int32_t size, const float* matrix, float* output))                           \
  KERNEL(void, cluster, (const int32_t* source, int32_t size, int32_t* endpoints))                                     \
  KERNEL(void, assign, (const int32_t* source, int32_t count, const int32_t* colors, int8_t* selectors))               \
  KERNEL(int32_t, assign_pattern, (const int32_t* source, int32_t size, const int32_t* colors, int8_t* selectors))     \
  KERNEL(int32_t, seeded,                                                                                              \
         (const uint8_t* reference, int32_t width, int32_t height, const int32_t* source, int32_t horizontal_position, \
          int32_t vertical_position, int32_t size, int32_t range, const int32_t* seeds, int32_t seed_count))           \
  KERNEL(void, load_source,                                                                                            \
         (const uint8_t* image, int32_t width, int32_t height, int32_t horizontal_position, int32_t vertical_position, \
          int32_t size, int32_t* source))                                                                              \
  KERNEL(void, halve, (const int32_t* block, int32_t size, int32_t* output))                                           \
  KERNEL(void, residual_target, (const int32_t* source, const int32_t* prediction, int32_t count, float* target))

extern "C" {

// Java supplies AT_HWCAP because this library imports no C-library functions.
MCV2_EXPORT int32_t mcv2_cpu_levels(int64_t kernel_capabilities);

MCV2_EXPORT int32_t mcv2_abi(void);
}

#if !defined(MCV2_DECLARATIONS_ONLY)

#include <limits.h>
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

constexpr int32_t SHIFT_DISTANCE_MASK = 31;

constexpr int32_t BYTE_INDEX_SHIFT = 3;
constexpr int32_t BIT_INDEX_MASK = 7;

constexpr int32_t SAMPLE_GRID = 4;
constexpr int32_t WORD_BYTES = (int32_t)sizeof(uint32_t);
constexpr int32_t SAMPLE_ROW_BYTES = SAMPLE_GRID * WORD_BYTES;

// SSE load/store shuffles cost more than the arithmetic in the halving kernel.
#if defined(MCV2_SIMD_SSE2) || defined(MCV2_SIMD_SSE41)
constexpr bool SHUFFLED_CHANNEL_LOAD = true;
#else
constexpr bool SHUFFLED_CHANNEL_LOAD = false;
#endif

#if defined(MCV2_SIMD_SSE41) || defined(MCV2_SIMD_SSE2) || defined(MCV2_SIMD_AVX2)
constexpr int32_t SWAP_HALVES = _MM_SHUFFLE(1, 0, 3, 2);
constexpr int32_t SWAP_PAIRS = _MM_SHUFFLE(2, 3, 0, 1);

constexpr int32_t EVEN_LANES = _MM_SHUFFLE(2, 0, 2, 0);
constexpr int32_t ODD_LANES = _MM_SHUFFLE(3, 1, 3, 1);
#endif

#if defined(MCV2_SIMD_SSE41) || defined(MCV2_SIMD_SSE2) || defined(MCV2_SIMD_AVX2) || defined(MCV2_SIMD_AVX512)
constexpr int32_t HALF_VECTOR_BYTES = 8;
#endif

inline int32_t wrapping_addition(const int32_t first, const int32_t second) {
  return (int32_t)((uint32_t)first + (uint32_t)second);
}
inline int32_t wrapping_subtraction(const int32_t first, const int32_t second) {
  return (int32_t)((uint32_t)first - (uint32_t)second);
}
inline int32_t wrapping_multiplication(const int32_t first, const int32_t second) {
  return (int32_t)((uint32_t)first * (uint32_t)second);
}
inline int32_t wrapping_shift_left(const int32_t first, const int32_t shift_distance) {
  return (int32_t)((uint32_t)first << (shift_distance & SHIFT_DISTANCE_MASK));
}
inline int32_t wrapping_shift_right(const int32_t first, const int32_t shift_distance) {
  return first >> (shift_distance & SHIFT_DISTANCE_MASK);
}

inline uint32_t pixel_word(const uint8_t* buffer) {
  return (uint32_t)buffer[0] | (uint32_t)buffer[1] << CHAR_BIT | (uint32_t)buffer[2] << 2 * CHAR_BIT;
}

struct scalar_int_vector {
  static constexpr int LANES = 1;
  int32_t lanes_;
  static scalar_int_vector load(const int32_t* buffer) {
    return {buffer[0]};
  }
  void store(int32_t* buffer) const {
    buffer[0] = lanes_;
  }

  void store_bytes(int8_t* buffer) const {
    buffer[0] = (int8_t)lanes_;
  }
  static scalar_int_vector broadcast(const int32_t value) {
    return {value};
  }
  static scalar_int_vector zero() {
    return {0};
  }
  static scalar_int_vector lane_indices() {
    return {0};
  }
  static scalar_int_vector load_unsigned_bytes(const uint8_t* buffer) {
    return {buffer[0]};
  }
  friend scalar_int_vector operator+(const scalar_int_vector first, const scalar_int_vector second) {
    return {wrapping_addition(first.lanes_, second.lanes_)};
  }
  friend scalar_int_vector operator-(const scalar_int_vector first, const scalar_int_vector second) {
    return {wrapping_subtraction(first.lanes_, second.lanes_)};
  }
  friend scalar_int_vector operator*(const scalar_int_vector first, const scalar_int_vector second) {
    return {wrapping_multiplication(first.lanes_, second.lanes_)};
  }
  friend scalar_int_vector operator&(const scalar_int_vector first, const scalar_int_vector second) {
    return {first.lanes_ & second.lanes_};
  }
  friend scalar_int_vector operator|(const scalar_int_vector first, const scalar_int_vector second) {
    return {first.lanes_ | second.lanes_};
  }
  scalar_int_vector shift_left(const int32_t shift_distance) const {
    return {wrapping_shift_left(lanes_, shift_distance)};
  }
  scalar_int_vector shift_right(const int32_t shift_distance) const {
    return {wrapping_shift_right(lanes_, shift_distance)};
  }
  static scalar_int_vector minimum(const scalar_int_vector first, const scalar_int_vector second) {
    return {first.lanes_ < second.lanes_ ? first.lanes_ : second.lanes_};
  }
  static scalar_int_vector maximum(const scalar_int_vector first, const scalar_int_vector second) {
    return {first.lanes_ > second.lanes_ ? first.lanes_ : second.lanes_};
  }
  static scalar_int_vector absolute(const scalar_int_vector first) {
    return {first.lanes_ < 0 ? wrapping_subtraction(0, first.lanes_) : first.lanes_};
  }

  static scalar_int_vector less(const scalar_int_vector first, const scalar_int_vector second) {
    return {first.lanes_ < second.lanes_ ? -1 : 0};
  }
  int32_t sum() const {
    return lanes_;
  }
  static void load_channels(const int32_t* buffer, scalar_int_vector& red_channels, scalar_int_vector& green_channels,
                            scalar_int_vector& blue_channels) {
    red_channels.lanes_ = buffer[0];
    green_channels.lanes_ = buffer[1];
    blue_channels.lanes_ = buffer[2];
  }
  static void store_channels(int32_t* buffer, const scalar_int_vector red_channels,
                             const scalar_int_vector green_channels, const scalar_int_vector blue_channels) {
    buffer[0] = red_channels.lanes_;
    buffer[1] = green_channels.lanes_;
    buffer[2] = blue_channels.lanes_;
  }

  static scalar_int_vector bits(const int8_t* buffer, const int32_t index) {
    return {((buffer[index >> BYTE_INDEX_SHIFT] >> (index & BIT_INDEX_MASK)) & 1) ? -1 : 0};
  }

  static scalar_int_vector evens(const scalar_int_vector first, scalar_int_vector) {
    return first;
  }

  static scalar_int_vector odds(scalar_int_vector, const scalar_int_vector second) {
    return second;
  }
};

#if defined(MCV2_SIMD_SCALAR)

using int_vector = scalar_int_vector;

inline int32_t four_pixel_difference(const uint32_t first_pixel, const uint32_t second_pixel,
                                     const uint32_t third_pixel, const uint32_t fourth_pixel,
                                     const uint8_t* sample_bytes) {
  const uint32_t words[SAMPLE_GRID] = {first_pixel, second_pixel, third_pixel, fourth_pixel};
  int32_t sum = 0;
  for (int32_t byte_index = 0; byte_index < SAMPLE_ROW_BYTES; byte_index++) {
    const int32_t difference =
        (int32_t)((words[byte_index / WORD_BYTES] >> (CHAR_BIT * (byte_index % WORD_BYTES))) & UINT8_MAX) -
        sample_bytes[byte_index];
    sum += difference < 0 ? -difference : difference;
  }
  return sum;
}

struct float_vector {
  float lanes_;
  static float_vector broadcast(const float value) {
    return {value};
  }
  static float_vector from_integers(const int_vector first) {
    return {(float)first.lanes_};
  }
  friend float_vector operator+(const float_vector first, const float_vector second) {
    return {first.lanes_ + second.lanes_};
  }
  friend float_vector operator-(const float_vector first, const float_vector second) {
    return {first.lanes_ - second.lanes_};
  }
  friend float_vector operator*(const float_vector first, const float_vector second) {
    return {first.lanes_ * second.lanes_};
  }
  void store(float* buffer) const {
    buffer[0] = lanes_;
  }
};

struct double_vector {
  static constexpr int LANES = 1;
  double lanes_;
  static double_vector broadcast(const double value) {
    return {value};
  }
  static double_vector load_floats(const float* buffer) {
    return {(double)buffer[0]};
  }
  friend double_vector operator+(const double_vector first, const double_vector second) {
    return {first.lanes_ + second.lanes_};
  }
  friend double_vector operator*(const double_vector first, const double_vector second) {
    return {first.lanes_ * second.lanes_};
  }
  void store(double* buffer) const {
    buffer[0] = lanes_;
  }
};

#elif defined(MCV2_SIMD_SSE41) || defined(MCV2_SIMD_SSE2)

constexpr int32_t EVERY_THIRD_LANE_FROM_ONE = 0x2;
constexpr int32_t EVERY_THIRD_LANE_FROM_TWO = 0x4;

constexpr int32_t RED_LOAD_ORDER = _MM_SHUFFLE(1, 2, 3, 0);
constexpr int32_t GREEN_LOAD_ORDER = _MM_SHUFFLE(2, 3, 0, 1);
constexpr int32_t BLUE_LOAD_ORDER = _MM_SHUFFLE(3, 0, 1, 2);

constexpr int32_t RED_STORE_ORDER = _MM_SHUFFLE(1, 0, 0, 0);
constexpr int32_t GREEN_STORE_ORDER = _MM_SHUFFLE(2, 0, 0, 1);
constexpr int32_t BLUE_STORE_ORDER = _MM_SHUFFLE(3, 0, 0, 2);

constexpr int32_t BROADCAST_FIRST_LANE = _MM_SHUFFLE(0, 0, 0, 0);
constexpr int32_t BROADCAST_SECOND_LANE = _MM_SHUFFLE(1, 1, 1, 1);
constexpr int32_t BROADCAST_THIRD_LANE = _MM_SHUFFLE(2, 2, 2, 2);
constexpr int32_t BROADCAST_FOURTH_LANE = _MM_SHUFFLE(3, 3, 3, 3);

#if defined(MCV2_SIMD_SSE41)
template <int BLEND_MASK> inline __m128i blend_integer_lanes(const __m128i first, const __m128i second) {
  const __m128 first_floats = _mm_castsi128_ps(first);
  const __m128 second_floats = _mm_castsi128_ps(second);
  const __m128 blended = _mm_blend_ps(first_floats, second_floats, BLEND_MASK);
  return _mm_castps_si128(blended);
}
inline __m128i multiply_low_int(const __m128i first, const __m128i second) {
  return _mm_mullo_epi32(first, second);
}
inline __m128i minimum_int(const __m128i first, const __m128i second) {
  return _mm_min_epi32(first, second);
}
inline __m128i maximum_int(const __m128i first, const __m128i second) {
  return _mm_max_epi32(first, second);
}
inline __m128i absolute_int(const __m128i first) {
  return _mm_abs_epi32(first);
}
inline __m128i widen_unsigned_bytes(const __m128i bytes) {
  return _mm_cvtepu8_epi32(bytes);
}
#else
constexpr int32_t PACK_LOW_WORDS = _MM_SHUFFLE(0, 0, 2, 0);

constexpr int32_t FIRST_LANE_BIT = 1;
constexpr int32_t SECOND_LANE_BIT = 2;
constexpr int32_t THIRD_LANE_BIT = 4;
constexpr int32_t FOURTH_LANE_BIT = 8;

constexpr int32_t LANE_BITS = 32;
constexpr int32_t SIGN_SHIFT = LANE_BITS - 1;

template <int BLEND_MASK> inline __m128i blend_integer_lanes(const __m128i first, const __m128i second) {
  const __m128i mask = _mm_setr_epi32((BLEND_MASK & FIRST_LANE_BIT) ? -1 : 0, (BLEND_MASK & SECOND_LANE_BIT) ? -1 : 0,
                                      (BLEND_MASK & THIRD_LANE_BIT) ? -1 : 0, (BLEND_MASK & FOURTH_LANE_BIT) ? -1 : 0);
  const __m128i selected_second = _mm_and_si128(mask, second);
  const __m128i selected_first = _mm_andnot_si128(mask, first);
  return _mm_or_si128(selected_second, selected_first);
}
// Signed and unsigned products share the low 32 bits required by Java's wrapping arithmetic.
inline __m128i multiply_low_int(const __m128i first, const __m128i second) {
  const __m128i even = _mm_mul_epu32(first, second);
  const __m128i first_odd_lanes = _mm_srli_epi64(first, LANE_BITS);
  const __m128i second_odd_lanes = _mm_srli_epi64(second, LANE_BITS);
  const __m128i odd = _mm_mul_epu32(first_odd_lanes, second_odd_lanes);
  const __m128i packed_even = _mm_shuffle_epi32(even, PACK_LOW_WORDS);
  const __m128i packed_odd = _mm_shuffle_epi32(odd, PACK_LOW_WORDS);
  return _mm_unpacklo_epi32(packed_even, packed_odd);
}
inline __m128i minimum_int(const __m128i first, const __m128i second) {
  const __m128i less = _mm_cmplt_epi32(first, second);
  const __m128i first_minimums = _mm_and_si128(less, first);
  const __m128i second_minimums = _mm_andnot_si128(less, second);
  return _mm_or_si128(first_minimums, second_minimums);
}
inline __m128i maximum_int(const __m128i first, const __m128i second) {
  const __m128i greater = _mm_cmpgt_epi32(first, second);
  const __m128i first_maximums = _mm_and_si128(greater, first);
  const __m128i second_maximums = _mm_andnot_si128(greater, second);
  return _mm_or_si128(first_maximums, second_maximums);
}
// Java Math.abs leaves Integer.MIN_VALUE negative.
inline __m128i absolute_int(const __m128i first) {
  const __m128i sign = _mm_srai_epi32(first, SIGN_SHIFT);
  const __m128i flipped = _mm_xor_si128(first, sign);
  return _mm_sub_epi32(flipped, sign);
}
inline __m128i widen_unsigned_bytes(const __m128i bytes) {
  const __m128i zero = _mm_setzero_si128();
  const __m128i words = _mm_unpacklo_epi8(bytes, zero);
  return _mm_unpacklo_epi16(words, zero);
}
#endif

inline int32_t four_pixel_difference(const uint32_t first_pixel, const uint32_t second_pixel,
                                     const uint32_t third_pixel, const uint32_t fourth_pixel,
                                     const uint8_t* sample_bytes) {
  const __m128i pixels =
      _mm_setr_epi32((int32_t)first_pixel, (int32_t)second_pixel, (int32_t)third_pixel, (int32_t)fourth_pixel);
  const __m128i samples = _mm_loadu_si128((const __m128i*)sample_bytes);
  const __m128i absolute_differences = _mm_sad_epu8(pixels, samples);
  const __m128i upper_differences = _mm_srli_si128(absolute_differences, HALF_VECTOR_BYTES);
  return _mm_cvtsi128_si32(absolute_differences) + _mm_cvtsi128_si32(upper_differences);
}

struct int_vector {
  static constexpr int LANES = 4;
  static constexpr int32_t LANE_MASK = (1 << LANES) - 1;
  __m128i lanes_;
  static int_vector load(const int32_t* buffer) {
    return {_mm_loadu_si128((const __m128i*)buffer)};
  }
  void store(int32_t* buffer) const {
    _mm_storeu_si128((__m128i*)buffer, lanes_);
  }
  void store_bytes(int8_t* buffer) const {
    const __m128i narrowed = _mm_packs_epi32(lanes_, lanes_);
    const __m128i zero = _mm_setzero_si128();
    const __m128i bytes = _mm_packs_epi16(narrowed, zero);
    const int32_t word = _mm_cvtsi128_si32(bytes);
    memcpy(buffer, &word, sizeof(word));
  }
  static int_vector broadcast(const int32_t value) {
    return {_mm_set1_epi32(value)};
  }
  static int_vector zero() {
    return {_mm_setzero_si128()};
  }
  static int_vector lane_indices() {
    return {_mm_setr_epi32(0, 1, 2, 3)};
  }
  static int_vector load_unsigned_bytes(const uint8_t* buffer) {
    int32_t word;
    memcpy(&word, buffer, sizeof(word));
    const __m128i bytes = _mm_cvtsi32_si128(word);
    return {widen_unsigned_bytes(bytes)};
  }
  friend int_vector operator+(const int_vector first, const int_vector second) {
    return {_mm_add_epi32(first.lanes_, second.lanes_)};
  }
  friend int_vector operator-(const int_vector first, const int_vector second) {
    return {_mm_sub_epi32(first.lanes_, second.lanes_)};
  }
  friend int_vector operator*(const int_vector first, const int_vector second) {
    return {multiply_low_int(first.lanes_, second.lanes_)};
  }
  friend int_vector operator&(const int_vector first, const int_vector second) {
    return {_mm_and_si128(first.lanes_, second.lanes_)};
  }
  friend int_vector operator|(const int_vector first, const int_vector second) {
    return {_mm_or_si128(first.lanes_, second.lanes_)};
  }
  int_vector shift_left(const int32_t shift_distance) const {
    const __m128i distance = _mm_cvtsi32_si128(shift_distance & SHIFT_DISTANCE_MASK);
    return {_mm_sll_epi32(lanes_, distance)};
  }
  int_vector shift_right(const int32_t shift_distance) const {
    const __m128i distance = _mm_cvtsi32_si128(shift_distance & SHIFT_DISTANCE_MASK);
    return {_mm_sra_epi32(lanes_, distance)};
  }
  static int_vector minimum(const int_vector first, const int_vector second) {
    return {minimum_int(first.lanes_, second.lanes_)};
  }
  static int_vector maximum(const int_vector first, const int_vector second) {
    return {maximum_int(first.lanes_, second.lanes_)};
  }
  static int_vector absolute(const int_vector first) {
    return {absolute_int(first.lanes_)};
  }
  static int_vector less(const int_vector first, const int_vector second) {
    return {_mm_cmplt_epi32(first.lanes_, second.lanes_)};
  }
  int32_t sum() const {
    const __m128i swapped_halves = _mm_shuffle_epi32(lanes_, SWAP_HALVES);
    const __m128i pair_sums = _mm_add_epi32(lanes_, swapped_halves);
    const __m128i swapped_pairs = _mm_shuffle_epi32(pair_sums, SWAP_PAIRS);
    const __m128i totals = _mm_add_epi32(pair_sums, swapped_pairs);
    return _mm_cvtsi128_si32(totals);
  }

  static void load_channels(const int32_t* buffer, int_vector& red_channels, int_vector& green_channels,
                            int_vector& blue_channels) {
    const __m128i first_vector = _mm_loadu_si128((const __m128i*)buffer);
    const __m128i second_vector = _mm_loadu_si128((const __m128i*)(buffer + LANES));
    const __m128i third_vector = _mm_loadu_si128((const __m128i*)(buffer + 2 * LANES));

    const __m128i partial_red = blend_integer_lanes<EVERY_THIRD_LANE_FROM_TWO>(first_vector, second_vector);
    const __m128i shuffled_red = blend_integer_lanes<EVERY_THIRD_LANE_FROM_ONE>(partial_red, third_vector);

    const __m128i partial_green = blend_integer_lanes<EVERY_THIRD_LANE_FROM_ONE>(second_vector, first_vector);
    const __m128i shuffled_green = blend_integer_lanes<EVERY_THIRD_LANE_FROM_TWO>(partial_green, third_vector);

    const __m128i partial_blue = blend_integer_lanes<EVERY_THIRD_LANE_FROM_ONE>(third_vector, second_vector);
    const __m128i shuffled_blue = blend_integer_lanes<EVERY_THIRD_LANE_FROM_TWO>(partial_blue, first_vector);

    red_channels.lanes_ = _mm_shuffle_epi32(shuffled_red, RED_LOAD_ORDER);
    green_channels.lanes_ = _mm_shuffle_epi32(shuffled_green, GREEN_LOAD_ORDER);
    blue_channels.lanes_ = _mm_shuffle_epi32(shuffled_blue, BLUE_LOAD_ORDER);
  }
  static void store_channels(int32_t* buffer, const int_vector red_channels, const int_vector green_channels,
                             const int_vector blue_channels) {
    const __m128i ordered_red = _mm_shuffle_epi32(red_channels.lanes_, RED_STORE_ORDER);
    const __m128i first_green = _mm_shuffle_epi32(green_channels.lanes_, BROADCAST_FIRST_LANE);
    const __m128i first_blue = _mm_shuffle_epi32(blue_channels.lanes_, BROADCAST_FIRST_LANE);
    const __m128i first_red_green = blend_integer_lanes<EVERY_THIRD_LANE_FROM_ONE>(ordered_red, first_green);
    const __m128i first_vector = blend_integer_lanes<EVERY_THIRD_LANE_FROM_TWO>(first_red_green, first_blue);

    const __m128i ordered_green = _mm_shuffle_epi32(green_channels.lanes_, GREEN_STORE_ORDER);
    const __m128i second_blue = _mm_shuffle_epi32(blue_channels.lanes_, BROADCAST_SECOND_LANE);
    const __m128i third_red = _mm_shuffle_epi32(red_channels.lanes_, BROADCAST_THIRD_LANE);
    const __m128i second_green_blue = blend_integer_lanes<EVERY_THIRD_LANE_FROM_ONE>(ordered_green, second_blue);
    const __m128i second_vector = blend_integer_lanes<EVERY_THIRD_LANE_FROM_TWO>(second_green_blue, third_red);

    const __m128i ordered_blue = _mm_shuffle_epi32(blue_channels.lanes_, BLUE_STORE_ORDER);
    const __m128i fourth_red = _mm_shuffle_epi32(red_channels.lanes_, BROADCAST_FOURTH_LANE);
    const __m128i fourth_green = _mm_shuffle_epi32(green_channels.lanes_, BROADCAST_FOURTH_LANE);
    const __m128i third_blue_red = blend_integer_lanes<EVERY_THIRD_LANE_FROM_ONE>(ordered_blue, fourth_red);
    const __m128i third_vector = blend_integer_lanes<EVERY_THIRD_LANE_FROM_TWO>(third_blue_red, fourth_green);

    _mm_storeu_si128((__m128i*)buffer, first_vector);
    _mm_storeu_si128((__m128i*)(buffer + LANES), second_vector);
    _mm_storeu_si128((__m128i*)(buffer + 2 * LANES), third_vector);
  }
  static int_vector bits(const int8_t* buffer, const int32_t index) {
    const __m128i byte = _mm_set1_epi32((buffer[index >> BYTE_INDEX_SHIFT] >> (index & BIT_INDEX_MASK)) & LANE_MASK);
    const __m128i lane_bits = _mm_setr_epi32(1, 2, 4, 8);
    const __m128i set_bits = _mm_and_si128(byte, lane_bits);
    return {_mm_cmpeq_epi32(set_bits, lane_bits)};
  }
  static int_vector evens(const int_vector first, const int_vector second) {
    const __m128 first_floats = _mm_castsi128_ps(first.lanes_);
    const __m128 second_floats = _mm_castsi128_ps(second.lanes_);
    const __m128 even_floats = _mm_shuffle_ps(first_floats, second_floats, EVEN_LANES);
    return {_mm_castps_si128(even_floats)};
  }
  static int_vector odds(const int_vector first, const int_vector second) {
    const __m128 first_floats = _mm_castsi128_ps(first.lanes_);
    const __m128 second_floats = _mm_castsi128_ps(second.lanes_);
    const __m128 odd_floats = _mm_shuffle_ps(first_floats, second_floats, ODD_LANES);
    return {_mm_castps_si128(odd_floats)};
  }
};

struct float_vector {
  __m128 lanes_;
  static float_vector broadcast(const float value) {
    return {_mm_set1_ps(value)};
  }
  static float_vector from_integers(const int_vector first) {
    return {_mm_cvtepi32_ps(first.lanes_)};
  }
  friend float_vector operator+(const float_vector first, const float_vector second) {
    return {_mm_add_ps(first.lanes_, second.lanes_)};
  }
  friend float_vector operator-(const float_vector first, const float_vector second) {
    return {_mm_sub_ps(first.lanes_, second.lanes_)};
  }
  friend float_vector operator*(const float_vector first, const float_vector second) {
    return {_mm_mul_ps(first.lanes_, second.lanes_)};
  }
  void store(float* buffer) const {
    _mm_storeu_ps(buffer, lanes_);
  }
};

struct double_vector {
  static constexpr int LANES = 2;
  __m128d lanes_;
  static double_vector broadcast(const double value) {
    return {_mm_set1_pd(value)};
  }
  static double_vector load_floats(const float* buffer) {
    int64_t pair;
    memcpy(&pair, buffer, sizeof(pair));
    const __m128i pair_bits = _mm_cvtsi64_si128(pair);
    const __m128 floats = _mm_castsi128_ps(pair_bits);
    return {_mm_cvtps_pd(floats)};
  }
  friend double_vector operator+(const double_vector first, const double_vector second) {
    return {_mm_add_pd(first.lanes_, second.lanes_)};
  }
  friend double_vector operator*(const double_vector first, const double_vector second) {
    return {_mm_mul_pd(first.lanes_, second.lanes_)};
  }
  void store(double* buffer) const {
    _mm_storeu_pd(buffer, lanes_);
  }
};

#elif defined(MCV2_SIMD_AVX2)

constexpr int32_t EVERY_THIRD_LANE_FROM_ZERO = 0x49;
constexpr int32_t EVERY_THIRD_LANE_FROM_ONE = 0x92;
constexpr int32_t EVERY_THIRD_LANE_FROM_TWO = 0x24;

constexpr int32_t SWAP_MIDDLE_QUARTERS = _MM_SHUFFLE(3, 1, 2, 0);

inline int32_t four_pixel_difference(const uint32_t first_pixel, const uint32_t second_pixel,
                                     const uint32_t third_pixel, const uint32_t fourth_pixel,
                                     const uint8_t* sample_bytes) {
  const __m128i pixels =
      _mm_setr_epi32((int32_t)first_pixel, (int32_t)second_pixel, (int32_t)third_pixel, (int32_t)fourth_pixel);
  const __m128i samples = _mm_loadu_si128((const __m128i*)sample_bytes);
  const __m128i absolute_differences = _mm_sad_epu8(pixels, samples);
  const __m128i upper_differences = _mm_srli_si128(absolute_differences, HALF_VECTOR_BYTES);
  return _mm_cvtsi128_si32(absolute_differences) + _mm_cvtsi128_si32(upper_differences);
}

struct int_vector {
  static constexpr int LANES = 8;
  static constexpr int32_t LANE_MASK = (1 << LANES) - 1;
  __m256i lanes_;
  static int_vector load(const int32_t* buffer) {
    return {_mm256_loadu_si256((const __m256i*)buffer)};
  }
  void store_bytes(int8_t* buffer) const {
    const __m128i lower_lanes = _mm256_castsi256_si128(lanes_);
    const __m128i upper_lanes = _mm256_extracti128_si256(lanes_, 1);
    const __m128i words = _mm_packs_epi32(lower_lanes, upper_lanes);
    const __m128i zero = _mm_setzero_si128();
    const __m128i bytes = _mm_packs_epi16(words, zero);
    _mm_storel_epi64((__m128i*)buffer, bytes);
  }
  void store(int32_t* buffer) const {
    _mm256_storeu_si256((__m256i*)buffer, lanes_);
  }
  static int_vector broadcast(const int32_t value) {
    return {_mm256_set1_epi32(value)};
  }
  static int_vector zero() {
    return {_mm256_setzero_si256()};
  }
  static int_vector lane_indices() {
    return {_mm256_setr_epi32(0, 1, 2, 3, 4, 5, 6, 7)};
  }
  static int_vector load_unsigned_bytes(const uint8_t* buffer) {
    const __m128i bytes = _mm_loadl_epi64((const __m128i*)buffer);
    return {_mm256_cvtepu8_epi32(bytes)};
  }
  friend int_vector operator+(const int_vector first, const int_vector second) {
    return {_mm256_add_epi32(first.lanes_, second.lanes_)};
  }
  friend int_vector operator-(const int_vector first, const int_vector second) {
    return {_mm256_sub_epi32(first.lanes_, second.lanes_)};
  }
  friend int_vector operator*(const int_vector first, const int_vector second) {
    return {_mm256_mullo_epi32(first.lanes_, second.lanes_)};
  }
  friend int_vector operator&(const int_vector first, const int_vector second) {
    return {_mm256_and_si256(first.lanes_, second.lanes_)};
  }
  friend int_vector operator|(const int_vector first, const int_vector second) {
    return {_mm256_or_si256(first.lanes_, second.lanes_)};
  }
  int_vector shift_left(const int32_t shift_distance) const {
    const __m128i distance = _mm_cvtsi32_si128(shift_distance & SHIFT_DISTANCE_MASK);
    return {_mm256_sll_epi32(lanes_, distance)};
  }
  int_vector shift_right(const int32_t shift_distance) const {
    const __m128i distance = _mm_cvtsi32_si128(shift_distance & SHIFT_DISTANCE_MASK);
    return {_mm256_sra_epi32(lanes_, distance)};
  }
  static int_vector minimum(const int_vector first, const int_vector second) {
    return {_mm256_min_epi32(first.lanes_, second.lanes_)};
  }
  static int_vector maximum(const int_vector first, const int_vector second) {
    return {_mm256_max_epi32(first.lanes_, second.lanes_)};
  }
  static int_vector absolute(const int_vector first) {
    return {_mm256_abs_epi32(first.lanes_)};
  }
  static int_vector less(const int_vector first, const int_vector second) {
    return {_mm256_cmpgt_epi32(second.lanes_, first.lanes_)};
  }
  int32_t sum() const {
    const __m128i lower_lanes = _mm256_castsi256_si128(lanes_);
    const __m128i upper_lanes = _mm256_extracti128_si256(lanes_, 1);
    const __m128i half_sums = _mm_add_epi32(lower_lanes, upper_lanes);
    const __m128i swapped_halves = _mm_shuffle_epi32(half_sums, SWAP_HALVES);
    const __m128i pair_sums = _mm_add_epi32(half_sums, swapped_halves);
    const __m128i swapped_pairs = _mm_shuffle_epi32(pair_sums, SWAP_PAIRS);
    const __m128i totals = _mm_add_epi32(pair_sums, swapped_pairs);
    return _mm_cvtsi128_si32(totals);
  }

  static void load_channels(const int32_t* buffer, int_vector& red_channels, int_vector& green_channels,
                            int_vector& blue_channels) {
    const __m256i first_vector = _mm256_loadu_si256((const __m256i*)buffer);
    const __m256i second_vector = _mm256_loadu_si256((const __m256i*)(buffer + LANES));
    const __m256i third_vector = _mm256_loadu_si256((const __m256i*)(buffer + 2 * LANES));

    const __m256i partial_red = _mm256_blend_epi32(first_vector, second_vector, EVERY_THIRD_LANE_FROM_ONE);
    const __m256i shuffled_red = _mm256_blend_epi32(partial_red, third_vector, EVERY_THIRD_LANE_FROM_TWO);

    const __m256i partial_green = _mm256_blend_epi32(first_vector, second_vector, EVERY_THIRD_LANE_FROM_TWO);
    const __m256i shuffled_green = _mm256_blend_epi32(partial_green, third_vector, EVERY_THIRD_LANE_FROM_ZERO);

    const __m256i partial_blue = _mm256_blend_epi32(first_vector, second_vector, EVERY_THIRD_LANE_FROM_ZERO);
    const __m256i shuffled_blue = _mm256_blend_epi32(partial_blue, third_vector, EVERY_THIRD_LANE_FROM_ONE);
    const __m256i red_order = _mm256_setr_epi32(0, 3, 6, 1, 4, 7, 2, 5);
    const __m256i green_order = _mm256_setr_epi32(1, 4, 7, 2, 5, 0, 3, 6);
    const __m256i blue_order = _mm256_setr_epi32(2, 5, 0, 3, 6, 1, 4, 7);
    red_channels.lanes_ = _mm256_permutevar8x32_epi32(shuffled_red, red_order);
    green_channels.lanes_ = _mm256_permutevar8x32_epi32(shuffled_green, green_order);
    blue_channels.lanes_ = _mm256_permutevar8x32_epi32(shuffled_blue, blue_order);
  }
  static void store_channels(int32_t* buffer, const int_vector red_channels, const int_vector green_channels,
                             const int_vector blue_channels) {
    const __m256i red_order = _mm256_setr_epi32(0, 3, 6, 1, 4, 7, 2, 5);
    const __m256i green_order = _mm256_setr_epi32(5, 0, 3, 6, 1, 4, 7, 2);
    const __m256i blue_order = _mm256_setr_epi32(2, 5, 0, 3, 6, 1, 4, 7);
    const __m256i shuffled_red = _mm256_permutevar8x32_epi32(red_channels.lanes_, red_order);
    const __m256i shuffled_green = _mm256_permutevar8x32_epi32(green_channels.lanes_, green_order);
    const __m256i shuffled_blue = _mm256_permutevar8x32_epi32(blue_channels.lanes_, blue_order);
    const __m256i first_red_green = _mm256_blend_epi32(shuffled_red, shuffled_green, EVERY_THIRD_LANE_FROM_ONE);
    const __m256i first_vector = _mm256_blend_epi32(first_red_green, shuffled_blue, EVERY_THIRD_LANE_FROM_TWO);
    _mm256_storeu_si256((__m256i*)buffer, first_vector);

    const __m256i second_blue_red = _mm256_blend_epi32(shuffled_blue, shuffled_red, EVERY_THIRD_LANE_FROM_ONE);
    const __m256i second_vector = _mm256_blend_epi32(second_blue_red, shuffled_green, EVERY_THIRD_LANE_FROM_TWO);
    _mm256_storeu_si256((__m256i*)(buffer + LANES), second_vector);

    const __m256i third_green_blue = _mm256_blend_epi32(shuffled_green, shuffled_blue, EVERY_THIRD_LANE_FROM_ONE);
    const __m256i third_vector = _mm256_blend_epi32(third_green_blue, shuffled_red, EVERY_THIRD_LANE_FROM_TWO);
    _mm256_storeu_si256((__m256i*)(buffer + 2 * LANES), third_vector);
  }
  static int_vector bits(const int8_t* buffer, const int32_t index) {
    const __m256i byte = _mm256_set1_epi32(buffer[index >> BYTE_INDEX_SHIFT] & LANE_MASK);
    const __m256i lane_bits = _mm256_setr_epi32(1, 2, 4, 8, 16, 32, 64, 128);
    const __m256i set_bits = _mm256_and_si256(byte, lane_bits);
    return {_mm256_cmpeq_epi32(set_bits, lane_bits)};
  }

  static int_vector evens(const int_vector first, const int_vector second) {
    const __m256 first_floats = _mm256_castsi256_ps(first.lanes_);
    const __m256 second_floats = _mm256_castsi256_ps(second.lanes_);
    const __m256 pairs = _mm256_shuffle_ps(first_floats, second_floats, EVEN_LANES);
    const __m256i pair_integers = _mm256_castps_si256(pairs);
    return {_mm256_permute4x64_epi64(pair_integers, SWAP_MIDDLE_QUARTERS)};
  }
  static int_vector odds(const int_vector first, const int_vector second) {
    const __m256 first_floats = _mm256_castsi256_ps(first.lanes_);
    const __m256 second_floats = _mm256_castsi256_ps(second.lanes_);
    const __m256 pairs = _mm256_shuffle_ps(first_floats, second_floats, ODD_LANES);
    const __m256i pair_integers = _mm256_castps_si256(pairs);
    return {_mm256_permute4x64_epi64(pair_integers, SWAP_MIDDLE_QUARTERS)};
  }
};

struct float_vector {
  __m256 lanes_;
  static float_vector broadcast(const float value) {
    return {_mm256_set1_ps(value)};
  }
  static float_vector from_integers(const int_vector first) {
    return {_mm256_cvtepi32_ps(first.lanes_)};
  }
  friend float_vector operator+(const float_vector first, const float_vector second) {
    return {_mm256_add_ps(first.lanes_, second.lanes_)};
  }
  friend float_vector operator-(const float_vector first, const float_vector second) {
    return {_mm256_sub_ps(first.lanes_, second.lanes_)};
  }
  friend float_vector operator*(const float_vector first, const float_vector second) {
    return {_mm256_mul_ps(first.lanes_, second.lanes_)};
  }
  void store(float* buffer) const {
    _mm256_storeu_ps(buffer, lanes_);
  }
};

struct double_vector {
  static constexpr int LANES = 4;
  __m256d lanes_;
  static double_vector broadcast(const double value) {
    return {_mm256_set1_pd(value)};
  }
  static double_vector load_floats(const float* buffer) {
    const __m128 floats = _mm_loadu_ps(buffer);
    return {_mm256_cvtps_pd(floats)};
  }
  friend double_vector operator+(const double_vector first, const double_vector second) {
    return {_mm256_add_pd(first.lanes_, second.lanes_)};
  }
  friend double_vector operator*(const double_vector first, const double_vector second) {
    return {_mm256_mul_pd(first.lanes_, second.lanes_)};
  }
  void store(double* buffer) const {
    _mm256_storeu_pd(buffer, lanes_);
  }
};

#elif defined(MCV2_SIMD_AVX512)

inline int32_t four_pixel_difference(const uint32_t first_pixel, const uint32_t second_pixel,
                                     const uint32_t third_pixel, const uint32_t fourth_pixel,
                                     const uint8_t* sample_bytes) {
  const __m128i pixels =
      _mm_setr_epi32((int32_t)first_pixel, (int32_t)second_pixel, (int32_t)third_pixel, (int32_t)fourth_pixel);
  const __m128i samples = _mm_loadu_si128((const __m128i*)sample_bytes);
  const __m128i absolute_differences = _mm_sad_epu8(pixels, samples);
  const __m128i upper_differences = _mm_srli_si128(absolute_differences, HALF_VECTOR_BYTES);
  return _mm_cvtsi128_si32(absolute_differences) + _mm_cvtsi128_si32(upper_differences);
}

struct int_vector {
  static constexpr int LANES = 16;
  __m512i lanes_;
  static int_vector load(const int32_t* buffer) {
    return {_mm512_loadu_si512(buffer)};
  }
  void store_bytes(int8_t* buffer) const {
    const __m128i bytes = _mm512_cvtepi32_epi8(lanes_);
    _mm_storeu_si128((__m128i*)buffer, bytes);
  }
  void store(int32_t* buffer) const {
    _mm512_storeu_si512(buffer, lanes_);
  }
  static int_vector broadcast(const int32_t value) {
    return {_mm512_set1_epi32(value)};
  }
  static int_vector zero() {
    return {_mm512_setzero_si512()};
  }
  static int_vector lane_indices() {
    return {_mm512_set_epi32(15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0)};
  }
  static int_vector load_unsigned_bytes(const uint8_t* buffer) {
    const __m128i bytes = _mm_loadu_si128((const __m128i*)buffer);
    return {_mm512_cvtepu8_epi32(bytes)};
  }
  friend int_vector operator+(const int_vector first, const int_vector second) {
    return {_mm512_add_epi32(first.lanes_, second.lanes_)};
  }
  friend int_vector operator-(const int_vector first, const int_vector second) {
    return {_mm512_sub_epi32(first.lanes_, second.lanes_)};
  }
  friend int_vector operator*(const int_vector first, const int_vector second) {
    return {_mm512_mullo_epi32(first.lanes_, second.lanes_)};
  }
  friend int_vector operator&(const int_vector first, const int_vector second) {
    return {_mm512_and_si512(first.lanes_, second.lanes_)};
  }
  friend int_vector operator|(const int_vector first, const int_vector second) {
    return {_mm512_or_si512(first.lanes_, second.lanes_)};
  }
  int_vector shift_left(const int32_t shift_distance) const {
    const __m128i distance = _mm_cvtsi32_si128(shift_distance & SHIFT_DISTANCE_MASK);
    return {_mm512_sll_epi32(lanes_, distance)};
  }
  int_vector shift_right(const int32_t shift_distance) const {
    const __m128i distance = _mm_cvtsi32_si128(shift_distance & SHIFT_DISTANCE_MASK);
    return {_mm512_sra_epi32(lanes_, distance)};
  }
  static int_vector minimum(const int_vector first, const int_vector second) {
    return {_mm512_min_epi32(first.lanes_, second.lanes_)};
  }
  static int_vector maximum(const int_vector first, const int_vector second) {
    return {_mm512_max_epi32(first.lanes_, second.lanes_)};
  }
  static int_vector absolute(const int_vector first) {
    return {_mm512_abs_epi32(first.lanes_)};
  }
  static int_vector less(const int_vector first, const int_vector second) {
    const __mmask16 less_mask = _mm512_cmplt_epi32_mask(first.lanes_, second.lanes_);
    return {_mm512_movm_epi32(less_mask)};
  }
  int32_t sum() const {
    return _mm512_reduce_add_epi32(lanes_);
  }

  static void load_channels(const int32_t* buffer, int_vector& red_channels, int_vector& green_channels,
                            int_vector& blue_channels) {
    const __m512i first_vector = _mm512_loadu_si512(buffer);
    const __m512i second_vector = _mm512_loadu_si512(buffer + LANES);
    const __m512i third_vector = _mm512_loadu_si512(buffer + 2 * LANES);
    red_channels.lanes_ = gather(first_vector, second_vector, third_vector, 0);
    green_channels.lanes_ = gather(first_vector, second_vector, third_vector, 1);
    blue_channels.lanes_ = gather(first_vector, second_vector, third_vector, 2);
  }
  static void store_channels(int32_t* buffer, const int_vector red_channels, const int_vector green_channels,
                             const int_vector blue_channels) {
    const __m512i first_vector = scatter(red_channels.lanes_, green_channels.lanes_, blue_channels.lanes_, 0);
    _mm512_storeu_si512(buffer, first_vector);

    const __m512i second_vector = scatter(red_channels.lanes_, green_channels.lanes_, blue_channels.lanes_, 1);
    _mm512_storeu_si512(buffer + LANES, second_vector);

    const __m512i third_vector = scatter(red_channels.lanes_, green_channels.lanes_, blue_channels.lanes_, 2);
    _mm512_storeu_si512(buffer + 2 * LANES, third_vector);
  }

  static int_vector bits(const int8_t* buffer, const int32_t index) {
    const int32_t array_offset = index >> BYTE_INDEX_SHIFT;
    const uint32_t word =
        (uint32_t)(uint8_t)buffer[array_offset] | ((uint32_t)(uint8_t)buffer[array_offset + 1] << CHAR_BIT);
    return {_mm512_movm_epi32((__mmask16)word)};
  }
  static int_vector evens(const int_vector first, const int_vector second) {
    const __m512i index = _mm512_set_epi32(30, 28, 26, 24, 22, 20, 18, 16, 14, 12, 10, 8, 6, 4, 2, 0);
    return {_mm512_permutex2var_epi32(first.lanes_, index, second.lanes_)};
  }
  static int_vector odds(const int_vector first, const int_vector second) {
    const __m512i index = _mm512_set_epi32(31, 29, 27, 25, 23, 21, 19, 17, 15, 13, 11, 9, 7, 5, 3, 1);
    return {_mm512_permutex2var_epi32(first.lanes_, index, second.lanes_)};
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

  static __m512i gather(const __m512i first_vector, const __m512i second_vector, const __m512i third_vector,
                        const int32_t channel) {
    const __m512i first_indexes = _mm512_load_si512(GATHER_FIRST[channel]);
    const __m512i low = _mm512_permutex2var_epi32(first_vector, first_indexes, second_vector);
    const __m512i second_indexes = _mm512_load_si512(GATHER_SECOND[channel]);
    return _mm512_permutex2var_epi32(low, second_indexes, third_vector);
  }
  static __m512i scatter(const __m512i red, __m512i green, __m512i blue, const int32_t output_index) {
    const __m512i first_indexes = _mm512_load_si512(SCATTER_FIRST[output_index]);
    const __m512i red_green = _mm512_permutex2var_epi32(red, first_indexes, green);
    const __m512i second_indexes = _mm512_load_si512(SCATTER_SECOND[output_index]);
    return _mm512_permutex2var_epi32(red_green, second_indexes, blue);
  }
};

struct float_vector {
  __m512 lanes_;
  static float_vector broadcast(const float value) {
    return {_mm512_set1_ps(value)};
  }
  static float_vector from_integers(const int_vector first) {
    return {_mm512_cvtepi32_ps(first.lanes_)};
  }
  friend float_vector operator+(const float_vector first, const float_vector second) {
    return {_mm512_add_ps(first.lanes_, second.lanes_)};
  }
  friend float_vector operator-(const float_vector first, const float_vector second) {
    return {_mm512_sub_ps(first.lanes_, second.lanes_)};
  }
  friend float_vector operator*(const float_vector first, const float_vector second) {
    return {_mm512_mul_ps(first.lanes_, second.lanes_)};
  }
  void store(float* buffer) const {
    _mm512_storeu_ps(buffer, lanes_);
  }
};

struct double_vector {
  static constexpr int LANES = 8;
  __m512d lanes_;
  static double_vector broadcast(const double value) {
    return {_mm512_set1_pd(value)};
  }
  static double_vector load_floats(const float* buffer) {
    const __m256 floats = _mm256_loadu_ps(buffer);
    return {_mm512_cvtps_pd(floats)};
  }
  friend double_vector operator+(const double_vector first, const double_vector second) {
    return {_mm512_add_pd(first.lanes_, second.lanes_)};
  }
  friend double_vector operator*(const double_vector first, const double_vector second) {
    return {_mm512_mul_pd(first.lanes_, second.lanes_)};
  }
  void store(double* buffer) const {
    _mm512_storeu_pd(buffer, lanes_);
  }
};

#elif defined(MCV2_SIMD_NEON)

inline int32_t four_pixel_difference(const uint32_t first_pixel, const uint32_t second_pixel,
                                     const uint32_t third_pixel, const uint32_t fourth_pixel,
                                     const uint8_t* sample_bytes) {
  const uint32_t words[SAMPLE_GRID] = {first_pixel, second_pixel, third_pixel, fourth_pixel};
  const uint32x4_t pixel_words = vld1q_u32(words);
  const uint8x16_t pixels = vreinterpretq_u8_u32(pixel_words);
  const uint8x16_t samples = vld1q_u8(sample_bytes);
  const uint8x16_t absolute_differences = vabdq_u8(pixels, samples);
  return (int32_t)vaddlvq_u8(absolute_differences);
}

struct int_vector {
  static constexpr int LANES = 4;
  static constexpr int32_t LANE_MASK = (1 << LANES) - 1;
  int32x4_t lanes_;
  static int_vector load(const int32_t* buffer) {
    return {vld1q_s32(buffer)};
  }
  void store_bytes(int8_t* buffer) const {
    const int16x4_t narrowed = vmovn_s32(lanes_);
    const int16x4_t zero = vdup_n_s16(0);
    const int16x8_t words = vcombine_s16(narrowed, zero);
    const int8x8_t bytes = vmovn_s16(words);
    const int32x2_t packed_words = vreinterpret_s32_s8(bytes);
    vst1_lane_s32((int32_t*)buffer, packed_words, 0);
  }
  void store(int32_t* buffer) const {
    vst1q_s32(buffer, lanes_);
  }
  static int_vector broadcast(const int32_t value) {
    return {vdupq_n_s32(value)};
  }
  static int_vector zero() {
    return {vdupq_n_s32(0)};
  }
  static int_vector lane_indices() {
    static const int32_t indices[LANES] = {0, 1, 2, 3};
    return {vld1q_s32(indices)};
  }
  static int_vector load_unsigned_bytes(const uint8_t* buffer) {
    uint32_t word;
    memcpy(&word, buffer, sizeof(word));
    const uint32x2_t repeated_word = vdup_n_u32(word);
    const uint8x8_t bytes = vreinterpret_u8_u32(repeated_word);
    const uint16x8_t words = vmovl_u8(bytes);
    const uint16x4_t low_words = vget_low_u16(words);
    const uint32x4_t widened = vmovl_u16(low_words);
    return {vreinterpretq_s32_u32(widened)};
  }
  friend int_vector operator+(const int_vector first, const int_vector second) {
    return {vaddq_s32(first.lanes_, second.lanes_)};
  }
  friend int_vector operator-(const int_vector first, const int_vector second) {
    return {vsubq_s32(first.lanes_, second.lanes_)};
  }
  friend int_vector operator*(const int_vector first, const int_vector second) {
    return {vmulq_s32(first.lanes_, second.lanes_)};
  }
  friend int_vector operator&(const int_vector first, const int_vector second) {
    return {vandq_s32(first.lanes_, second.lanes_)};
  }
  friend int_vector operator|(const int_vector first, const int_vector second) {
    return {vorrq_s32(first.lanes_, second.lanes_)};
  }
  int_vector shift_left(const int32_t shift_distance) const {
    const int32x4_t distance = vdupq_n_s32(shift_distance & SHIFT_DISTANCE_MASK);
    return {vshlq_s32(lanes_, distance)};
  }
  int_vector shift_right(const int32_t shift_distance) const {
    const int32x4_t negative_distance = vdupq_n_s32(-(shift_distance & SHIFT_DISTANCE_MASK));
    return {vshlq_s32(lanes_, negative_distance)};
  }
  static int_vector minimum(const int_vector first, const int_vector second) {
    return {vminq_s32(first.lanes_, second.lanes_)};
  }
  static int_vector maximum(const int_vector first, const int_vector second) {
    return {vmaxq_s32(first.lanes_, second.lanes_)};
  }
  static int_vector absolute(const int_vector first) {
    return {vabsq_s32(first.lanes_)};
  }
  static int_vector less(const int_vector first, const int_vector second) {
    const uint32x4_t less_mask = vcltq_s32(first.lanes_, second.lanes_);
    return {vreinterpretq_s32_u32(less_mask)};
  }
  int32_t sum() const {
    return vaddvq_s32(lanes_);
  }
  static void load_channels(const int32_t* buffer, int_vector& red_channels, int_vector& green_channels,
                            int_vector& blue_channels) {
    const int32x4x3_t channel_vectors = vld3q_s32(buffer);
    red_channels.lanes_ = channel_vectors.val[0];
    green_channels.lanes_ = channel_vectors.val[1];
    blue_channels.lanes_ = channel_vectors.val[2];
  }
  static void store_channels(int32_t* buffer, const int_vector red_channels, const int_vector green_channels,
                             const int_vector blue_channels) {
    int32x4x3_t channel_vectors;
    channel_vectors.val[0] = red_channels.lanes_;
    channel_vectors.val[1] = green_channels.lanes_;
    channel_vectors.val[2] = blue_channels.lanes_;
    vst3q_s32(buffer, channel_vectors);
  }
  static int_vector bits(const int8_t* buffer, const int32_t index) {
    static const int32_t lane_bits[LANES] = {1, 2, 4, 8};
    const int32x4_t byte = vdupq_n_s32((buffer[index >> BYTE_INDEX_SHIFT] >> (index & BIT_INDEX_MASK)) & LANE_MASK);
    const int32x4_t lane_bit_values = vld1q_s32(lane_bits);
    const uint32x4_t set_bits = vtstq_s32(byte, lane_bit_values);
    return {vreinterpretq_s32_u32(set_bits)};
  }
  static int_vector evens(const int_vector first, const int_vector second) {
    return {vuzp1q_s32(first.lanes_, second.lanes_)};
  }
  static int_vector odds(const int_vector first, const int_vector second) {
    return {vuzp2q_s32(first.lanes_, second.lanes_)};
  }
};

struct float_vector {
  float32x4_t lanes_;
  static float_vector broadcast(const float value) {
    return {vdupq_n_f32(value)};
  }
  static float_vector from_integers(const int_vector first) {
    return {vcvtq_f32_s32(first.lanes_)};
  }
  friend float_vector operator+(const float_vector first, const float_vector second) {
    return {vaddq_f32(first.lanes_, second.lanes_)};
  }
  friend float_vector operator-(const float_vector first, const float_vector second) {
    return {vsubq_f32(first.lanes_, second.lanes_)};
  }
  friend float_vector operator*(const float_vector first, const float_vector second) {
    return {vmulq_f32(first.lanes_, second.lanes_)};
  }
  void store(float* buffer) const {
    vst1q_f32(buffer, lanes_);
  }
};

struct double_vector {
  static constexpr int LANES = 2;
  float64x2_t lanes_;
  static double_vector broadcast(const double value) {
    return {vdupq_n_f64(value)};
  }
  static double_vector load_floats(const float* buffer) {
    const float32x2_t floats = vld1_f32(buffer);
    return {vcvt_f64_f32(floats)};
  }
  friend double_vector operator+(const double_vector first, const double_vector second) {
    return {vaddq_f64(first.lanes_, second.lanes_)};
  }
  friend double_vector operator*(const double_vector first, const double_vector second) {
    return {vmulq_f64(first.lanes_, second.lanes_)};
  }
  void store(double* buffer) const {
    vst1q_f64(buffer, lanes_);
  }
};

#elif defined(MCV2_SIMD_SVE256) || defined(MCV2_SIMD_SVE512)

// Fixed-length SVE code requires the exact vector length selected at dispatch.
#if defined(MCV2_SIMD_SVE256)
#define MCV2_SVE_BITS 256
#else
#define MCV2_SVE_BITS 512
#endif
typedef svint32_t sve_int_lanes __attribute__((arm_sve_vector_bits(MCV2_SVE_BITS)));
typedef svuint64_t sve_unsigned_long_lanes __attribute__((arm_sve_vector_bits(MCV2_SVE_BITS)));
typedef svfloat32_t sve_float_lanes __attribute__((arm_sve_vector_bits(MCV2_SVE_BITS)));
typedef svfloat64_t sve_double_lanes __attribute__((arm_sve_vector_bits(MCV2_SVE_BITS)));

inline int32_t four_pixel_difference(const uint32_t first_pixel, const uint32_t second_pixel,
                                     const uint32_t third_pixel, const uint32_t fourth_pixel,
                                     const uint8_t* sample_bytes) {
  const uint32_t words[SAMPLE_GRID] = {first_pixel, second_pixel, third_pixel, fourth_pixel};
  const uint32x4_t pixel_words = vld1q_u32(words);
  const uint8x16_t pixels = vreinterpretq_u8_u32(pixel_words);
  const uint8x16_t samples = vld1q_u8(sample_bytes);
  const uint8x16_t absolute_differences = vabdq_u8(pixels, samples);
  return (int32_t)vaddlvq_u8(absolute_differences);
}

struct int_vector {
  static constexpr int LANES = MCV2_SVE_BITS / 32;
  sve_int_lanes lanes_;
  static int_vector load(const int32_t* buffer) {
    return {svld1_s32(svptrue_b32(), buffer)};
  }
  void store_bytes(int8_t* buffer) const {
    svst1b_s32(svptrue_b32(), buffer, lanes_);
  }
  void store(int32_t* buffer) const {
    svst1_s32(svptrue_b32(), buffer, lanes_);
  }
  static int_vector broadcast(const int32_t value) {
    return {svdup_n_s32(value)};
  }
  static int_vector zero() {
    return {svdup_n_s32(0)};
  }
  static int_vector lane_indices() {
    return {svindex_s32(0, 1)};
  }
  static int_vector load_unsigned_bytes(const uint8_t* buffer) {
    return {svreinterpret_s32_u32(svld1ub_u32(svptrue_b32(), buffer))};
  }
  friend int_vector operator+(const int_vector first, const int_vector second) {
    return {svadd_s32_x(svptrue_b32(), first.lanes_, second.lanes_)};
  }
  friend int_vector operator-(const int_vector first, const int_vector second) {
    return {svsub_s32_x(svptrue_b32(), first.lanes_, second.lanes_)};
  }
  friend int_vector operator*(const int_vector first, const int_vector second) {
    return {svmul_s32_x(svptrue_b32(), first.lanes_, second.lanes_)};
  }
  friend int_vector operator&(const int_vector first, const int_vector second) {
    return {svand_s32_x(svptrue_b32(), first.lanes_, second.lanes_)};
  }
  friend int_vector operator|(const int_vector first, const int_vector second) {
    return {svorr_s32_x(svptrue_b32(), first.lanes_, second.lanes_)};
  }
  int_vector shift_left(const int32_t shift_distance) const {
    return {svlsl_n_s32_x(svptrue_b32(), lanes_, (uint32_t)(shift_distance & SHIFT_DISTANCE_MASK))};
  }
  int_vector shift_right(const int32_t shift_distance) const {
    return {svasr_n_s32_x(svptrue_b32(), lanes_, (uint32_t)(shift_distance & SHIFT_DISTANCE_MASK))};
  }
  static int_vector minimum(const int_vector first, const int_vector second) {
    return {svmin_s32_x(svptrue_b32(), first.lanes_, second.lanes_)};
  }
  static int_vector maximum(const int_vector first, const int_vector second) {
    return {svmax_s32_x(svptrue_b32(), first.lanes_, second.lanes_)};
  }
  static int_vector absolute(const int_vector first) {
    return {svabs_s32_x(svptrue_b32(), first.lanes_)};
  }
  static int_vector less(const int_vector first, const int_vector second) {
    return {svsel_s32(svcmplt_s32(svptrue_b32(), first.lanes_, second.lanes_), svdup_n_s32(-1), svdup_n_s32(0))};
  }

  int32_t sum() const {
    return (int32_t)(uint32_t)(uint64_t)svaddv_s32(svptrue_b32(), lanes_);
  }
  static void load_channels(const int32_t* buffer, int_vector& red_channels, int_vector& green_channels,
                            int_vector& blue_channels) {
    const svint32x3_t channel_vectors = svld3_s32(svptrue_b32(), buffer);
    red_channels.lanes_ = svget3_s32(channel_vectors, 0);
    green_channels.lanes_ = svget3_s32(channel_vectors, 1);
    blue_channels.lanes_ = svget3_s32(channel_vectors, 2);
  }
  static void store_channels(int32_t* buffer, const int_vector red_channels, const int_vector green_channels,
                             const int_vector blue_channels) {
    svst3_s32(svptrue_b32(), buffer, svcreate3_s32(red_channels.lanes_, green_channels.lanes_, blue_channels.lanes_));
  }

  static int_vector bits(const int8_t* buffer, const int32_t index) {
    const int32_t array_offset = index >> BYTE_INDEX_SHIFT;
    uint32_t word = (uint8_t)buffer[array_offset];
    if (LANES > CHAR_BIT) {
      word |= (uint32_t)(uint8_t)buffer[array_offset + 1] << CHAR_BIT;
    }
    const svuint32_t bit_positions = svindex_u32(0, 1);
    const svuint32_t lane_bit =
        svand_n_u32_x(svptrue_b32(), svlsr_u32_x(svptrue_b32(), svdup_n_u32(word), bit_positions), 1);
    return {svsel_s32(svcmpne_n_u32(svptrue_b32(), lane_bit, 0), svdup_n_s32(-1), svdup_n_s32(0))};
  }
  static int_vector evens(const int_vector first, const int_vector second) {
    return {svuzp1_s32(first.lanes_, second.lanes_)};
  }
  static int_vector odds(const int_vector first, const int_vector second) {
    return {svuzp2_s32(first.lanes_, second.lanes_)};
  }
};

struct float_vector {
  sve_float_lanes lanes_;
  static float_vector broadcast(const float value) {
    return {svdup_n_f32(value)};
  }
  static float_vector from_integers(const int_vector first) {
    return {svcvt_f32_s32_x(svptrue_b32(), first.lanes_)};
  }
  friend float_vector operator+(const float_vector first, const float_vector second) {
    return {svadd_f32_x(svptrue_b32(), first.lanes_, second.lanes_)};
  }
  friend float_vector operator-(const float_vector first, const float_vector second) {
    return {svsub_f32_x(svptrue_b32(), first.lanes_, second.lanes_)};
  }
  friend float_vector operator*(const float_vector first, const float_vector second) {
    return {svmul_f32_x(svptrue_b32(), first.lanes_, second.lanes_)};
  }
  void store(float* buffer) const {
    svst1_f32(svptrue_b32(), buffer, lanes_);
  }
};

struct double_vector {
  static constexpr int LANES = MCV2_SVE_BITS / 64;
  sve_double_lanes lanes_;
  static double_vector broadcast(const double value) {
    return {svdup_n_f64(value)};
  }

  static double_vector load_floats(const float* buffer) {
    const sve_unsigned_long_lanes words = svld1uw_u64(svptrue_b64(), (const uint32_t*)buffer);
    return {svcvt_f64_f32_x(svptrue_b64(), svreinterpret_f32_u64(words))};
  }
  friend double_vector operator+(const double_vector first, const double_vector second) {
    return {svadd_f64_x(svptrue_b64(), first.lanes_, second.lanes_)};
  }
  friend double_vector operator*(const double_vector first, const double_vector second) {
    return {svmul_f64_x(svptrue_b64(), first.lanes_, second.lanes_)};
  }
  void store(double* buffer) const {
    svst1_f64(svptrue_b64(), buffer, lanes_);
  }
};

#endif

}
}

namespace mcv2 {
namespace {

constexpr int32_t CHANNELS = 3;
constexpr int32_t MAXIMUM_CHANNEL = 255;

constexpr int32_t RED_SHIFT = 16;
constexpr int32_t GREEN_SHIFT = 8;
constexpr int32_t BLUE_SHIFT = 0;
constexpr int32_t CHANNEL_MASK = 0xFF;

constexpr int32_t GRID = 4;
constexpr int32_t ROOT_SIZE = 32;
constexpr int32_t BLOCK_SIZES = 3;
constexpr int32_t SMALLEST_BLOCK_SHIFT = 3;
constexpr int32_t SMALLEST_BLOCK_SIZE = 1 << SMALLEST_BLOCK_SHIFT;

constexpr int32_t MOTION_BYTES = 2;
constexpr int32_t SELECTORS_AT = 6;

constexpr double DISTORTION_SCALE = 96.0;

constexpr int32_t NIBBLE_BITS = 4;
constexpr int32_t NIBBLE_MASK = 15;
constexpr int32_t NIBBLE_SIGN = 1 << (NIBBLE_BITS - 1);
constexpr int32_t NIBBLE_RANGE = 1 << NIBBLE_BITS;
constexpr int32_t NIBBLES_PER_BYTE = CHAR_BIT / NIBBLE_BITS;

constexpr int32_t UNROLLED_VECTORS = 4;

constexpr int32_t ENDPOINTS = 2;
constexpr int32_t ENDPOINT_CHANNELS = ENDPOINTS * CHANNELS;

constexpr int32_t CLUSTER_STEP = 2;
constexpr int32_t SMALLEST_STEPPED_SIZE = 16;

constexpr int32_t CLUSTER_ITERATIONS = 2;

constexpr int32_t DIRECTION_COUNT = 4;
constexpr int32_t DIRECTIONS[DIRECTION_COUNT][2] = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

constexpr int32_t HORIZONTAL_MOTION_SHIFT = 16;
constexpr int32_t VERTICAL_MOTION_MASK = 0xFFFF;

constexpr int32_t KEY_HORIZONTAL_SHIFT = 32;

constexpr int32_t LUMA_GREEN_WEIGHT = 2;
constexpr float LUMA_NORMALIZATION = 0.25f;

inline int32_t logarithm_base_two(const int32_t value) {
  return __builtin_ctz((unsigned)value);
}
inline int32_t size_index(const int32_t size) {
  return logarithm_base_two(size) - SMALLEST_BLOCK_SHIFT;
}
inline int32_t minimum_int(const int32_t first, const int32_t second) {
  return first < second ? first : second;
}
inline int32_t maximum_int(const int32_t first, const int32_t second) {
  return first > second ? first : second;
}
inline int32_t clamp_int(const int32_t value, const int32_t low, const int32_t high) {
  return minimum_int(maximum_int(value, low), high);
}

struct interpolation_axis {
  int32_t lower_[ROOT_SIZE];
  int32_t upper_[ROOT_SIZE];
  int32_t weight_[ROOT_SIZE];
};

struct interpolation_axes {
  interpolation_axis axes_[BLOCK_SIZES];
};

constexpr interpolation_axes make_axes() {
  interpolation_axes tables{};
  for (int32_t size_index = 0; size_index < BLOCK_SIZES; size_index++) {
    const int32_t size = SMALLEST_BLOCK_SIZE << size_index;
    const int32_t span = 2 * size;
    for (int32_t pixel_index = 0; pixel_index < size; pixel_index++) {
      int32_t position = (2 * pixel_index + 1) * GRID - size;
      position = position < 0 ? 0 : position;
      position = position > (GRID - 1) * span ? (GRID - 1) * span : position;
      const int32_t lower = position / span;
      tables.axes_[size_index].lower_[pixel_index] = lower;
      tables.axes_[size_index].upper_[pixel_index] = lower + 1 < GRID - 1 ? lower + 1 : GRID - 1;
      tables.axes_[size_index].weight_[pixel_index] = position - lower * span;
    }
  }
  return tables;
}

constexpr interpolation_axes AXES = make_axes();

inline int32_t rounding_shift(const int32_t size) {
  return 2 * (logarithm_base_two(size) + 1);
}

inline int_vector round(const int_vector value, const int32_t shift) {
  return int_vector::minimum(
      int_vector::maximum((value + int_vector::broadcast(1 << (shift - 1))).shift_right(shift), int_vector::zero()),
      int_vector::broadcast(MAXIMUM_CHANNEL));
}

struct distortion_measure {
  const int32_t* source_;
  double rate_;
  double limit_;
  int64_t distortion_;
};

inline void add_row_distortion(distortion_measure& measure, const int32_t* output, const int32_t source_position,
                               const int32_t pixels) {
  int_vector sum = int_vector::zero();
  for (int32_t pixel_index = 0; pixel_index < pixels; pixel_index += int_vector::LANES) {
    int_vector source_red, source_green, source_blue, reconstructed_red, reconstructed_green, reconstructed_blue;
    int_vector::load_channels(measure.source_ + source_position + pixel_index * CHANNELS, source_red, source_green,
                              source_blue);
    int_vector::load_channels(output + source_position + pixel_index * CHANNELS, reconstructed_red, reconstructed_green,
                              reconstructed_blue);
    const int_vector red_difference = source_red - reconstructed_red;
    const int_vector green_difference = source_green - reconstructed_green;
    const int_vector blue_difference = source_blue - reconstructed_blue;
    const int_vector luma = red_difference + green_difference.shift_left(1) + blue_difference;
    const int_vector red_blue_difference = red_difference - blue_difference;
    const int_vector green_difference_plane = green_difference.shift_left(1) - red_difference - blue_difference;
    sum = sum + (luma * luma + red_blue_difference * red_blue_difference).shift_left(2) +
          green_difference_plane * green_difference_plane;
  }
  measure.distortion_ += sum.sum();
}

inline bool is_within_limit(const distortion_measure& measure) {
  return (double)measure.distortion_ / DISTORTION_SCALE + measure.rate_ < measure.limit_;
}

inline void interpolate_horizontally(const int32_t* nodes, const int32_t size, int32_t* rows) {
  const interpolation_axis& axis = AXES.axes_[size_index(size)];
  const int32_t span = 2 * size;
  for (int32_t node_row = 0; node_row < GRID; node_row++) {
    const int32_t array_offset = node_row * size;
    for (int32_t pixel_column = 0; pixel_column < size; pixel_column++) {
      const int32_t weight = axis.weight_[pixel_column];
      rows[array_offset + pixel_column] =
          wrapping_addition(wrapping_multiplication(nodes[node_row * GRID + axis.lower_[pixel_column]], span - weight),
                            wrapping_multiplication(nodes[node_row * GRID + axis.upper_[pixel_column]], weight));
    }
  }
}

inline void interpolate_vertically(const int32_t* rows, const int32_t size, const int32_t pixel_row, int32_t* line) {
  const interpolation_axis& axis = AXES.axes_[size_index(size)];
  const int32_t top = axis.lower_[pixel_row] * size;
  const int32_t bottom = axis.upper_[pixel_row] * size;
  const int_vector upper_weight = int_vector::broadcast(axis.weight_[pixel_row]);
  const int_vector lower_weight = int_vector::broadcast(2 * size - axis.weight_[pixel_row]);
  for (int32_t pixel_column = 0; pixel_column < size; pixel_column += int_vector::LANES) {
    (int_vector::load(rows + top + pixel_column) * lower_weight +
     int_vector::load(rows + bottom + pixel_column) * upper_weight)
        .store(line + pixel_column);
  }
}

int64_t predicted(const int32_t* prediction, const int32_t size, int32_t* output, distortion_measure measure) {
  const int32_t row_length = size * CHANNELS;
  for (int32_t row_index = 0; row_index < size; row_index++) {
    const int32_t source_position = row_index * row_length;
    for (int32_t offset = source_position; offset < source_position + row_length; offset += int_vector::LANES) {
      int_vector::load(prediction + offset).store(output + offset);
    }
    add_row_distortion(measure, output, source_position, size);

    if (!is_within_limit(measure)) {
      return -1;
    }
  }
  return measure.distortion_;
}

int64_t solid(const int32_t color, const int32_t size, int32_t* output, distortion_measure measure) {
  const int_vector red_values = int_vector::broadcast((color >> RED_SHIFT) & CHANNEL_MASK);
  const int_vector green_values = int_vector::broadcast((color >> GREEN_SHIFT) & CHANNEL_MASK);
  const int_vector blue_values = int_vector::broadcast((color >> BLUE_SHIFT) & CHANNEL_MASK);
  for (int32_t vertical_position = 0; vertical_position < size; vertical_position++) {
    const int32_t source_position = vertical_position * size * CHANNELS;
    for (int32_t horizontal_position = 0; horizontal_position < size; horizontal_position += int_vector::LANES) {
      int_vector::store_channels(output + source_position + horizontal_position * CHANNELS, red_values, green_values,
                                 blue_values);
    }
    add_row_distortion(measure, output, source_position, size);

    if (!is_within_limit(measure)) {
      return -1;
    }
  }
  return measure.distortion_;
}

int64_t palette(const int8_t* record, const int32_t size, int32_t* output, distortion_measure measure) {
  const int_vector first_red = int_vector::broadcast((uint8_t)record[0]);
  const int_vector first_green = int_vector::broadcast((uint8_t)record[1]);
  const int_vector first_blue = int_vector::broadcast((uint8_t)record[2]);
  const int_vector red_delta = int_vector::broadcast((uint8_t)record[3] - (uint8_t)record[0]);
  const int_vector green_delta = int_vector::broadcast((uint8_t)record[4] - (uint8_t)record[1]);
  const int_vector blue_delta = int_vector::broadcast((uint8_t)record[5] - (uint8_t)record[2]);
  for (int32_t row_index = 0; row_index < size; row_index++) {
    for (int32_t column = 0; column < size; column += int_vector::LANES) {
      const int32_t pixel = row_index * size + column;
      const int_vector bit = int_vector::bits(record + SELECTORS_AT, pixel);
      int_vector::store_channels(output + pixel * CHANNELS, first_red + (red_delta & bit),
                                 first_green + (green_delta & bit), first_blue + (blue_delta & bit));
    }
    add_row_distortion(measure, output, row_index * size * CHANNELS, size);

    if (!is_within_limit(measure)) {
      return -1;
    }
  }
  return measure.distortion_;
}

int64_t compact(const int32_t* prediction, const int8_t* record, const int32_t quantizer, const int32_t size,
                int32_t* output, distortion_measure measure) {
  int32_t nodes[GRID * GRID];
  for (int32_t index = 0; index < GRID * GRID; index++) {
    const int32_t packed = (uint8_t)record[MOTION_BYTES + index / NIBBLES_PER_BYTE];
    const int32_t nibble = (packed >> ((index % NIBBLES_PER_BYTE) * NIBBLE_BITS)) & NIBBLE_MASK;

    nodes[index] = nibble >= NIBBLE_SIGN ? nibble - NIBBLE_RANGE : nibble;
  }
  int32_t rows[GRID * ROOT_SIZE];
  interpolate_horizontally(nodes, size, rows);
  const int32_t shift = rounding_shift(size);
  const int_vector scale = int_vector::broadcast(4 * size * size);
  int32_t luma[ROOT_SIZE];
  for (int32_t row_index = 0; row_index < size; row_index++) {
    interpolate_vertically(rows, size, row_index, luma);
    const int32_t source_position = row_index * size * CHANNELS;
    for (int32_t column = 0; column < size; column += int_vector::LANES) {
      const int_vector residual = int_vector::load(luma + column).shift_left(quantizer);
      int_vector predicted_red, predicted_green, predicted_blue;
      int_vector::load_channels(prediction + source_position + column * CHANNELS, predicted_red, predicted_green,
                                predicted_blue);
      int_vector::store_channels(
          output + source_position + column * CHANNELS, round(predicted_red * scale + residual, shift),
          round(predicted_green * scale + residual, shift), round(predicted_blue * scale + residual, shift));
    }
    add_row_distortion(measure, output, source_position, size);

    if (!is_within_limit(measure)) {
      return -1;
    }
  }
  return measure.distortion_;
}

inline void widen_bytes(const uint8_t* source, const int32_t count, int32_t* target) {
  for (int32_t offset = 0; offset < count; offset += int_vector::LANES) {
    int_vector::load_unsigned_bytes(source + offset).store(target + offset);
  }
}

inline void copy_pixel(const uint8_t* source, int32_t* target) {
  for (int32_t channel = 0; channel < CHANNELS; channel++) {
    target[channel] = source[channel];
  }
}

void predict(const uint8_t* reference, const int32_t width, const int32_t height, const int32_t block_left,
             const int32_t block_top, const int32_t size, const int32_t motion_x, const int32_t motion_y,
             int32_t* output) {
  const int32_t left = block_left + motion_x;
  const int32_t top = block_top + motion_y;
  const int32_t row_length = size * CHANNELS;
  if (left >= 0 && top >= 0 && left + size <= width && top + size <= height) {
    for (int32_t row_index = 0; row_index < size; row_index++) {
      const uint8_t* source = reference + ((int64_t)(top + row_index) * width + left) * CHANNELS;
      int32_t* target = output + row_index * row_length;
      widen_bytes(source, row_length, target);
    }
    return;
  }

  for (int32_t row_index = 0; row_index < size; row_index++) {
    const int32_t source_row = clamp_int(top + row_index, 0, height - 1);
    for (int32_t column = 0; column < size; column++) {
      const int32_t source_column = clamp_int(left + column, 0, width - 1);
      const uint8_t* source = reference + ((int64_t)source_row * width + source_column) * CHANNELS;
      int32_t* target = output + (row_index * size + column) * CHANNELS;
      copy_pixel(source, target);
    }
  }
}

inline void sum_four_row_vectors(const float* plane, const float* weights, const int32_t size,
                                 const int32_t first_source_row, double* lanes) {
  double_vector first_sum = double_vector::broadcast(0.0);
  double_vector second_sum = first_sum;
  double_vector third_sum = first_sum;
  double_vector fourth_sum = first_sum;
  for (int32_t horizontal_position = 0; horizontal_position < size; horizontal_position++) {
    const double_vector weight = double_vector::broadcast((double)weights[horizontal_position]);
    const float* column = plane + horizontal_position * size + first_source_row;
    first_sum = first_sum + double_vector::load_floats(column) * weight;
    second_sum = second_sum + double_vector::load_floats(column + double_vector::LANES) * weight;
    third_sum = third_sum + double_vector::load_floats(column + 2 * double_vector::LANES) * weight;
    fourth_sum = fourth_sum + double_vector::load_floats(column + 3 * double_vector::LANES) * weight;
  }
  first_sum.store(lanes);
  second_sum.store(lanes + double_vector::LANES);
  third_sum.store(lanes + 2 * double_vector::LANES);
  fourth_sum.store(lanes + 3 * double_vector::LANES);
}

inline void sum_row_vector(const float* plane, const float* weights, const int32_t size, const int32_t first_source_row,
                           double* lanes) {
  double_vector sum = double_vector::broadcast(0.0);
  for (int32_t horizontal_position = 0; horizontal_position < size; horizontal_position++) {
    sum = sum + double_vector::load_floats(plane + horizontal_position * size + first_source_row) *
                    double_vector::broadcast((double)weights[horizontal_position]);
  }
  sum.store(lanes);
}

inline void store_lanes(const double* lanes, const int32_t lane_count, const int32_t first_source_row,
                        const int32_t element_index, double* scratch) {
  for (int32_t lane_index = 0; lane_index < lane_count; lane_index++) {
    scratch[(first_source_row + lane_index) * GRID + element_index] = lanes[lane_index];
  }
}

inline double sum_weighted_column(const float* weights, const double* scratch, const int32_t size,
                                  const int32_t element_index) {
  double sum = 0;
  for (int32_t vertical_position = 0; vertical_position < size; vertical_position++) {
    sum += (double)weights[vertical_position] * scratch[vertical_position * GRID + element_index];
  }
  return sum;
}

// Each lane must retain Java's summation order for bit-exact fits.
void fit(const float* values, const int32_t size, const float* matrix, float* output) {
  const int32_t grid = GRID;
  float plane[ROOT_SIZE * ROOT_SIZE];
  for (int32_t vertical_position = 0; vertical_position < size; vertical_position++) {
    for (int32_t horizontal_position = 0; horizontal_position < size; horizontal_position++) {
      plane[horizontal_position * size + vertical_position] = values[vertical_position * size + horizontal_position];
    }
  }
  double scratch[ROOT_SIZE * GRID];
  const int32_t unrolled_lanes = UNROLLED_VECTORS * double_vector::LANES;
  double lanes[unrolled_lanes];
  for (int32_t element_index = 0; element_index < grid; element_index++) {
    const float* weights = matrix + element_index * size;
    int32_t first_source_row = 0;

    for (; first_source_row + unrolled_lanes <= size; first_source_row += unrolled_lanes) {
      sum_four_row_vectors(plane, weights, size, first_source_row, lanes);
      store_lanes(lanes, unrolled_lanes, first_source_row, element_index, scratch);
    }
    for (; first_source_row < size; first_source_row += double_vector::LANES) {
      sum_row_vector(plane, weights, size, first_source_row, lanes);
      store_lanes(lanes, double_vector::LANES, first_source_row, element_index, scratch);
    }
  }
  for (int32_t index = 0; index < grid; index++) {
    const float* weights = matrix + index * size;
    for (int32_t element_index = 0; element_index < grid; element_index++) {
      output[index * grid + element_index] = (float)sum_weighted_column(weights, scratch, size, element_index);
    }
  }
}

inline bool lane_sums_fit(const int32_t low, const int32_t high, const int32_t count) {
  const int64_t largest = -(int64_t)low > (int64_t)high ? -(int64_t)low : (int64_t)high;
  return largest * ((int64_t)count + int_vector::LANES) <= INT32_MAX;
}

void cluster_pixels(const int32_t* source, const int32_t size, const int32_t step, int32_t* endpoints) {
  const int32_t first_red = endpoints[0];
  const int32_t first_green = endpoints[1];
  const int32_t first_blue = endpoints[2];
  const int32_t second_red = endpoints[3];
  const int32_t second_green = endpoints[4];
  const int32_t second_blue = endpoints[5];
  int64_t sums[ENDPOINT_CHANNELS + ENDPOINTS] = {0, 0, 0, 0, 0, 0, 0, 0};
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
      sums[ENDPOINT_CHANNELS + endpoint]++;
    }
  }
  for (int32_t endpoint = 0; endpoint < ENDPOINTS; endpoint++) {
    const int64_t members = sums[ENDPOINT_CHANNELS + endpoint];
    if (members <= 0) {
      continue;
    }
    for (int32_t channel = 0; channel < CHANNELS; channel++) {
      endpoints[endpoint * CHANNELS + channel] =
          (int32_t)((sums[endpoint * CHANNELS + channel] + members / 2) / members);
    }
  }
}

template <int STEP>
inline void load_step_channels(const int32_t* source, const int32_t pixel_index, int_vector& red, int_vector& green,
                               int_vector& blue) {
  int_vector::load_channels(source + pixel_index * CHANNELS, red, green, blue);

  if (STEP != CLUSTER_STEP) {
    return;
  }

  int_vector next_red, next_green, next_blue;
  int_vector::load_channels(source + (pixel_index + int_vector::LANES) * CHANNELS, next_red, next_green, next_blue);
  red = int_vector::evens(red, next_red);
  green = int_vector::evens(green, next_green);
  blue = int_vector::evens(blue, next_blue);
}

template <int STEP> void cluster_lanes(const int32_t* source, const int32_t size, int32_t* endpoints) {
  const int32_t pixels = (size / STEP) * (size / STEP);
  const int_vector first_red = int_vector::broadcast(endpoints[0]);
  const int_vector first_green = int_vector::broadcast(endpoints[1]);
  const int_vector first_blue = int_vector::broadcast(endpoints[2]);
  const int_vector second_red = int_vector::broadcast(endpoints[3]);
  const int_vector second_green = int_vector::broadcast(endpoints[4]);
  const int_vector second_blue = int_vector::broadcast(endpoints[5]);

  int_vector near_red = int_vector::zero();
  int_vector near_green = int_vector::zero();
  int_vector near_blue = int_vector::zero();
  int_vector near_count = int_vector::zero();
  int_vector all_red = int_vector::zero();
  int_vector all_green = int_vector::zero();
  int_vector all_blue = int_vector::zero();
  for (int32_t row_index = 0; row_index < size; row_index += STEP) {
    for (int32_t column = 0; column < size; column += STEP * int_vector::LANES) {
      int_vector red, green, blue;
      load_step_channels<STEP>(source, row_index * size + column, red, green, blue);
      const int_vector first_distance = (red - first_red) * (red - first_red) +
                                        (green - first_green) * (green - first_green) +
                                        (blue - first_blue) * (blue - first_blue);
      const int_vector second_distance = (red - second_red) * (red - second_red) +
                                         (green - second_green) * (green - second_green) +
                                         (blue - second_blue) * (blue - second_blue);
      const int_vector nearer = int_vector::less(second_distance, first_distance);
      near_red = near_red + (red & nearer);
      near_green = near_green + (green & nearer);
      near_blue = near_blue + (blue & nearer);
      near_count = near_count - nearer;
      all_red = all_red + red;
      all_green = all_green + green;
      all_blue = all_blue + blue;
    }
  }
  const int64_t counts[ENDPOINTS] = {pixels - near_count.sum(), near_count.sum()};
  const int64_t near[CHANNELS] = {near_red.sum(), near_green.sum(), near_blue.sum()};
  const int64_t all[CHANNELS] = {all_red.sum(), all_green.sum(), all_blue.sum()};
  for (int32_t endpoint = 0; endpoint < ENDPOINTS; endpoint++) {
    const int64_t members = counts[endpoint];
    if (members <= 0) {
      continue;
    }
    for (int32_t channel = 0; channel < CHANNELS; channel++) {
      const int64_t sum = endpoint == 1 ? near[channel] : all[channel] - near[channel];
      endpoints[endpoint * CHANNELS + channel] = (int32_t)((sum + members / 2) / members);
    }
  }
}

template <int STEP> int32_t cluster_extrema(const int32_t* source, const int32_t size, int32_t* endpoints) {
  int_vector low_lanes = int_vector::broadcast(INT32_MAX);
  int_vector high_lanes = int_vector::broadcast(INT32_MIN);
  int_vector low_at = int_vector::zero();
  int_vector high_at = int_vector::zero();
  int_vector combined_bits = int_vector::zero();
  const int_vector lane_offsets = int_vector::lane_indices() * int_vector::broadcast(STEP * CHANNELS);
  for (int32_t row_index = 0; row_index < size; row_index += STEP) {
    for (int32_t column = 0; column < size; column += STEP * int_vector::LANES) {
      int_vector red, green, blue;
      load_step_channels<STEP>(source, row_index * size + column, red, green, blue);
      combined_bits = combined_bits | red | green | blue;
      const int_vector luma = red + green + green + blue;
      const int_vector array_offset = int_vector::broadcast((row_index * size + column) * CHANNELS) + lane_offsets;
      const int_vector lower = int_vector::less(luma, low_lanes);
      low_lanes = low_lanes + ((luma - low_lanes) & lower);
      low_at = low_at + ((array_offset - low_at) & lower);
      const int_vector higher = int_vector::less(high_lanes, luma);
      high_lanes = high_lanes + ((luma - high_lanes) & higher);
      high_at = high_at + ((array_offset - high_at) & higher);
    }
  }
  int32_t lows[int_vector::LANES];
  int32_t lows_at[int_vector::LANES];
  int32_t highs[int_vector::LANES];
  int32_t highs_at[int_vector::LANES];
  int32_t combined_lanes[int_vector::LANES];
  low_lanes.store(lows);
  low_at.store(lows_at);
  high_lanes.store(highs);
  high_at.store(highs_at);
  combined_bits.store(combined_lanes);
  int32_t low = 0;
  int32_t high = 0;
  int32_t low_luma = INT32_MAX;
  int32_t high_luma = INT32_MIN;
  int32_t all = 0;
  for (int32_t lane = 0; lane < int_vector::LANES; lane++) {
    if (lows[lane] < low_luma || (lows[lane] == low_luma && lows_at[lane] < low)) {
      low_luma = lows[lane];
      low = lows_at[lane];
    }
    if (highs[lane] > high_luma || (highs[lane] == high_luma && highs_at[lane] < high)) {
      high_luma = highs[lane];
      high = highs_at[lane];
    }
    all |= combined_lanes[lane];
  }
  for (int32_t channel = 0; channel < CHANNELS; channel++) {
    endpoints[channel] = source[low + channel];
    endpoints[CHANNELS + channel] = source[high + channel];
  }
  return all;
}

inline void cluster_lanes_with_step(const int32_t* source, const int32_t size, const int32_t step, int32_t* endpoints) {
  if (step == CLUSTER_STEP) {
    cluster_lanes<CLUSTER_STEP>(source, size, endpoints);
    return;
  }

  cluster_lanes<1>(source, size, endpoints);
}

void cluster(const int32_t* source, const int32_t size, int32_t* endpoints) {
  const int32_t step = size >= SMALLEST_STEPPED_SIZE ? CLUSTER_STEP : 1;
  const int32_t all = step == CLUSTER_STEP ? cluster_extrema<CLUSTER_STEP>(source, size, endpoints)
                                           : cluster_extrema<1>(source, size, endpoints);
  if (all >= 0 && lane_sums_fit(0, all, (size / step) * (size / step))) {
    for (int32_t iteration = 0; iteration < CLUSTER_ITERATIONS; iteration++) {
      cluster_lanes_with_step(source, size, step, endpoints);
    }
    return;
  }

  cluster_pixels(source, size, step, endpoints);
  cluster_pixels(source, size, step, endpoints);
}

void assign(const int32_t* source, const int32_t count, const int32_t* colors, int8_t* selectors) {
  const int_vector first_red = int_vector::broadcast(colors[0]);
  const int_vector first_green = int_vector::broadcast(colors[1]);
  const int_vector first_blue = int_vector::broadcast(colors[2]);
  const int_vector second_red = int_vector::broadcast(colors[3]);
  const int_vector second_green = int_vector::broadcast(colors[4]);
  const int_vector second_blue = int_vector::broadcast(colors[5]);
  int32_t index = 0;
  for (; index + int_vector::LANES <= count; index += int_vector::LANES) {
    int_vector red_values, green_values, blue_values;
    int_vector::load_channels(source + index * CHANNELS, red_values, green_values, blue_values);
    const int_vector first_red_difference = red_values - first_red;
    const int_vector first_green_difference = green_values - first_green;
    const int_vector first_blue_difference = blue_values - first_blue;
    const int_vector second_red_difference = red_values - second_red;
    const int_vector second_green_difference = green_values - second_green;
    const int_vector second_blue_difference = blue_values - second_blue;
    const int_vector first_distance = first_red_difference * first_red_difference +
                                      first_green_difference * first_green_difference +
                                      first_blue_difference * first_blue_difference;
    const int_vector second_distance = second_red_difference * second_red_difference +
                                       second_green_difference * second_green_difference +
                                       second_blue_difference * second_blue_difference;
    (int_vector::less(second_distance, first_distance) & int_vector::broadcast(1)).store_bytes(selectors + index);
  }
  for (; index < count; index++) {
    const int32_t* samples = source + index * CHANNELS;
    const int32_t first_distance = (samples[0] - colors[0]) * (samples[0] - colors[0]) +
                                   (samples[1] - colors[1]) * (samples[1] - colors[1]) +
                                   (samples[2] - colors[2]) * (samples[2] - colors[2]);
    const int32_t second_distance = (samples[0] - colors[3]) * (samples[0] - colors[3]) +
                                    (samples[1] - colors[4]) * (samples[1] - colors[4]) +
                                    (samples[2] - colors[5]) * (samples[2] - colors[5]);
    selectors[index] = (int8_t)(second_distance < first_distance ? 1 : 0);
  }
}

int32_t assign_pattern(const int32_t* source, const int32_t size, const int32_t* colors, int8_t* selectors) {
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

inline int32_t sample_position(const int32_t size, const int32_t sample_index) {
  return minimum_int((sample_index * size) / SAMPLE_GRID + size / (2 * SAMPLE_GRID), size - 1);
}

inline int32_t pack_motion(const int32_t motion_x, const int32_t motion_y) {
  return wrapping_shift_left(motion_x, HORIZONTAL_MOTION_SHIFT) | (motion_y & VERTICAL_MOTION_MASK);
}
inline int32_t unpack_horizontal_motion(const int32_t vector) {
  return vector >> HORIZONTAL_MOTION_SHIFT;
}
inline int32_t unpack_vertical_motion(const int32_t vector) {
  return (int16_t)vector;
}

inline int64_t pixel_cost(const uint8_t* reference_pixel, const int32_t* source_pixel) {
  int64_t sum = 0;
  for (int32_t channel = 0; channel < CHANNELS; channel++) {
    const int32_t difference = wrapping_subtraction(reference_pixel[channel], source_pixel[channel]);
    sum += difference < 0 ? wrapping_subtraction(0, difference) : difference;
  }
  return sum;
}

int64_t interior_cost(const uint8_t* reference, const int32_t width, const int32_t* source_samples,
                      const int32_t block_left, const int32_t block_top, const int32_t* samples, const int32_t motion_x,
                      const int32_t motion_y) {
  int64_t sum = 0;
  for (int32_t sample_row = 0; sample_row < SAMPLE_GRID; sample_row++) {
    const uint8_t* line =
        reference + ((int64_t)(block_top + samples[sample_row] + motion_y) * width + block_left + motion_x) * CHANNELS;
    const int32_t* source_row = source_samples + sample_row * SAMPLE_GRID * CHANNELS;
    for (int32_t sample_column = 0; sample_column < SAMPLE_GRID; sample_column++) {
      const uint8_t* reference_pixel = line + samples[sample_column] * CHANNELS;
      const int32_t* source_pixel = source_row + sample_column * CHANNELS;
      sum += pixel_cost(reference_pixel, source_pixel);
    }
  }
  return sum;
}

int64_t packed_interior_cost(const uint8_t* reference, const int32_t width, const uint8_t* packed_samples,
                             const int32_t block_left, const int32_t block_top, const int32_t* samples,
                             const int32_t motion_x, const int32_t motion_y) {
  int32_t sum = 0;
  for (int32_t sample_row = 0; sample_row < SAMPLE_GRID; sample_row++) {
    const uint8_t* line =
        reference + ((int64_t)(block_top + samples[sample_row] + motion_y) * width + block_left + motion_x) * CHANNELS;
    sum += four_pixel_difference(pixel_word(line + samples[0] * CHANNELS), pixel_word(line + samples[1] * CHANNELS),
                                 pixel_word(line + samples[2] * CHANNELS), pixel_word(line + samples[3] * CHANNELS),
                                 packed_samples + SAMPLE_ROW_BYTES * sample_row);
  }
  return sum;
}

int64_t cost(const uint8_t* reference, const int32_t width, const int32_t height, const int32_t* source_samples,
             const uint8_t* packed_samples, const int32_t block_left, const int32_t block_top, const int32_t* samples,
             const int32_t motion_x, const int32_t motion_y) {
  const int32_t left = block_left + samples[0] + motion_x;
  const int32_t right = block_left + samples[3] + motion_x;
  const int32_t top = block_top + samples[0] + motion_y;
  const int32_t bottom = block_top + samples[3] + motion_y;
  if (left >= 0 && top >= 0 && right < width && bottom < height) {
    return packed_samples != nullptr
               ? packed_interior_cost(reference, width, packed_samples, block_left, block_top, samples, motion_x,
                                      motion_y)
               : interior_cost(reference, width, source_samples, block_left, block_top, samples, motion_x, motion_y);
  }
  int64_t sum = 0;
  for (int32_t sample_row = 0; sample_row < SAMPLE_GRID; sample_row++) {
    const int32_t reference_row = clamp_int(block_top + samples[sample_row] + motion_y, 0, height - 1);
    for (int32_t sample_column = 0; sample_column < SAMPLE_GRID; sample_column++) {
      const int32_t reference_column = clamp_int(block_left + samples[sample_column] + motion_x, 0, width - 1);
      const uint8_t* reference_pixel = reference + ((int64_t)reference_row * width + reference_column) * CHANNELS;
      const int32_t* source_pixel = source_samples + (sample_row * SAMPLE_GRID + sample_column) * CHANNELS;
      sum += pixel_cost(reference_pixel, source_pixel);
    }
  }
  return sum;
}

struct measured_vectors {
  static constexpr int32_t CAPACITY = 256;
  int64_t vectors_[CAPACITY];
  int32_t count_ = 0;
  // Full coordinates avoid collisions caused by truncating packed motion vectors.
  static int64_t key(const int32_t motion_x, const int32_t motion_y) {
    return (int64_t)((uint64_t)(uint32_t)motion_x << KEY_HORIZONTAL_SHIFT | (uint32_t)motion_y);
  }
  bool contains(const int32_t motion_x, const int32_t motion_y) const {
    const int64_t vector = key(motion_x, motion_y);
    for (int32_t index = 0; index < count_; index++) {
      if (vectors_[index] == vector) {
        return true;
      }
    }
    return false;
  }
  void add(const int32_t motion_x, const int32_t motion_y) {
    if (count_ < CAPACITY) {
      vectors_[count_++] = key(motion_x, motion_y);
    }
  }
};

struct motion_search {
  const uint8_t* reference_;
  int32_t width_;
  int32_t height_;
  const int32_t* source_samples_;
  const uint8_t* packed_samples_;
  int32_t block_left_;
  int32_t block_top_;
  const int32_t* samples_;
  measured_vectors measured_;
  int64_t best_cost_ = INT64_MAX;
  int32_t best_x_ = 0;
  int32_t best_y_ = 0;
  motion_search(const uint8_t* reference, const int32_t width, const int32_t height, const int32_t* source_samples,
                const uint8_t* packed_samples, const int32_t block_left, const int32_t block_top,
                const int32_t* samples)
      : reference_(reference), width_(width), height_(height), source_samples_(source_samples),
        packed_samples_(packed_samples), block_left_(block_left), block_top_(block_top), samples_(samples) {
  }
  void measure_once(const int32_t motion_x, const int32_t motion_y) {
    if (measured_.contains(motion_x, motion_y)) {
      return;
    }

    measured_.add(motion_x, motion_y);
    const int64_t motion_cost = cost(reference_, width_, height_, source_samples_, packed_samples_, block_left_,
                                     block_top_, samples_, motion_x, motion_y);
    if (motion_cost < best_cost_) {
      best_cost_ = motion_cost;
      best_x_ = motion_x;
      best_y_ = motion_y;
    }
  }
};

inline bool sample_pixel(const int32_t* source_pixel, int32_t* sample, uint8_t* sample_byte) {
  bool fits_bytes = true;
  for (int32_t channel = 0; channel < CHANNELS; channel++) {
    const int32_t value = source_pixel[channel];
    sample[channel] = value;
    fits_bytes &= (uint32_t)value <= MAXIMUM_CHANNEL;
    sample_byte[channel] = (uint8_t)value;
  }
  return fits_bytes;
}

int32_t seeded(const uint8_t* reference, const int32_t width, const int32_t height, const int32_t* source,
               const int32_t block_left, const int32_t block_top, const int32_t size, const int32_t range,
               const int32_t* seeds, const int32_t seed_count) {
  const int32_t samples[SAMPLE_GRID] = {sample_position(size, 0), sample_position(size, 1), sample_position(size, 2),
                                        sample_position(size, 3)};
  int32_t source_samples[SAMPLE_GRID * SAMPLE_GRID * CHANNELS];
  uint8_t sample_bytes[SAMPLE_GRID * SAMPLE_ROW_BYTES] = {0};
  bool fits_bytes = true;
  for (int32_t sample_row = 0; sample_row < SAMPLE_GRID; sample_row++) {
    for (int32_t sample_column = 0; sample_column < SAMPLE_GRID; sample_column++) {
      const int32_t* source_pixel = source + (samples[sample_row] * size + samples[sample_column]) * CHANNELS;
      int32_t* sample = source_samples + (sample_row * SAMPLE_GRID + sample_column) * CHANNELS;
      uint8_t* sample_byte = sample_bytes + SAMPLE_ROW_BYTES * sample_row + WORD_BYTES * sample_column;
      fits_bytes &= sample_pixel(source_pixel, sample, sample_byte);
    }
  }
  const uint8_t* packed_samples = fits_bytes ? sample_bytes : nullptr;
  motion_search search(reference, width, height, source_samples, packed_samples, block_left, block_top, samples);
  search.measure_once(0, 0);
  for (int32_t seed_index = 0; seed_index < seed_count; seed_index++) {
    const int32_t seed_x = clamp_int(unpack_horizontal_motion(seeds[seed_index]), -range, range);
    const int32_t seed_y = clamp_int(unpack_vertical_motion(seeds[seed_index]), -range, range);
    search.measure_once(seed_x, seed_y);
  }
  for (int32_t step = 0; step < 2 * range; step++) {
    const int32_t center_x = search.best_x_;
    const int32_t center_y = search.best_y_;
    for (int32_t direction = 0; direction < DIRECTION_COUNT; direction++) {
      const int32_t candidate_x = clamp_int(center_x + DIRECTIONS[direction][0], -range, range);
      const int32_t candidate_y = clamp_int(center_y + DIRECTIONS[direction][1], -range, range);
      search.measure_once(candidate_x, candidate_y);
    }
    if (search.best_x_ == center_x && search.best_y_ == center_y) {
      break;
    }
  }
  return pack_motion(search.best_x_, search.best_y_);
}

void load_source(const uint8_t* image, const int32_t width, const int32_t height, const int32_t horizontal_position,
                 const int32_t vertical_position, const int32_t size, int32_t* source) {
  for (int32_t pixel_row = 0; pixel_row < size; pixel_row++) {
    const int32_t source_row = minimum_int(vertical_position + pixel_row, height - 1);
    const uint8_t* line = image + (int64_t)source_row * width * CHANNELS;
    int32_t* destination = source + pixel_row * size * CHANNELS;
    if (horizontal_position + size <= width) {
      const uint8_t* block_start = line + (int64_t)horizontal_position * CHANNELS;
      widen_bytes(block_start, size * CHANNELS, destination);
      continue;
    }
    for (int32_t pixel_column = 0; pixel_column < size; pixel_column++) {
      const int32_t source_column = minimum_int(horizontal_position + pixel_column, width - 1);
      const uint8_t* source_pixel = line + (int64_t)source_column * CHANNELS;
      int32_t* destination_pixel = destination + pixel_column * CHANNELS;
      copy_pixel(source_pixel, destination_pixel);
    }
  }
}

template <class VectorType> void halve_lanes(const int32_t* block, const int32_t size, int32_t* output) {
  const int32_t half = size / 2;
  const int32_t row_length = size * CHANNELS;
  const VectorType two = VectorType::broadcast(2);
  for (int32_t pixel_row = 0; pixel_row < half; pixel_row++) {
    const int32_t* top = block + 2 * pixel_row * row_length;
    const int32_t* bottom = top + row_length;
    int32_t* line = output + pixel_row * half * CHANNELS;
    for (int32_t horizontal_position = 0; horizontal_position < size; horizontal_position += 2 * VectorType::LANES) {
      VectorType first_red, first_green, first_blue, second_red, second_green, second_blue, third_red, third_green,
          third_blue, fourth_red, fourth_green, fourth_blue;
      VectorType::load_channels(top + horizontal_position * CHANNELS, first_red, first_green, first_blue);
      VectorType::load_channels(top + (horizontal_position + VectorType::LANES) * CHANNELS, second_red, second_green,
                                second_blue);
      VectorType::load_channels(bottom + horizontal_position * CHANNELS, third_red, third_green, third_blue);
      VectorType::load_channels(bottom + (horizontal_position + VectorType::LANES) * CHANNELS, fourth_red, fourth_green,
                                fourth_blue);
      const VectorType first_red_sum = first_red + third_red;
      const VectorType second_red_sum = second_red + fourth_red;
      const VectorType first_green_sum = first_green + third_green;
      const VectorType second_green_sum = second_green + fourth_green;
      const VectorType first_blue_sum = first_blue + third_blue;
      const VectorType second_blue_sum = second_blue + fourth_blue;
      const VectorType red_sum =
          VectorType::evens(first_red_sum, second_red_sum) + VectorType::odds(first_red_sum, second_red_sum) + two;
      const VectorType green_sum = VectorType::evens(first_green_sum, second_green_sum) +
                                   VectorType::odds(first_green_sum, second_green_sum) + two;
      const VectorType blue_sum =
          VectorType::evens(first_blue_sum, second_blue_sum) + VectorType::odds(first_blue_sum, second_blue_sum) + two;
      VectorType::store_channels(line + (horizontal_position / 2) * CHANNELS, red_sum.shift_right(2),
                                 green_sum.shift_right(2), blue_sum.shift_right(2));
    }
  }
}

void halve(const int32_t* block, const int32_t size, int32_t* output) {
  if (SHUFFLED_CHANNEL_LOAD || size < 2 * int_vector::LANES) {
    halve_lanes<scalar_int_vector>(block, size, output);
  } else {
    halve_lanes<int_vector>(block, size, output);
  }
}

void residual_target(const int32_t* source, const int32_t* prediction, const int32_t count, float* target) {
  const float_vector normalization = float_vector::broadcast(LUMA_NORMALIZATION);
  const float_vector green_weight = float_vector::broadcast(LUMA_GREEN_WEIGHT);
  int32_t pixel = 0;
  for (; pixel + int_vector::LANES <= count; pixel += int_vector::LANES) {
    int_vector source_red, source_green, source_blue;
    int_vector::load_channels(source + pixel * CHANNELS, source_red, source_green, source_blue);
    const float_vector luma =
        float_vector::from_integers(source_red + source_green.shift_left(1) + source_blue) * normalization;
    int_vector predicted_red, predicted_green, predicted_blue;
    int_vector::load_channels(prediction + pixel * CHANNELS, predicted_red, predicted_green, predicted_blue);
    const float_vector red = float_vector::from_integers(predicted_red);
    const float_vector green = float_vector::from_integers(predicted_green);
    const float_vector blue = float_vector::from_integers(predicted_blue);
    (luma - ((red + green_weight * green) + blue) * normalization).store(target + pixel);
  }
  for (; pixel < count; pixel++) {
    const int32_t array_offset = pixel * CHANNELS;
    const float luma =
        (float)(source[array_offset] + LUMA_GREEN_WEIGHT * source[array_offset + 1] + source[array_offset + 2]) *
        LUMA_NORMALIZATION;
    const float red = (float)prediction[array_offset];
    const float green = (float)prediction[array_offset + 1];
    const float blue = (float)prediction[array_offset + 2];
    target[pixel] = luma - (red + LUMA_GREEN_WEIGHT * green + blue) * LUMA_NORMALIZATION;
  }
}

}
}

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

// Narrow blocks require a smaller SIMD level to avoid reading past their rows.
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
MCV2_EXPORT void MCV2_FIT_TO(fit)(const float* values, int32_t size, const float* matrix, float* output);
#endif

int64_t MCV2_PREFIX(predicted)(const int32_t* prediction, const int32_t size, int32_t* output, const int32_t* source,
                               const double rate, const double limit) {
  MCV2_HAND_OFF(predicted, prediction, size, output, source, rate, limit)
  return predicted(prediction, size, output, {source, rate, limit, 0});
}

int64_t MCV2_PREFIX(solid)(const int32_t color, const int32_t size, int32_t* output, const int32_t* source,
                           const double rate, const double limit) {
  MCV2_HAND_OFF(solid, color, size, output, source, rate, limit)
  return solid(color, size, output, {source, rate, limit, 0});
}

int64_t MCV2_PREFIX(palette)(const int8_t* record, const int32_t size, int32_t* output, const int32_t* source,
                             const double rate, const double limit) {
  MCV2_HAND_OFF(palette, record, size, output, source, rate, limit)
  return palette(record, size, output, {source, rate, limit, 0});
}

int64_t MCV2_PREFIX(compact)(const int32_t* prediction, const int8_t* record, const int32_t quantizer,
                             const int32_t size, int32_t* output, const int32_t* source, const double rate,
                             const double limit) {
  MCV2_HAND_OFF(compact, prediction, record, quantizer, size, output, source, rate, limit)
  return compact(prediction, record, quantizer, size, output, {source, rate, limit, 0});
}

void MCV2_PREFIX(predict)(const uint8_t* reference, const int32_t width, const int32_t height,
                          const int32_t horizontal_position, const int32_t vertical_position, const int32_t size,
                          const int32_t horizontal_motion, const int32_t vertical_motion, int32_t* output) {
  MCV2_HAND_OFF(predict, reference, width, height, horizontal_position, vertical_position, size, horizontal_motion,
                vertical_motion, output)
  predict(reference, width, height, horizontal_position, vertical_position, size, horizontal_motion, vertical_motion,
          output);
}

void MCV2_PREFIX(fit)(const float* values, const int32_t size, const float* matrix, float* output) {
  MCV2_HAND_OFF(fit, values, size, matrix, output)
#if defined(MCV2_FIT_TO)
  if (size == MCV2_FIT_AT) {
    return MCV2_FIT_TO(fit)(values, size, matrix, output);
  }
#endif
  fit(values, size, matrix, output);
}

void MCV2_PREFIX(cluster)(const int32_t* source, const int32_t size, int32_t* endpoints) {
  // Blocks narrower than two vectors would make the sampled clustering kernel overread.
  MCV2_HAND_OFF_BELOW(2 * MCV2_NARROW_BELOW, cluster, source, size, endpoints)
  cluster(source, size, endpoints);
}

void MCV2_PREFIX(assign)(const int32_t* source, const int32_t count, const int32_t* colors, int8_t* selectors) {
  assign(source, count, colors, selectors);
}

int32_t MCV2_PREFIX(assign_pattern)(const int32_t* source, const int32_t size, const int32_t* colors,
                                    int8_t* selectors) {
  MCV2_HAND_OFF(assign_pattern, source, size, colors, selectors)
  return assign_pattern(source, size, colors, selectors);
}

int32_t MCV2_PREFIX(seeded)(const uint8_t* reference, const int32_t width, const int32_t height, const int32_t* source,
                            const int32_t horizontal_position, const int32_t vertical_position, const int32_t size,
                            const int32_t range, const int32_t* seeds, const int32_t seed_count) {
  MCV2_HAND_OFF(seeded, reference, width, height, source, horizontal_position, vertical_position, size, range, seeds,
                seed_count)
  return seeded(reference, width, height, source, horizontal_position, vertical_position, size, range, seeds,
                seed_count);
}

void MCV2_PREFIX(load_source)(const uint8_t* image, const int32_t width, const int32_t height,
                              const int32_t horizontal_position, const int32_t vertical_position, const int32_t size,
                              int32_t* source) {
  MCV2_HAND_OFF(load_source, image, width, height, horizontal_position, vertical_position, size, source)
  load_source(image, width, height, horizontal_position, vertical_position, size, source);
}

void MCV2_PREFIX(halve)(const int32_t* block, const int32_t size, int32_t* output) {
  // Blocks narrower than two vectors would make the halving kernel overread.
  MCV2_HAND_OFF_BELOW(2 * MCV2_NARROW_BELOW, halve, block, size, output)
  halve(block, size, output);
}

void MCV2_PREFIX(residual_target)(const int32_t* source, const int32_t* prediction, const int32_t count,
                                  float* target) {
  residual_target(source, prediction, count, target);
}
}

#if defined(MCV2_CPU)

#if defined(__x86_64__) || defined(_M_X64)
#include <cpuid.h>

namespace {

constexpr unsigned FEATURES_LEAF = 1;
constexpr unsigned EXTENDED_FEATURES_LEAF = 7;
constexpr unsigned EXTENDED_FEATURES_SUBLEAF = 0;

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

constexpr unsigned EXTENDED_CONTROL_REGISTER = 0;
constexpr int HIGH_WORD_SHIFT = 32;

unsigned long long extended_control_state() {
  unsigned low;
  unsigned high;
  __asm__ volatile("xgetbv" : "=a"(low), "=d"(high) : "c"(EXTENDED_CONTROL_REGISTER));
  return ((unsigned long long)high << HIGH_WORD_SHIFT) | low;
}

}

extern "C" int32_t mcv2_cpu_levels(int64_t) {
  int32_t levels = MCV2_LEVEL_SCALAR | MCV2_LEVEL_SSE2;
  unsigned register_a;
  unsigned register_b;
  unsigned register_c;
  unsigned register_d;
  if (!__get_cpuid(FEATURES_LEAF, &register_a, &register_b, &register_c, &register_d)) {
    return levels;
  }

  if (register_c & SSE41_BIT) {
    levels |= MCV2_LEVEL_SSE41;
  }
  const unsigned long long state = (register_c & OSXSAVE_BIT) && (register_c & AVX_BIT) ? extended_control_state() : 0;
  if ((state & YMM_STATE) != YMM_STATE || !__get_cpuid_count(EXTENDED_FEATURES_LEAF, EXTENDED_FEATURES_SUBLEAF,
                                                             &register_a, &register_b, &register_c, &register_d)) {
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

#if defined(__linux__)
namespace {

constexpr unsigned long long SVE256_VECTOR_BYTES = 32;
constexpr unsigned long long SVE512_VECTOR_BYTES = 64;

}
#endif

extern "C" int32_t mcv2_cpu_levels(const int64_t kernel_capabilities) {
  int32_t levels = MCV2_LEVEL_SCALAR | MCV2_LEVEL_NEON;
#if defined(__linux__)
  if (kernel_capabilities & MCV2_HARDWARE_CAPABILITY_SVE) {
    unsigned long long vector_bytes;
    __asm__ volatile(".arch_extension sve\n\trdvl %0, #1" : "=r"(vector_bytes));
    if (vector_bytes == SVE256_VECTOR_BYTES) {
      levels |= MCV2_LEVEL_SVE256;
    } else if (vector_bytes == SVE512_VECTOR_BYTES) {
      levels |= MCV2_LEVEL_SVE512;
    }
  }
#else
  (void)kernel_capabilities;
#endif
  return levels;
}

#else

extern "C" int32_t mcv2_cpu_levels(int64_t) {
  return MCV2_LEVEL_SCALAR;
}

#endif

extern "C" int32_t mcv2_abi(void) {
  return MCV2_INTERFACE_VERSION;
}
#endif
#endif
