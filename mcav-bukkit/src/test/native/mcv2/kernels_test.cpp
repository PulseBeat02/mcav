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
    uint64_t mixed_value = (state_ += 0x9E3779B97F4A7C15ull);
    mixed_value = (mixed_value ^ (mixed_value >> 30)) * 0xBF58476D1CE4E5B9ull;
    mixed_value = (mixed_value ^ (mixed_value >> 27)) * 0x94D049BB133111EBull;
    return mixed_value ^ (mixed_value >> 31);
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
        value = 1.401298464e-45f * (float)range(1, 1000);
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
  uint64_t value_ = 0xCBF29CE484222325ull;
  void add(const void* data, const size_t length) {
    const uint8_t* bytes = (const uint8_t*)data;
    for (size_t index = 0; index < length; index++) {
      value_ = (value_ ^ bytes[index]) * 0x100000001B3ull;
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
  if (!equal && failures++ < 20) {
    fprintf(stderr, "%s differs at %s, trial %d\n", kernel, level.name_, trial);
  }
}

void run(const vector<level_kernels>& levels, const int trials) {
  const level_kernels& scalar = levels[0];
  const char* names[] = {"predicted", "solid",  "palette",     "compact", "predict",        "fit", "cluster", "assign",
                         "pattern",   "seeded", "load_source", "halve",   "residual_target"};
  fnv_digest total;
  for (int kernel = 0; kernel < 13; kernel++) {
    random_generator random{0x6D637632ull * (kernel + 1)};
    fnv_digest digest;
    for (int trial = 0; trial < trials; trial++) {
      const int32_t size = 8 << random.range(0, 2);
      const size_t channels = (size_t)size * size * 3;
      const vector<int32_t> source = random.integers(channels, 0, 255);
      const vector<int32_t> prediction = random.integers(channels, 0, 255);
      const double rate = random.range(0, 100000) / 7.0;
      const double limit = random.range(0, 3) == 0 ? INFINITY : rate + random.range(0, size * size * 3000);
      for (const level_kernels& level : levels) {
        random_generator inputs = random;
        vector<int32_t> output(channels, 0);
        int64_t measured = 0;
        vector<int32_t> integers;
        vector<float> floats;
        vector<int8_t> bytes;
        switch (kernel) {
        case 0:
          measured = level.predicted_(prediction.data(), size, output.data(), source.data(), rate, limit);
          break;
        case 1:
          measured = level.solid_(inputs.range(0, 0xFFFFFF), size, output.data(), source.data(), rate, limit);
          break;
        case 2: {
          const vector<int8_t> record = inputs.bytes(6 + size * size / 8);
          measured = level.palette_(record.data(), size, output.data(), source.data(), rate, limit);
          break;
        }
        case 3: {
          const int32_t quantizer = inputs.range(0, 2);
          const vector<int8_t> record = inputs.bytes(10);
          measured = level.compact_(prediction.data(), record.data(), quantizer, size, output.data(), source.data(),
                                    rate, limit);
          break;
        }
        case 4: {
          const int32_t width = inputs.range(1, 80);
          const int32_t height = inputs.range(1, 60);
          const vector<uint8_t> reference = inputs.unsigned_bytes((size_t)width * height * 3);
          level.predict_(reference.data(), width, height, inputs.range(-10, width + 10), inputs.range(-10, height + 10),
                         size, inputs.range(-40, 40), inputs.range(-40, 40), output.data());
          break;
        }
        case 5: {
          const vector<float> values = inputs.floats((size_t)size * size);
          const vector<float> matrix = inputs.floats((size_t)4 * size);
          floats.assign(16, 0);
          level.fit_(values.data(), size, matrix.data(), floats.data());
          break;
        }
        case 6: {

          const int32_t low = inputs.range(0, 255);
          const int32_t spread = inputs.range(0, 1) ? 8 : 255;
          vector<int32_t> block = inputs.integers(channels, low, low + spread > 255 ? 255 : low + spread);
          if (inputs.range(0, 7) == 0) {
            for (int32_t& value : block) {
              value = (int32_t)inputs.next();
            }
          }
          integers.assign(6, 0);
          level.cluster_(block.data(), size, integers.data());
          break;
        }
        case 7:
        case 8: {
          const vector<int32_t> colors = inputs.integers(6, 0, 255);
          bytes.assign((size_t)size * size, 0);
          if (kernel == 7) {
            level.assign_(source.data(), size * size, colors.data(), bytes.data());
          } else {
            measured = level.assign_pattern_(source.data(), size, colors.data(), bytes.data());
          }
          break;
        }
        case 9: {
          const int32_t width = inputs.range(1, 120);
          const int32_t height = inputs.range(1, 90);
          const vector<uint8_t> reference = inputs.unsigned_bytes((size_t)width * height * 3);
          vector<int32_t> seeds((size_t)inputs.range(0, 6));
          for (int32_t& seed : seeds) {
            seed = (int32_t)((uint32_t)inputs.range(-40, 40) << 16) | (inputs.range(-40, 40) & 0xFFFF);
          }

          vector<int32_t> block = source;
          if (inputs.range(0, 7) == 0) {
            block[inputs.range(0, (int32_t)block.size() - 1)] = (int32_t)inputs.next();
          }
          measured = level.seeded_(reference.data(), width, height, block.data(), inputs.range(0, width - 1),
                                   inputs.range(0, height - 1), size, inputs.range(0, 24), seeds.data(),
                                   (int32_t)seeds.size());
          break;
        }
        case 10: {
          const int32_t width = inputs.range(1, 70);
          const int32_t height = inputs.range(1, 70);
          const vector<uint8_t> image = inputs.unsigned_bytes((size_t)width * height * 3);
          level.load_source_(image.data(), width, height, inputs.range(0, width - 1), inputs.range(0, height - 1), size,
                             output.data());
          break;
        }
        case 11:
          level.halve_(source.data(), size, output.data());
          break;
        default: {
          const int32_t count = inputs.range(0, size * size);
          floats = inputs.floats((size_t)count + 2);
          level.residual_target_(source.data(), prediction.data(), count, floats.data());
          break;
        }
        }

        static vector<int32_t> reference_output;
        static vector<int32_t> reference_integers;
        static vector<float> reference_floats;
        static vector<int8_t> reference_bytes;
        static int64_t reference_measured;
        if (&level == &scalar) {
          reference_output = output;
          reference_integers = integers;
          reference_floats = floats;
          reference_bytes = bytes;
          reference_measured = measured;
          digest.add(output);
          digest.add(integers);
          digest.add(floats);
          digest.add(bytes);
          digest.add(measured);
        } else {
          report(same(output, reference_output) && same(integers, reference_integers) &&
                     same(floats, reference_floats) && same(bytes, reference_bytes) && measured == reference_measured,
                 names[kernel], level, trial);
        }
      }

      random.next();
    }
    printf("%-16s %016llx\n", names[kernel], (unsigned long long)digest.value_);
    total.add(digest.value_);
  }
  printf("%-16s %016llx\n", "all", (unsigned long long)total.value_);
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
}

int main(int argument_count, char** arguments) {
  vector<level_kernels> levels;

  const char* expected = nullptr;
  if (argument_count > 1 && strncmp(arguments[argument_count - 1], "expect=", 7) == 0) {
    expected = arguments[--argument_count] + 7;
  }
#ifdef MCV2_TEST_DIRECT
  if (argument_count != 1) {
    fprintf(stderr, "usage: %s [expect=level]\n", arguments[0]);
    return 2;
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
    for (const known_level& candidate : KNOWN_LEVELS) {
      if (strcmp(candidate.name_, level.name_) == 0 && (available & candidate.bit_)) {
        levels.push_back(level);
      }
    }
  }
#else
  if (argument_count != 2) {
    fprintf(stderr, "usage: %s library [expect=level]\n", arguments[0]);
    return 2;
  }
  void* library = dlopen(arguments[1], RTLD_NOW | RTLD_LOCAL);
  if (library == nullptr) {
    fprintf(stderr, "cannot load %s: %s\n", arguments[1], dlerror());
    return 2;
  }
  const int32_t available = ((int32_t(*)(int64_t))dlsym(library, "mcv2_cpu_levels"))(kernel_capabilities());
  for (const known_level& candidate : KNOWN_LEVELS) {
    if (!(available & candidate.bit_)) {
      continue;
    }
    level_kernels level{};
    level.name_ = candidate.name_;
    char symbol[64];
#define MCV2_LOAD(type, kernel, parameters)                                                                            \
  snprintf(symbol, sizeof(symbol), "mcv2_%s_%s", candidate.name_, #kernel);                                            \
  level.kernel##_ = (type(*) parameters)dlsym(library, symbol);                                                        \
  if (level.kernel##_ == nullptr) {                                                                                    \
    fprintf(stderr, "missing %s\n", symbol);                                                                           \
    return 2;                                                                                                          \
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
    return 1;
  }
  run(levels, 400);
  if (failures) {
    fprintf(stderr, "%d differences\n", failures);
    return 1;
  }
  printf("every level agrees\n");
  return 0;
}
