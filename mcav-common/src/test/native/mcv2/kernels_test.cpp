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

// The standalone test of the MCV2 native kernels, outside any JVM: every kernel, on inputs from a fixed pseudo-random
// sequence in the ranges the encoder gives it, at every level this CPU runs, must write exactly what the scalar level
// writes, and the scalar level's outputs fold into one digest per kernel. The JVM tests prove the scalar level equal to
// Java on x86-64, so an equal digest from another platform's library proves it equal to Java too; that is how the
// linux-aarch64 library is checked, under qemu-user. Built two ways (run-native-tests.sh): with the level sources
// linked in (-DMCV2_TEST_DIRECT), under AddressSanitizer and UndefinedBehaviorSanitizer or for llvm-cov coverage; or
// against a shipped library, loaded with dlopen from the path given as the only argument.
//
//   kernels_test [library]      exits 0 when every level agrees, printing the digests

#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include <vector>

#include "mcv2_kernels.h"

#ifndef MCV2_TEST_DIRECT
#include <dlfcn.h>
#endif

namespace {

// the kernels of one level
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
#define MCV2_DECLARE_sse41(type, name, parameters) type mcv2_sse41_##name parameters;
#define MCV2_DECLARE_avx2(type, name, parameters) type mcv2_avx2_##name parameters;
#define MCV2_DECLARE_neon(type, name, parameters) type mcv2_neon_##name parameters;
#define MCV2_ENTRY_scalar(type, name, parameters) mcv2_scalar_##name,
#define MCV2_ENTRY_sse41(type, name, parameters) mcv2_sse41_##name,
#define MCV2_ENTRY_avx2(type, name, parameters) mcv2_avx2_##name,
#define MCV2_ENTRY_neon(type, name, parameters) mcv2_neon_##name,
} // namespace
MCV2_DECLARE_LEVEL(scalar)
#if defined(__x86_64__)
MCV2_DECLARE_LEVEL(sse41)
MCV2_DECLARE_LEVEL(avx2)
#elif defined(__aarch64__)
MCV2_DECLARE_LEVEL(neon)
#endif
namespace {
#endif

// splitmix64: the same sequence on every platform
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
  // the fits' values, now and then an extreme one
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

// FNV-1a over what the scalar level wrote
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
  // an empty vector's data may be null, which memcmp must not be given
  return a.size() == b.size() && (a.empty() || memcmp(a.data(), b.data(), a.size() * sizeof(T)) == 0);
}

void report(bool equal, const char *kernel, const Level &level, int trial) {
  if (!equal && failures++ < 20) {
    fprintf(stderr, "%s differs at %s, trial %d\n", kernel, level.name, trial);
  }
}

constexpr int32_t KINDS[] = {0, 1, 2, 3, 4, 8};

// runs every kernel TRIALS times on every level, comparing with the first (scalar) level
void run(const std::vector<Level> &levels, int trials) {
  const Level &scalar = levels[0];
  const char *names[] = {"predicted", "solid",         "palette",   "intra_grid",   "residual_grid",   "reduced",
                         "compact",   "predict",       "fit",       "cell_sums",    "luma_residual",   "cluster",
                         "palette_cluster", "assign",  "assign_pattern", "seeded", "load_source", "halve",
                         "ycocg",     "residual_target", "cell_means"};
  Digest total;
  for (int kernel = 0; kernel < 21; kernel++) {
    Random random{0x6D637632ull * (kernel + 1)};
    Digest digest;
    for (int trial = 0; trial < trials; trial++) {
      const int32_t size = 8 << random.range(0, 2);
      const size_t channels = (size_t)size * size * 3;
      const std::vector<int32_t> source = random.ints(channels, 0, 255);
      const std::vector<int32_t> prediction = random.ints(channels, 0, 1020);
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
          const int32_t offset = inputs.range(0, 3);
          const std::vector<int8_t> record = inputs.bytes(offset + 6 + size * size / 8);
          measured = level.palette(record.data(), offset, size, out.data(), source.data(), rate, limit);
          break;
        }
        case 3: {
          const int32_t grid = 1 << inputs.range(0, 3);
          const std::vector<int8_t> record = inputs.bytes(3 * grid * grid);
          measured = level.intra_grid(record.data(), 0, grid, size, out.data(), source.data(), rate, limit);
          break;
        }
        case 4: {
          const int32_t grid = 1 << inputs.range(0, 3);
          const int32_t q = inputs.range(0, 4);
          const std::vector<int8_t> record = inputs.bytes(3 * grid * grid);
          measured = level.residual_grid(prediction.data(), record.data(), 0, grid, q, size, out.data(), source.data(),
                                         rate, limit);
          break;
        }
        case 5: {
          const int32_t luma = 1 << inputs.range(0, 3);
          const int32_t chroma = 1 << inputs.range(0, 2);
          const int32_t q = inputs.range(0, 4);
          const int32_t intra = inputs.range(0, 1);
          const std::vector<int8_t> record = inputs.bytes(luma * luma + 2 * chroma * chroma);
          measured = level.reduced(intra ? nullptr : prediction.data(), intra, record.data(), 0, luma, chroma, q, size,
                                   out.data(), source.data(), rate, limit);
          break;
        }
        case 6: {
          const int32_t kind = KINDS[inputs.range(0, 5)];
          const int32_t q = inputs.range(0, 4);
          const std::vector<int8_t> record = inputs.bytes(20);
          measured =
              level.compact(prediction.data(), record.data(), 0, kind, q, size, out.data(), source.data(), rate, limit);
          break;
        }
        case 7: {
          const int32_t width = inputs.range(1, 80);
          const int32_t height = inputs.range(1, 60);
          const std::vector<uint8_t> reference = inputs.ubytes((size_t)width * height * 3);
          level.predict(reference.data(), width, height, inputs.range(-10, width + 10), inputs.range(-10, height + 10),
                        size, inputs.range(-40, 40), inputs.range(-40, 40), out.data());
          break;
        }
        case 8: {
          const int32_t grid = 1 << inputs.range(0, 3);
          const int32_t stride = inputs.range(0, 1) ? 3 : 1;
          const std::vector<float> values = inputs.floats((size_t)size * size * stride);
          const std::vector<float> matrix = inputs.floats((size_t)grid * size);
          floats.assign((size_t)grid * grid, 0);
          level.fit(values.data(), 0, stride, size, grid, matrix.data(), floats.data(), 0, 1);
          break;
        }
        case 9:
          ints.assign(48, 0);
          level.cell_sums(source.data(), size, ints.data());
          break;
        case 10:
          floats.assign(16, 0);
          level.luma_residual(source.data(), prediction.data(), size, floats.data());
          break;
        case 11:
        case 12: {
          // sometimes nearly flat, so the clusters meet ties and empty sides
          const int32_t low = inputs.range(0, 255);
          const int32_t spread = inputs.range(0, 1) ? 8 : 255;
          const std::vector<int32_t> block = inputs.ints(channels, low, low + spread > 255 ? 255 : low + spread);
          floats.assign(6, 0);
          if (kernel == 11) {
            level.cluster(block.data(), size, floats.data());
          } else {
            level.palette_cluster(block.data(), size * size, floats.data());
          }
          break;
        }
        case 13:
        case 14: {
          const std::vector<int32_t> colors = inputs.ints(6, 0, 255);
          bytes.assign((size_t)size * size, 0);
          if (kernel == 13) {
            level.assign(source.data(), size * size, colors.data(), bytes.data());
          } else {
            measured = level.assign_pattern(source.data(), size, colors.data(), bytes.data());
          }
          break;
        }
        case 15: {
          const int32_t width = inputs.range(1, 120);
          const int32_t height = inputs.range(1, 90);
          const std::vector<uint8_t> reference = inputs.ubytes((size_t)width * height * 3);
          std::vector<int32_t> seeds((size_t)inputs.range(0, 6));
          for (auto &seed : seeds) {
            seed = (int32_t)((uint32_t)inputs.range(-40, 40) << 16) | (inputs.range(-40, 40) & 0xFFFF);
          }
          measured = level.seeded(reference.data(), width, height, source.data(), inputs.range(0, width - 1),
                                  inputs.range(0, height - 1), size, inputs.range(-10, 10), inputs.range(-10, 10),
                                  inputs.range(0, 24), inputs.range(0, 1), seeds.data(), (int32_t)seeds.size());
          break;
        }
        case 16: {
          const int32_t width = inputs.range(1, 70);
          const int32_t height = inputs.range(1, 70);
          const std::vector<uint8_t> image = inputs.ubytes((size_t)width * height * 3);
          level.load_source(image.data(), width, height, inputs.range(0, width - 1), inputs.range(0, height - 1), size,
                            out.data());
          break;
        }
        case 17:
          level.halve(source.data(), size, out.data());
          break;
        case 18:
          floats = inputs.floats(channels);
          level.ycocg(source.data(), size * size, inputs.range(0, 1), floats.data());
          break;
        case 19: {
          const std::vector<float> ycocg = inputs.floats(channels);
          floats = inputs.floats(channels);
          level.residual_target(ycocg.data(), prediction.data(), size * size, inputs.range(0, 1), floats.data());
          break;
        }
        default: {
          const std::vector<float> target = inputs.floats(channels);
          const int32_t grid = 1 << inputs.range(0, 3);
          floats.assign(1 + (size_t)grid * grid * 2, 0);
          level.cell_means(target.data(), size, inputs.range(0, 2), grid, floats.data(), 1, 2);
          break;
        }
        }
        // the scalar level's outputs are the reference: fold them into the digest, compare every other level's
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
      // every level drew the same inputs from a copy; the next trial's start one step on
      random.next();
    }
    printf("%-16s %016llx\n", names[kernel], (unsigned long long)digest.value);
    total.add(digest.value);
  }
  printf("%-16s %016llx\n", "all", (unsigned long long)total.value);
}

} // namespace

int main(int argc, char **argv) {
  std::vector<Level> levels;
#ifdef MCV2_TEST_DIRECT
  (void)argc;
  (void)argv;
  const int32_t available = mcv2_cpu_levels();
  levels.push_back({"scalar", MCV2_KERNELS(MCV2_ENTRY_scalar)});
#if defined(__x86_64__)
  if (available & MCV2_LEVEL_SSE41) {
    levels.push_back({"sse41", MCV2_KERNELS(MCV2_ENTRY_sse41)});
  }
  if (available & MCV2_LEVEL_AVX2) {
    levels.push_back({"avx2", MCV2_KERNELS(MCV2_ENTRY_avx2)});
  }
#elif defined(__aarch64__)
  if (available & MCV2_LEVEL_NEON) {
    levels.push_back({"neon", MCV2_KERNELS(MCV2_ENTRY_neon)});
  }
#endif
#else
  if (argc != 2) {
    fprintf(stderr, "usage: %s library\n", argv[0]);
    return 2;
  }
  void *library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
  if (library == nullptr) {
    fprintf(stderr, "cannot load %s: %s\n", argv[1], dlerror());
    return 2;
  }
  const int32_t available = ((int32_t(*)(void))dlsym(library, "mcv2_cpu_levels"))();
  const struct {
    const char *name;
    int32_t bit;
  } known[] = {{"scalar", MCV2_LEVEL_SCALAR}, {"sse41", MCV2_LEVEL_SSE41}, {"avx2", MCV2_LEVEL_AVX2},
               {"neon", MCV2_LEVEL_NEON}};
  for (const auto &candidate : known) {
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
  printf("\n");
  run(levels, 400);
  if (failures) {
    fprintf(stderr, "%d differences\n", failures);
    return 1;
  }
  printf("every level agrees\n");
  return 0;
}
