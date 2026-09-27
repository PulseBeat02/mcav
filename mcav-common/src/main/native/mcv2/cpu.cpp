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

// Which levels this CPU runs. Compiled without any extended instruction set, so it runs on every CPU of its
// architecture: an x86-64 level needs its instructions (cpuid) and, for AVX2, the operating system saving the YMM
// registers (xgetbv); every AArch64 CPU has NEON.
#include "mcv2_kernels.h"

#if defined(__x86_64__) || defined(_M_X64)
#include <cpuid.h>

namespace {

constexpr unsigned SSE41_BIT = 1u << 19;
constexpr unsigned OSXSAVE_BIT = 1u << 27;
constexpr unsigned AVX_BIT = 1u << 28;
constexpr unsigned AVX2_BIT = 1u << 5;
// XCR0: the XMM and YMM state, both saved by the operating system
constexpr unsigned long long YMM_STATE = 0x6;

unsigned long long xcr0() {
  unsigned low;
  unsigned high;
  __asm__ volatile("xgetbv" : "=a"(low), "=d"(high) : "c"(0));
  return ((unsigned long long)high << 32) | low;
}

} // namespace

extern "C" int32_t mcv2_cpu_levels(void) {
  int32_t levels = MCV2_LEVEL_SCALAR;
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
  const bool ymm = (ecx & OSXSAVE_BIT) && (ecx & AVX_BIT) && (xcr0() & YMM_STATE) == YMM_STATE;
  if (ymm && __get_cpuid_count(7, 0, &eax, &ebx, &ecx, &edx) && (ebx & AVX2_BIT)) {
    levels |= MCV2_LEVEL_AVX2;
  }
  return levels;
}

#elif defined(__aarch64__) || defined(_M_ARM64)

extern "C" int32_t mcv2_cpu_levels(void) { return MCV2_LEVEL_SCALAR | MCV2_LEVEL_NEON; }

#else

extern "C" int32_t mcv2_cpu_levels(void) { return MCV2_LEVEL_SCALAR; }

#endif

extern "C" int32_t mcv2_abi(void) { return MCV2_ABI; }
