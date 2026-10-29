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

#include <float.h>
#include <limits.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include <vector>

using std::vector;

#define MCV2_DECLARATIONS_ONLY
#include "mcv2.cpp"

#ifndef MCV2_TEST_DIRECT
#include <dlfcn.h>
#endif
#if defined(__aarch64__) && defined(__linux__)
#include <sys/auxv.h>
#endif

namespace {

struct level_kernels {
  const char* name_;
#define MCV2_FIELD(type, name, parameters) type(*name##_) parameters;
  MCV2_KERNELS(MCV2_FIELD)
};

#ifdef MCV2_TEST_DIRECT
#define MCV2_DECLARE_LEVEL(level)                                                                                      \
  extern "C" {                                                                                                         \
  MCV2_KERNELS(MCV2_DECLARE_##level)                                                                                   \
  }
#define MCV2_DECLARE_scalar(type, name, parameters) type mcv2_scalar_##name parameters;
#define MCV2_DECLARE_sse2(type, name, parameters) type mcv2_sse2_##name parameters;
#define MCV2_DECLARE_sse41(type, name, parameters) type mcv2_sse41_##name parameters;
#define MCV2_DECLARE_avx2(type, name, parameters) type mcv2_avx2_##name parameters;
#define MCV2_DECLARE_avx512(type, name, parameters) type mcv2_avx512_##name parameters;
#define MCV2_DECLARE_neon(type, name, parameters) type mcv2_neon_##name parameters;
#define MCV2_DECLARE_sve256(type, name, parameters) type mcv2_sve256_##name parameters;
#define MCV2_DECLARE_sve512(type, name, parameters) type mcv2_sve512_##name parameters;
#define MCV2_ENTRY_scalar(type, name, parameters) mcv2_scalar_##name,
#define MCV2_ENTRY_sse2(type, name, parameters) mcv2_sse2_##name,
#define MCV2_ENTRY_sse41(type, name, parameters) mcv2_sse41_##name,
#define MCV2_ENTRY_avx2(type, name, parameters) mcv2_avx2_##name,
#define MCV2_ENTRY_avx512(type, name, parameters) mcv2_avx512_##name,
#define MCV2_ENTRY_neon(type, name, parameters) mcv2_neon_##name,
#define MCV2_ENTRY_sve256(type, name, parameters) mcv2_sve256_##name,
#define MCV2_ENTRY_sve512(type, name, parameters) mcv2_sve512_##name,
}
MCV2_DECLARE_LEVEL(scalar)
#if defined(__x86_64__)
MCV2_DECLARE_LEVEL(sse2)
MCV2_DECLARE_LEVEL(sse41)
MCV2_DECLARE_LEVEL(avx2)
MCV2_DECLARE_LEVEL(avx512)
#elif defined(__aarch64__)
MCV2_DECLARE_LEVEL(neon)
MCV2_DECLARE_LEVEL(sve256)
MCV2_DECLARE_LEVEL(sve512)
#endif
namespace {
#endif

constexpr uint64_t GOLDEN_GAMMA = 0x9E3779B97F4A7C15ull;
constexpr int FIRST_MIX_SHIFT = 30;
constexpr uint64_t FIRST_MIX_MULTIPLIER = 0xBF58476D1CE4E5B9ull;
constexpr int SECOND_MIX_SHIFT = 27;
constexpr uint64_t SECOND_MIX_MULTIPLIER = 0x94D049BB133111EBull;
constexpr int FINAL_MIX_SHIFT = 31;

constexpr uint64_t FNV_OFFSET_BASIS = 0xCBF29CE484222325ull;
constexpr uint64_t FNV_PRIME = 0x100000001B3ull;

constexpr uint64_t RANDOM_SEED = 0x6D637632ull;

constexpr int32_t CHANNELS = 3;
constexpr int32_t MAXIMUM_CHANNEL = 255;

constexpr int32_t ENDPOINTS = 2;
constexpr int32_t ENDPOINT_CHANNELS = ENDPOINTS * CHANNELS;

constexpr int32_t SMALLEST_BLOCK_SIZE = 8;
constexpr int32_t BLOCK_SIZES = 3;

constexpr int32_t GRID = 4;

constexpr int32_t COMPACT_RECORD_BYTES = 10;

constexpr int32_t MAXIMUM_MOTION = 40;
constexpr int32_t HORIZONTAL_MOTION_SHIFT = 16;
constexpr int32_t VERTICAL_MOTION_MASK = 0xFFFF;

constexpr int32_t OUT_OF_RANGE_ODDS = 7;

constexpr int TRIALS = 400;
constexpr int MAXIMUM_REPORTED_FAILURES = 20;

constexpr const char* DIGEST_LINE_FORMAT = "%-16s %016llx\n";

constexpr char EXPECT_PREFIX[] = "expect=";
constexpr size_t EXPECT_PREFIX_LENGTH = sizeof(EXPECT_PREFIX) - 1;

constexpr int FAILURE_EXIT_CODE = 1;
constexpr int SETUP_FAILURE_EXIT_CODE = 2;

#ifndef MCV2_TEST_DIRECT
constexpr int LIBRARY_ARGUMENT_COUNT = 2;
constexpr size_t SYMBOL_CAPACITY = 64;
constexpr const char* CPU_LEVELS_SYMBOL = "mcv2_cpu_levels";
constexpr const char* KERNEL_SYMBOL_FORMAT = "mcv2_%s_%s";
#endif

struct known_level {
  const char* name_;
  int32_t bit_;
};

constexpr known_level KNOWN_LEVELS[] = {{"scalar", MCV2_LEVEL_SCALAR}, {"sse2", MCV2_LEVEL_SSE2},
                                        {"sse41", MCV2_LEVEL_SSE41},   {"avx2", MCV2_LEVEL_AVX2},
                                        {"avx512", MCV2_LEVEL_AVX512}, {"neon", MCV2_LEVEL_NEON},
                                        {"sve256", MCV2_LEVEL_SVE256}, {"sve512", MCV2_LEVEL_SVE512}};

int64_t kernel_capabilities() {
#if defined(__aarch64__) && defined(__linux__)
  return (int64_t)getauxval(AT_HWCAP);
#else
  return 0;
#endif
}

struct random_generator {
  uint64_t state_;
  uint64_t next() {
    uint64_t mixed_value = (state_ += GOLDEN_GAMMA);
    mixed_value = (mixed_value ^ (mixed_value >> FIRST_MIX_SHIFT)) * FIRST_MIX_MULTIPLIER;
    mixed_value = (mixed_value ^ (mixed_value >> SECOND_MIX_SHIFT)) * SECOND_MIX_MULTIPLIER;
    return mixed_value ^ (mixed_value >> FINAL_MIX_SHIFT);
  }
  int32_t range(const int32_t low, const int32_t high) {
    return low + (int32_t)(next() % (uint64_t)(high - low + 1));
  }
  vector<int32_t> integers(const size_t count, const int32_t low, const int32_t high) {
    vector<int32_t> values(count);
    for (int32_t& value : values) {
      value = range(low, high);
    }
    return values;
  }
  vector<int8_t> bytes(const size_t count) {
    vector<int8_t> values(count);
    for (int8_t& value : values) {
      value = (int8_t)next();
    }
    return values;
  }
  vector<uint8_t> unsigned_bytes(const size_t count) {
    vector<uint8_t> values(count);
    for (uint8_t& value : values) {
      value = (uint8_t)next();
    }
    return values;
  }

  vector<float> floats(const size_t count) {
    vector<float> values(count);
    for (float& value : values) {
      switch (range(0, 15)) {
      case 0:
        value = -0.0f;
        break;
      case 1:
        value = FLT_TRUE_MIN * (float)range(1, 1000);
        break;
      case 2:
        value = 1e30f * (float)range(-3, 3);
        break;
      default:
        value = ((float)range(-1000000, 1000000) / 3.0f) / 1000;
      }
    }
    return values;
  }
};

struct fnv_digest {
  uint64_t value_ = FNV_OFFSET_BASIS;
  void add(const void* data, const size_t length) {
    const uint8_t* bytes = (const uint8_t*)data;
    for (size_t index = 0; index < length; index++) {
      value_ = (value_ ^ bytes[index]) * FNV_PRIME;
    }
  }
  template <class ValueType> void add(const vector<ValueType>& values) {
    add(values.data(), values.size() * sizeof(ValueType));
  }
  template <class ValueType> void add(const ValueType value) {
    add(&value, sizeof(value));
  }
};

int failures = 0;

template <class ValueType> bool same(const vector<ValueType>& actual, const vector<ValueType>& expected) {
  // An empty vector may return null data, which memcmp cannot accept.
  return actual.size() == expected.size() &&
         (actual.empty() || memcmp(actual.data(), expected.data(), actual.size() * sizeof(ValueType)) == 0);
}

void report(const bool equal, const char* kernel, const level_kernels& level, const int trial) {
  if (!equal && failures++ < MAXIMUM_REPORTED_FAILURES) {
    fprintf(stderr, "%s differs at %s, trial %d\n", kernel, level.name_, trial);
  }
}

enum kernel_index {
  PREDICTED,
  SOLID,
  PALETTE,
  COMPACT,
  PREDICT,
  FIT,
  CLUSTER,
  ASSIGN,
  ASSIGN_PATTERN,
  SEEDED,
  LOAD_SOURCE,
  HALVE,
  RESIDUAL_TARGET,
  KERNEL_COUNT,
};

constexpr const char* KERNEL_NAMES[] = {"predicted",   "solid",   "palette",        "compact", "predict",
                                        "fit",         "cluster", "assign",         "pattern", "seeded",
                                        "load_source", "halve",   "residual_target"};

struct kernel_trial {
  int kernel_;
  int index_;
  int32_t size_;
  size_t channels_;
  vector<int32_t> source_;
  vector<int32_t> prediction_;
  double rate_;
  double limit_;
};

struct kernel_result {
  vector<int32_t> output_;
  vector<int32_t> integers_;
  vector<float> floats_;
  vector<int8_t> bytes_;
  int64_t measured_ = 0;
};

kernel_trial draw_trial(const int kernel, const int index, random_generator& random) {
  const int32_t size = SMALLEST_BLOCK_SIZE << random.range(0, BLOCK_SIZES - 1);
  const size_t channels = (size_t)size * size * CHANNELS;
  const vector<int32_t> source = random.integers(channels, 0, MAXIMUM_CHANNEL);
  const vector<int32_t> prediction = random.integers(channels, 0, MAXIMUM_CHANNEL);
  const double rate = random.range(0, 100000) / 7.0;
  const double limit = random.range(0, 3) == 0 ? INFINITY : rate + random.range(0, size * size * 3000);
  return {kernel, index, size, channels, source, prediction, rate, limit};
}

void run_palette(const level_kernels& level, const kernel_trial& trial, random_generator& inputs,
                 kernel_result& result) {
  const vector<int8_t> record = inputs.bytes(ENDPOINT_CHANNELS + trial.size_ * trial.size_ / CHAR_BIT);
  result.measured_ = level.palette_(record.data(), trial.size_, result.output_.data(), trial.source_.data(),
                                    trial.rate_, trial.limit_);
}

void run_compact(const level_kernels& level, const kernel_trial& trial, random_generator& inputs,
                 kernel_result& result) {
  const int32_t quantizer = inputs.range(0, 2);
  const vector<int8_t> record = inputs.bytes(COMPACT_RECORD_BYTES);
  result.measured_ = level.compact_(trial.prediction_.data(), record.data(), quantizer, trial.size_,
                                    result.output_.data(), trial.source_.data(), trial.rate_, trial.limit_);
}

void run_predict(const level_kernels& level, const kernel_trial& trial, random_generator& inputs,
                 kernel_result& result) {
  const int32_t width = inputs.range(1, 80);
  const int32_t height = inputs.range(1, 60);
  const vector<uint8_t> reference = inputs.unsigned_bytes((size_t)width * height * CHANNELS);
  const int32_t horizontal_position = inputs.range(-10, width + 10);
  const int32_t vertical_position = inputs.range(-10, height + 10);
  const int32_t horizontal_motion = inputs.range(-MAXIMUM_MOTION, MAXIMUM_MOTION);
  const int32_t vertical_motion = inputs.range(-MAXIMUM_MOTION, MAXIMUM_MOTION);
  level.predict_(reference.data(), width, height, horizontal_position, vertical_position, trial.size_,
                 horizontal_motion, vertical_motion, result.output_.data());
}

void run_fit(const level_kernels& level, const kernel_trial& trial, random_generator& inputs, kernel_result& result) {
  const vector<float> values = inputs.floats((size_t)trial.size_ * trial.size_);
  const vector<float> matrix = inputs.floats((size_t)GRID * trial.size_);
  result.floats_.assign(GRID * GRID, 0);
  level.fit_(values.data(), trial.size_, matrix.data(), result.floats_.data());
}

void run_cluster(const level_kernels& level, const kernel_trial& trial, random_generator& inputs,
                 kernel_result& result) {
  const int32_t low = inputs.range(0, MAXIMUM_CHANNEL);
  const int32_t spread = inputs.range(0, 1) ? 8 : MAXIMUM_CHANNEL;
  vector<int32_t> block =
      inputs.integers(trial.channels_, low, low + spread > MAXIMUM_CHANNEL ? MAXIMUM_CHANNEL : low + spread);
  if (inputs.range(0, OUT_OF_RANGE_ODDS) == 0) {
    for (int32_t& value : block) {
      value = (int32_t)inputs.next();
    }
  }
  result.integers_.assign(ENDPOINT_CHANNELS, 0);
  level.cluster_(block.data(), trial.size_, result.integers_.data());
}

void run_assign(const level_kernels& level, const kernel_trial& trial, random_generator& inputs,
                kernel_result& result) {
  const vector<int32_t> colors = inputs.integers(ENDPOINT_CHANNELS, 0, MAXIMUM_CHANNEL);
  result.bytes_.assign((size_t)trial.size_ * trial.size_, 0);
  level.assign_(trial.source_.data(), trial.size_ * trial.size_, colors.data(), result.bytes_.data());
}

void run_assign_pattern(const level_kernels& level, const kernel_trial& trial, random_generator& inputs,
                        kernel_result& result) {
  const vector<int32_t> colors = inputs.integers(ENDPOINT_CHANNELS, 0, MAXIMUM_CHANNEL);
  result.bytes_.assign((size_t)trial.size_ * trial.size_, 0);
  result.measured_ = level.assign_pattern_(trial.source_.data(), trial.size_, colors.data(), result.bytes_.data());
}

void run_seeded(const level_kernels& level, const kernel_trial& trial, random_generator& inputs,
                kernel_result& result) {
  const int32_t width = inputs.range(1, 120);
  const int32_t height = inputs.range(1, 90);
  const vector<uint8_t> reference = inputs.unsigned_bytes((size_t)width * height * CHANNELS);
  vector<int32_t> seeds((size_t)inputs.range(0, 6));
  for (int32_t& seed : seeds) {
    const int32_t horizontal_motion = inputs.range(-MAXIMUM_MOTION, MAXIMUM_MOTION);
    const int32_t vertical_motion = inputs.range(-MAXIMUM_MOTION, MAXIMUM_MOTION);
    seed = (int32_t)((uint32_t)horizontal_motion << HORIZONTAL_MOTION_SHIFT) | (vertical_motion & VERTICAL_MOTION_MASK);
  }

  vector<int32_t> block = trial.source_;
  if (inputs.range(0, OUT_OF_RANGE_ODDS) == 0) {
    const int32_t value = (int32_t)inputs.next();
    const int32_t position = inputs.range(0, (int32_t)block.size() - 1);
    block[position] = value;
  }
  const int32_t horizontal_position = inputs.range(0, width - 1);
  const int32_t vertical_position = inputs.range(0, height - 1);
  const int32_t search_range = inputs.range(0, 24);
  result.measured_ = level.seeded_(reference.data(), width, height, block.data(), horizontal_position,
                                   vertical_position, trial.size_, search_range, seeds.data(), (int32_t)seeds.size());
}

void run_load_source(const level_kernels& level, const kernel_trial& trial, random_generator& inputs,
                     kernel_result& result) {
  const int32_t width = inputs.range(1, 70);
  const int32_t height = inputs.range(1, 70);
  const vector<uint8_t> image = inputs.unsigned_bytes((size_t)width * height * CHANNELS);
  const int32_t horizontal_position = inputs.range(0, width - 1);
  const int32_t vertical_position = inputs.range(0, height - 1);
  level.load_source_(image.data(), width, height, horizontal_position, vertical_position, trial.size_,
                     result.output_.data());
}

void run_residual_target(const level_kernels& level, const kernel_trial& trial, random_generator& inputs,
                         kernel_result& result) {
  const int32_t count = inputs.range(0, trial.size_ * trial.size_);
  result.floats_ = inputs.floats((size_t)count + 2);
  level.residual_target_(trial.source_.data(), trial.prediction_.data(), count, result.floats_.data());
}

kernel_result run_level(const level_kernels& level, const kernel_trial& trial, random_generator& inputs) {
  kernel_result result;
  result.output_.assign(trial.channels_, 0);
  switch (trial.kernel_) {
  case PREDICTED:
    result.measured_ = level.predicted_(trial.prediction_.data(), trial.size_, result.output_.data(),
                                        trial.source_.data(), trial.rate_, trial.limit_);
    break;
  case SOLID:
    result.measured_ = level.solid_(inputs.range(0, 0xFFFFFF), trial.size_, result.output_.data(), trial.source_.data(),
                                    trial.rate_, trial.limit_);
    break;
  case PALETTE:
    run_palette(level, trial, inputs, result);
    break;
  case COMPACT:
    run_compact(level, trial, inputs, result);
    break;
  case PREDICT:
    run_predict(level, trial, inputs, result);
    break;
  case FIT:
    run_fit(level, trial, inputs, result);
    break;
  case CLUSTER:
    run_cluster(level, trial, inputs, result);
    break;
  case ASSIGN:
    run_assign(level, trial, inputs, result);
    break;
  case ASSIGN_PATTERN:
    run_assign_pattern(level, trial, inputs, result);
    break;
  case SEEDED:
    run_seeded(level, trial, inputs, result);
    break;
  case LOAD_SOURCE:
    run_load_source(level, trial, inputs, result);
    break;
  case HALVE:
    level.halve_(trial.source_.data(), trial.size_, result.output_.data());
    break;
  default:
    run_residual_target(level, trial, inputs, result);
    break;
  }
  return result;
}

void add_result(fnv_digest& digest, const kernel_result& result) {
  digest.add(result.output_);
  digest.add(result.integers_);
  digest.add(result.floats_);
  digest.add(result.bytes_);
  digest.add(result.measured_);
}

bool same_result(const kernel_result& actual, const kernel_result& expected) {
  return same(actual.output_, expected.output_) && same(actual.integers_, expected.integers_) &&
         same(actual.floats_, expected.floats_) && same(actual.bytes_, expected.bytes_) &&
         actual.measured_ == expected.measured_;
}

void compare_levels(const vector<level_kernels>& levels, const kernel_trial& trial, const random_generator& random,
                    fnv_digest& digest) {
  kernel_result reference;
  for (const level_kernels& level : levels) {
    random_generator inputs = random;
    const kernel_result result = run_level(level, trial, inputs);
    if (&level == &levels[0]) {
      add_result(digest, result);
      reference = result;
      continue;
    }
    report(same_result(result, reference), KERNEL_NAMES[trial.kernel_], level, trial.index_);
  }
}

uint64_t digest_kernel(const vector<level_kernels>& levels, const int kernel, const int trials) {
  random_generator random{RANDOM_SEED * (kernel + 1)};
  fnv_digest digest;
  for (int index = 0; index < trials; index++) {
    const kernel_trial trial = draw_trial(kernel, index, random);
    compare_levels(levels, trial, random, digest);
    random.next();
  }
  return digest.value_;
}

void run(const vector<level_kernels>& levels, const int trials) {
  fnv_digest total;
  for (int kernel = 0; kernel < KERNEL_COUNT; kernel++) {
    const uint64_t digest = digest_kernel(levels, kernel, trials);
    printf(DIGEST_LINE_FORMAT, KERNEL_NAMES[kernel], (unsigned long long)digest);
    total.add(digest);
  }
  printf(DIGEST_LINE_FORMAT, "all", (unsigned long long)total.value_);
}

const char* best_level_name(const int32_t available) {
  const char* name = "none";
  for (const known_level& level : KNOWN_LEVELS) {
    if (available & level.bit_) {
      name = level.name_;
    }
  }
  return name;
}

#ifdef MCV2_TEST_DIRECT
bool is_available(const level_kernels& level, const int32_t available) {
  for (const known_level& candidate : KNOWN_LEVELS) {
    if (strcmp(candidate.name_, level.name_) == 0 && (available & candidate.bit_)) {
      return true;
    }
  }
  return false;
}
#endif
}

int main(int argument_count, char** arguments) {
  vector<level_kernels> levels;

  const char* expected = nullptr;
  if (argument_count > 1 && strncmp(arguments[argument_count - 1], EXPECT_PREFIX, EXPECT_PREFIX_LENGTH) == 0) {
    expected = arguments[--argument_count] + EXPECT_PREFIX_LENGTH;
  }
#ifdef MCV2_TEST_DIRECT
  if (argument_count != 1) {
    fprintf(stderr, "usage: %s [expect=level]\n", arguments[0]);
    return SETUP_FAILURE_EXIT_CODE;
  }

  const int32_t available = mcv2_cpu_levels(kernel_capabilities());
  const level_kernels linked[] = {
      {"scalar", MCV2_KERNELS(MCV2_ENTRY_scalar)},
#if defined(__x86_64__)
      {"sse2", MCV2_KERNELS(MCV2_ENTRY_sse2)},     {"sse41", MCV2_KERNELS(MCV2_ENTRY_sse41)},
      {"avx2", MCV2_KERNELS(MCV2_ENTRY_avx2)},     {"avx512", MCV2_KERNELS(MCV2_ENTRY_avx512)},
#elif defined(__aarch64__)
      {"neon", MCV2_KERNELS(MCV2_ENTRY_neon)},
      {"sve256", MCV2_KERNELS(MCV2_ENTRY_sve256)},
      {"sve512", MCV2_KERNELS(MCV2_ENTRY_sve512)},
#endif
  };
  for (const level_kernels& level : linked) {
    if (is_available(level, available)) {
      levels.push_back(level);
    }
  }
#else
  if (argument_count != LIBRARY_ARGUMENT_COUNT) {
    fprintf(stderr, "usage: %s library [expect=level]\n", arguments[0]);
    return SETUP_FAILURE_EXIT_CODE;
  }

  void* library = dlopen(arguments[1], RTLD_NOW | RTLD_LOCAL);
  if (library == nullptr) {
    fprintf(stderr, "cannot load %s: %s\n", arguments[1], dlerror());
    return SETUP_FAILURE_EXIT_CODE;
  }

  const int32_t available = ((int32_t(*)(int64_t))dlsym(library, CPU_LEVELS_SYMBOL))(kernel_capabilities());
  for (const known_level& candidate : KNOWN_LEVELS) {
    if (!(available & candidate.bit_)) {
      continue;
    }
    level_kernels level{};
    level.name_ = candidate.name_;
    char symbol[SYMBOL_CAPACITY];
#define MCV2_LOAD(type, kernel, parameters)                                                                            \
  snprintf(symbol, sizeof(symbol), KERNEL_SYMBOL_FORMAT, candidate.name_, #kernel);                                    \
  level.kernel##_ = (type(*) parameters)dlsym(library, symbol);                                                        \
  if (level.kernel##_ == nullptr) {                                                                                    \
    fprintf(stderr, "missing %s\n", symbol);                                                                           \
    return SETUP_FAILURE_EXIT_CODE;                                                                                    \
  }
    MCV2_KERNELS(MCV2_LOAD)
    levels.push_back(level);
  }
#endif
  printf("levels:");
  for (const level_kernels& level : levels) {
    printf(" %s", level.name_);
  }
  printf("\ndispatched: %s\n", best_level_name(available));

  if (expected != nullptr && strcmp(expected, best_level_name(available)) != 0) {
    fprintf(stderr, "expected the %s level, the dispatcher takes %s\n", expected, best_level_name(available));
    return FAILURE_EXIT_CODE;
  }

  run(levels, TRIALS);

  if (failures) {
    fprintf(stderr, "%d differences\n", failures);
    return FAILURE_EXIT_CODE;
  }

  printf("every level agrees\n");
  return 0;
}
