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

#define MCV2_DECLARATIONS_ONLY
#include "mcv2.cpp"

#ifndef MCV2_TEST_DIRECT
#include <dlfcn.h>
#endif
#if defined(__aarch64__) && defined(__linux__)
#include <sys/auxv.h>
#endif

namespace {

struct Level {
  const char *name;
#define MCV2_FIELD(type, name, parameters) type(*name) parameters;
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

constexpr struct {
  const char *name;
  int32_t bit;
} KNOWN[] = {{"scalar", MCV2_LEVEL_SCALAR}, {"sse2", MCV2_LEVEL_SSE2},     {"sse41", MCV2_LEVEL_SSE41},
             {"avx2", MCV2_LEVEL_AVX2},     {"avx512", MCV2_LEVEL_AVX512}, {"neon", MCV2_LEVEL_NEON},
             {"sve256", MCV2_LEVEL_SVE256}, {"sve512", MCV2_LEVEL_SVE512}};

int64_t hwcap() {
#if defined(__aarch64__) && defined(__linux__)
  return (int64_t)getauxval(AT_HWCAP);
#else
  return 0;
#endif
}

struct Random {
  uint64_t state;
  uint64_t next() {
    uint64_t z = (state += 0x9E3779B97F4A7C15ull);
    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ull;
    z = (z ^ (z >> 27)) * 0x94D049BB133111EBull;
    return z ^ (z >> 31);
  }
  int32_t range(int32_t low, int32_t high) { return low + (int32_t)(next() % (uint64_t)(high - low + 1)); }
  std::vector<int32_t> ints(size_t count, int32_t low, int32_t high) {
    std::vector<int32_t> values(count);
    for (auto &value : values) {
      value = range(low, high);
    }
    return values;
  }
  std::vector<int8_t> bytes(size_t count) {
    std::vector<int8_t> values(count);
    for (auto &value : values) {
      value = (int8_t)next();
    }
    return values;
  }
  std::vector<uint8_t> ubytes(size_t count) {
    std::vector<uint8_t> values(count);
    for (auto &value : values) {
      value = (uint8_t)next();
    }
    return values;
  }

  std::vector<float> floats(size_t count) {
    std::vector<float> values(count);
    for (auto &value : values) {
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

struct Digest {
  uint64_t value = 0xCBF29CE484222325ull;
  void add(const void *data, size_t length) {
    const uint8_t *bytes = (const uint8_t *)data;
    for (size_t i = 0; i < length; i++) {
      value = (value ^ bytes[i]) * 0x100000001B3ull;
    }
  }
  template <class T> void add(const std::vector<T> &values) { add(values.data(), values.size() * sizeof(T)); }
  template <class T> void add(T value) { add(&value, sizeof(value)); }
};

int failures = 0;

template <class T> bool same(const std::vector<T> &a, const std::vector<T> &b) {
  // An empty vector may return null data, which memcmp cannot accept.
  return a.size() == b.size() && (a.empty() || memcmp(a.data(), b.data(), a.size() * sizeof(T)) == 0);
}

void report(bool equal, const char *kernel, const Level &level, int trial) {
  if (!equal && failures++ < 20) {
    fprintf(stderr, "%s differs at %s, trial %d\n", kernel, level.name, trial);
  }
}

void run(const std::vector<Level> &levels, int trials) {
  const Level &scalar = levels[0];
  const char *names[] = {"predicted", "solid",  "palette",     "compact", "predict",        "fit", "cluster", "assign",
                         "pattern",   "seeded", "load_source", "halve",   "residual_target"};
  Digest total;
  for (int kernel = 0; kernel < 13; kernel++) {
    Random random{0x6D637632ull * (kernel + 1)};
    Digest digest;
    for (int trial = 0; trial < trials; trial++) {
      const int32_t size = 8 << random.range(0, 2);
      const size_t channels = (size_t)size * size * 3;
      const std::vector<int32_t> source = random.ints(channels, 0, 255);
      const std::vector<int32_t> prediction = random.ints(channels, 0, 255);
      const double rate = random.range(0, 100000) / 7.0;
      const double limit = random.range(0, 3) == 0 ? INFINITY : rate + random.range(0, size * size * 3000);
      for (const Level &level : levels) {
        Random inputs = random;
        std::vector<int32_t> out(channels, 0);
        int64_t measured = 0;
        std::vector<int32_t> ints;
        std::vector<float> floats;
        std::vector<int8_t> bytes;
        switch (kernel) {
        case 0:
          measured = level.predicted(prediction.data(), size, out.data(), source.data(), rate, limit);
          break;
        case 1:
          measured = level.solid(inputs.range(0, 0xFFFFFF), size, out.data(), source.data(), rate, limit);
          break;
        case 2: {
          const std::vector<int8_t> record = inputs.bytes(6 + size * size / 8);
          measured = level.palette(record.data(), size, out.data(), source.data(), rate, limit);
          break;
        }
        case 3: {
          const int32_t q = inputs.range(0, 2);
          const std::vector<int8_t> record = inputs.bytes(10);
          measured = level.compact(prediction.data(), record.data(), q, size, out.data(), source.data(), rate, limit);
          break;
        }
        case 4: {
          const int32_t width = inputs.range(1, 80);
          const int32_t height = inputs.range(1, 60);
          const std::vector<uint8_t> reference = inputs.ubytes((size_t)width * height * 3);
          level.predict(reference.data(), width, height, inputs.range(-10, width + 10), inputs.range(-10, height + 10),
                        size, inputs.range(-40, 40), inputs.range(-40, 40), out.data());
          break;
        }
        case 5: {
          const std::vector<float> values = inputs.floats((size_t)size * size);
          const std::vector<float> matrix = inputs.floats((size_t)4 * size);
          floats.assign(16, 0);
          level.fit(values.data(), size, matrix.data(), floats.data());
          break;
        }
        case 6: {

          const int32_t low = inputs.range(0, 255);
          const int32_t spread = inputs.range(0, 1) ? 8 : 255;
          std::vector<int32_t> block = inputs.ints(channels, low, low + spread > 255 ? 255 : low + spread);
          if (inputs.range(0, 7) == 0) {
            for (auto &value : block) {
              value = (int32_t)inputs.next();
            }
          }
          ints.assign(6, 0);
          level.cluster(block.data(), size, ints.data());
          break;
        }
        case 7:
        case 8: {
          const std::vector<int32_t> colors = inputs.ints(6, 0, 255);
          bytes.assign((size_t)size * size, 0);
          if (kernel == 7) {
            level.assign(source.data(), size * size, colors.data(), bytes.data());
          } else {
            measured = level.assign_pattern(source.data(), size, colors.data(), bytes.data());
          }
          break;
        }
        case 9: {
          const int32_t width = inputs.range(1, 120);
          const int32_t height = inputs.range(1, 90);
          const std::vector<uint8_t> reference = inputs.ubytes((size_t)width * height * 3);
          std::vector<int32_t> seeds((size_t)inputs.range(0, 6));
          for (auto &seed : seeds) {
            seed = (int32_t)((uint32_t)inputs.range(-40, 40) << 16) | (inputs.range(-40, 40) & 0xFFFF);
          }

          std::vector<int32_t> block = source;
          if (inputs.range(0, 7) == 0) {
            block[inputs.range(0, (int32_t)block.size() - 1)] = (int32_t)inputs.next();
          }
          measured =
              level.seeded(reference.data(), width, height, block.data(), inputs.range(0, width - 1),
                           inputs.range(0, height - 1), size, inputs.range(0, 24), seeds.data(), (int32_t)seeds.size());
          break;
        }
        case 10: {
          const int32_t width = inputs.range(1, 70);
          const int32_t height = inputs.range(1, 70);
          const std::vector<uint8_t> image = inputs.ubytes((size_t)width * height * 3);
          level.load_source(image.data(), width, height, inputs.range(0, width - 1), inputs.range(0, height - 1), size,
                            out.data());
          break;
        }
        case 11:
          level.halve(source.data(), size, out.data());
          break;
        default: {
          const int32_t count = inputs.range(0, size * size);
          floats = inputs.floats((size_t)count + 2);
          level.residual_target(source.data(), prediction.data(), count, floats.data());
          break;
        }
        }

        static std::vector<int32_t> reference_out;
        static std::vector<int32_t> reference_ints;
        static std::vector<float> reference_floats;
        static std::vector<int8_t> reference_bytes;
        static int64_t reference_measured;
        if (&level == &scalar) {
          reference_out = out;
          reference_ints = ints;
          reference_floats = floats;
          reference_bytes = bytes;
          reference_measured = measured;
          digest.add(out);
          digest.add(ints);
          digest.add(floats);
          digest.add(bytes);
          digest.add(measured);
        } else {
          report(same(out, reference_out) && same(ints, reference_ints) && same(floats, reference_floats) &&
                     same(bytes, reference_bytes) && measured == reference_measured,
                 names[kernel], level, trial);
        }
      }

      random.next();
    }
    printf("%-16s %016llx\n", names[kernel], (unsigned long long)digest.value);
    total.add(digest.value);
  }
  printf("%-16s %016llx\n", "all", (unsigned long long)total.value);
}

const char *best(int32_t available) {
  const char *name = "none";
  for (const auto &level : KNOWN) {
    if (available & level.bit) {
      name = level.name;
    }
  }
  return name;
}

}

int main(int argc, char **argv) {
  std::vector<Level> levels;

  const char *expected = nullptr;
  if (argc > 1 && strncmp(argv[argc - 1], "expect=", 7) == 0) {
    expected = argv[--argc] + 7;
  }
#ifdef MCV2_TEST_DIRECT
  if (argc != 1) {
    fprintf(stderr, "usage: %s [expect=level]\n", argv[0]);
    return 2;
  }
  const int32_t available = mcv2_cpu_levels(hwcap());
  const Level linked[] = {
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
  for (const Level &level : linked) {
    for (const auto &candidate : KNOWN) {
      if (strcmp(candidate.name, level.name) == 0 && (available & candidate.bit)) {
        levels.push_back(level);
      }
    }
  }
#else
  if (argc != 2) {
    fprintf(stderr, "usage: %s library [expect=level]\n", argv[0]);
    return 2;
  }
  void *library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
  if (library == nullptr) {
    fprintf(stderr, "cannot load %s: %s\n", argv[1], dlerror());
    return 2;
  }
  const int32_t available = ((int32_t(*)(int64_t))dlsym(library, "mcv2_cpu_levels"))(hwcap());
  for (const auto &candidate : KNOWN) {
    if (!(available & candidate.bit)) {
      continue;
    }
    Level level{};
    level.name = candidate.name;
    char symbol[64];
#define MCV2_LOAD(type, kernel, parameters)                                                                            \
  snprintf(symbol, sizeof(symbol), "mcv2_%s_%s", candidate.name, #kernel);                                             \
  level.kernel = (type(*) parameters)dlsym(library, symbol);                                                           \
  if (level.kernel == nullptr) {                                                                                       \
    fprintf(stderr, "missing %s\n", symbol);                                                                           \
    return 2;                                                                                                          \
  }
    MCV2_KERNELS(MCV2_LOAD)
    levels.push_back(level);
  }
#endif
  printf("levels:");
  for (const Level &level : levels) {
    printf(" %s", level.name);
  }
  printf("\ndispatched: %s\n", best(available));
  if (expected != nullptr && strcmp(expected, best(available)) != 0) {
    fprintf(stderr, "expected the %s level, the dispatcher takes %s\n", expected, best(available));
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
