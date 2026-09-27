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
// architecture. An x86-64 level needs its instructions (cpuid) and, for AVX2 and AVX-512, the operating system saving
// their registers (xgetbv); AVX-512 runs only with the Ice Lake feature set, never on the CPUs that slow down at 512
// bits. On AArch64 NEON is always there, and SVE where the Linux kernel says so in AT_HWCAP, which Java passes in: the
// vector length then chooses the fixed-length level, and at 128 bits NEON stays.
#include "mcv2_kernels.h"

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
