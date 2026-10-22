#!/usr/bin/env bash
#
# This file is part of mcav, a media playback library for Java
# Copyright (C) Brandon Li <https://brandonli.me/>
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.
#

set -euo pipefail

SCRIPT_DIRECTORY="$(cd "$(dirname "${0}")" && pwd)"
readonly SCRIPT_DIRECTORY
readonly NATIVE_SOURCES="${SCRIPT_DIRECTORY}/../../../main/native/mcv2"
readonly NATIVE_LIBRARIES="${SCRIPT_DIRECTORY}/../../../../build/generated/natives/mcav/mcv2/natives"
readonly X86_LIBRARY="${NATIVE_LIBRARIES}/linux-x86_64/libmcv2kernels.so"
readonly AARCH64_LIBRARY="${NATIVE_LIBRARIES}/linux-aarch64/libmcv2kernels.so"
TEST_DIRECTORY="${WORK:-$(mktemp -d)}"
readonly TEST_DIRECTORY

readonly COMPILER="${CLANG:-clang++}"
readonly LLVM_TOOLS="${LLVM_BIN:-/usr/lib/llvm-18/bin}"
readonly ZIG_COMPILER="${ZIG:-zig}"
readonly X86_EMULATOR="${QEMU_X86_64:-qemu-x86_64-static}"
readonly AARCH64_EMULATOR="${QEMU_AARCH64:-qemu-aarch64-static}"
readonly AARCH64_SYSROOT="${SYSROOT_AARCH64:?must be the path of the aarch64 sysroot!}"
readonly INSTRUCTION_EMULATOR="${SDE:?must be the path of sde64, Intel SDE!}"
readonly X86_MUSL_LOADER="${MUSL_X86_64:?must be the path of ld-musl-x86_64.so.1!}"
readonly AARCH64_MUSL_LOADER="${MUSL_AARCH64:?must be the path of ld-musl-aarch64.so.1!}"

RESOURCE_OPTIONS=()
[ -n "${RESOURCE_DIR:-}" ] && RESOURCE_OPTIONS=(-resource-dir="${RESOURCE_DIR}")
readonly RESOURCE_OPTIONS
readonly KERNEL_FLAGS=(-std=c++17 -ffp-contract=off -fwrapv -fno-strict-aliasing -Wall -Wextra -Werror -I"${NATIVE_SOURCES}")
readonly SANITIZER_FLAGS=(-O1 -g "-fsanitize=address,undefined" -fno-sanitize-recover=all -fno-omit-frame-pointer)

readonly AARCH64_FLAGS=(--target=aarch64-linux-gnu --sysroot="${AARCH64_SYSROOT}" -fuse-ld=lld -Wno-unused-command-line-argument)

readonly AVX512_FLAGS="-mavx512f -mavx512dq -mavx512bw -mavx512vl -mavx512vbmi -mavx512vbmi2 -mavx512vnni -mavx512bitalg"
# shellcheck disable=SC2034 # read through the nameref of compile_direct_test and check_symbols
readonly X86_UNITS=(level_scalar level_sse2 level_sse41:-msse4.1 level_avx2:-mavx2 "level_avx512:${AVX512_FLAGS}")
# shellcheck disable=SC2034 # read through the nameref of compile_direct_test and check_symbols
readonly AARCH64_UNITS=(level_scalar level_neon "level_sve256:-march=armv8-a+sve -msve-vector-bits=256"
  "level_sve512:-march=armv8-a+sve -msve-vector-bits=512")

readonly EMULATED_X86_CPUS=(spr:avx512 icx:avx512 skx:avx2 hsw:avx2 mrm:sse2)
readonly AARCH64_CPUS=(cortex-a72:neon "max,sve-default-vector-length=16:neon" "max,sve-default-vector-length=32:sve256"
  "max,sve-default-vector-length=64:sve512")

readonly FAILURE_EXIT_CODE=1

compile_direct_test() {
  local -r name="${1}"
  local -n units="${2}"
  shift 2
  local object_files=()
  for unit in "${units[@]}" test; do
    local unit_name="${unit%%:*}" instruction_flags=()
    [ "${unit}" != "${unit_name}" ] && read -r -a instruction_flags <<< "${unit#*:}"
    local source_file="${NATIVE_SOURCES}/${unit_name}.cpp"
    [ "${unit_name}" = test ] && source_file="${SCRIPT_DIRECTORY}/kernels_test.cpp"
    "${COMPILER}" "${RESOURCE_OPTIONS[@]}" "${KERNEL_FLAGS[@]}" -DMCV2_TEST_DIRECT "${@}" "${instruction_flags[@]}" -c "${source_file}" -o "${TEST_DIRECTORY}/${name}-${unit_name}.o"
    object_files+=("${TEST_DIRECTORY}/${name}-${unit_name}.o")
  done
  "${COMPILER}" "${RESOURCE_OPTIONS[@]}" "${@}" "${object_files[@]}" -o "${TEST_DIRECTORY}/${name}"
}

compile_canary() {
  local -r name="${1}"
  shift
  printf '#include <stdlib.h>\nint main(int argument_count, char **arguments) { char *buffer = calloc(16, 1); return buffer[15 + argument_count] + !arguments; }\n' \
    > "${TEST_DIRECTORY}/canary.c"
  "${COMPILER%++}" "${RESOURCE_OPTIONS[@]}" "${@}" -O1 -fsanitize=address "${TEST_DIRECTORY}/canary.c" -o "${TEST_DIRECTORY}/${name}"
}

assert_overflow_caught() {
  if ASAN_OPTIONS=detect_leaks=0 "${@}" > /dev/null 2> "${TEST_DIRECTORY}/caught.txt" || ! grep -q heap-buffer-overflow "${TEST_DIRECTORY}/caught.txt"; then
    echo "The sanitizer under ${*} missed a heap overflow!" >&2
    exit "${FAILURE_EXIT_CODE}"
  fi
}

assert_levels_agree() {
  local -r label="${1}"
  shift
  "${@}" > "${TEST_DIRECTORY}/${label}.txt"
  printf '%-44s %s  %s\n' "${label}" "$(sed -n 's/^dispatched: //p' "${TEST_DIRECTORY}/${label}.txt")" \
    "$(sed -n 's/^all *//p' "${TEST_DIRECTORY}/${label}.txt")"
}

echo "== AddressSanitizer + UndefinedBehaviorSanitizer"
compile_direct_test sanitized-x86_64 X86_UNITS "${SANITIZER_FLAGS[@]}"
compile_canary canary-x86_64
assert_overflow_caught "${TEST_DIRECTORY}/canary-x86_64"
assert_overflow_caught "${INSTRUCTION_EMULATOR}" -icx -- "${TEST_DIRECTORY}/canary-x86_64"
ASAN_OPTIONS=detect_leaks=1 assert_levels_agree "asan x86-64 (this CPU)" "${TEST_DIRECTORY}/sanitized-x86_64"
ASAN_OPTIONS=detect_leaks=0 assert_levels_agree "asan x86-64 (SDE -icx)" "${INSTRUCTION_EMULATOR}" -icx -- "${TEST_DIRECTORY}/sanitized-x86_64" expect=avx512
compile_direct_test sanitized-aarch64 AARCH64_UNITS "${AARCH64_FLAGS[@]}" "${SANITIZER_FLAGS[@]}"
compile_canary canary-aarch64 "${AARCH64_FLAGS[@]}"
assert_overflow_caught "${AARCH64_EMULATOR}" -L "${AARCH64_SYSROOT}" "${TEST_DIRECTORY}/canary-aarch64"
for entry in "${AARCH64_CPUS[@]}"; do
  ASAN_OPTIONS=detect_leaks=0 assert_levels_agree "asan aarch64 (qemu -cpu ${entry%%:*})" \
    "${AARCH64_EMULATOR}" -L "${AARCH64_SYSROOT}" -cpu "${entry%%:*}" "${TEST_DIRECTORY}/sanitized-aarch64" "expect=${entry#*:}"
done

echo "== symbols"

check_symbols() {
  local -r label="${1}"
  shift
  local -n units="${1}"
  shift
  for unit in "${units[@]}"; do
    local unit_name="${unit%%:*}" instruction_flags=()
    [ "${unit}" != "${unit_name}" ] && read -r -a instruction_flags <<< "${unit#*:}"
    "${COMPILER}" "${RESOURCE_OPTIONS[@]}" "${KERNEL_FLAGS[@]}" -O0 "${@}" "${instruction_flags[@]}" -c "${NATIVE_SOURCES}/${unit_name}.cpp" -o "${TEST_DIRECTORY}/symbols.o"
    if "${LLVM_TOOLS}/llvm-nm" --defined-only --extern-only "${TEST_DIRECTORY}/symbols.o" | awk '{print $3}' | grep -v '^mcv2_' | grep -q .; then
      echo "Unit ${unit_name} (${label}) defines shared symbols!" >&2
      "${LLVM_TOOLS}/llvm-nm" --defined-only --extern-only -C "${TEST_DIRECTORY}/symbols.o" | grep -v ' mcv2_' >&2
      exit "${FAILURE_EXIT_CODE}"
    fi
  done
  echo "${label}: every unit defines only its kernels"
}
check_symbols x86-64 X86_UNITS
check_symbols aarch64 AARCH64_UNITS "${AARCH64_FLAGS[@]}"

echo "== coverage (llvm-cov)"
compile_direct_test covered X86_UNITS -O1 -fprofile-instr-generate -fcoverage-mapping
LLVM_PROFILE_FILE="${TEST_DIRECTORY}/kernels-%p.profraw" "${INSTRUCTION_EMULATOR}" -icx -- "${TEST_DIRECTORY}/covered" > /dev/null
"${LLVM_TOOLS}/llvm-profdata" merge -o "${TEST_DIRECTORY}/kernels.profdata" "${TEST_DIRECTORY}"/kernels-*.profraw
"${LLVM_TOOLS}/llvm-cov" report "${TEST_DIRECTORY}/covered" -instr-profile="${TEST_DIRECTORY}/kernels.profdata" "${NATIVE_SOURCES}"/*.cpp

echo "== the shipped libraries"
for target in x86_64 aarch64; do
  library="${NATIVE_LIBRARIES}/linux-${target}/libmcv2kernels.so"
  if "${LLVM_TOOLS}/llvm-readelf" --dynamic "${library}" | grep -q NEEDED ||
    "${LLVM_TOOLS}/llvm-readelf" --dyn-syms "${library}" | awk '$7 == "UND" && $8 != ""' | grep -q .; then
    echo "Library linux-${target} imports something!" >&2
    exit "${FAILURE_EXIT_CODE}"
  fi
  "${ZIG_COMPILER}" c++ -target "${target}-linux-gnu.2.28" -O1 "${KERNEL_FLAGS[@]}" "${SCRIPT_DIRECTORY}/kernels_test.cpp" -o "${TEST_DIRECTORY}/glibc-${target}" \
    -ldl 2> /dev/null
  "${ZIG_COMPILER}" c++ -target "${target}-linux-musl" -dynamic -O1 "${KERNEL_FLAGS[@]}" "${SCRIPT_DIRECTORY}/kernels_test.cpp" \
    -o "${TEST_DIRECTORY}/musl-${target}" 2> /dev/null
done
assert_levels_agree "linux-x86_64 glibc (this CPU)" "${TEST_DIRECTORY}/glibc-x86_64" "${X86_LIBRARY}"
assert_levels_agree "linux-x86_64 musl (this CPU)" "${X86_MUSL_LOADER}" "${TEST_DIRECTORY}/musl-x86_64" "${X86_LIBRARY}"
assert_levels_agree "linux-x86_64 glibc (qemu -cpu qemu64)" "${X86_EMULATOR}" -cpu qemu64 "${TEST_DIRECTORY}/glibc-x86_64" "${X86_LIBRARY}" expect=sse2
for entry in "${EMULATED_X86_CPUS[@]}"; do
  assert_levels_agree "linux-x86_64 glibc (SDE -${entry%%:*})" "${INSTRUCTION_EMULATOR}" "-${entry%%:*}" -- "${TEST_DIRECTORY}/glibc-x86_64" "${X86_LIBRARY}" \
    "expect=${entry#*:}"
done
for entry in "${AARCH64_CPUS[@]}"; do
  assert_levels_agree "linux-aarch64 glibc (qemu -cpu ${entry%%:*})" "${AARCH64_EMULATOR}" -L "${AARCH64_SYSROOT}" -cpu "${entry%%:*}" \
    "${TEST_DIRECTORY}/glibc-aarch64" "${AARCH64_LIBRARY}" "expect=${entry#*:}"
done
assert_levels_agree "linux-aarch64 musl (qemu -cpu max,sve 64)" "${AARCH64_EMULATOR}" -cpu max,sve-default-vector-length=64 \
  "${AARCH64_MUSL_LOADER}" "${TEST_DIRECTORY}/musl-aarch64" "${AARCH64_LIBRARY}" expect=sve512
reference="$(tail -n +3 "${TEST_DIRECTORY}/asan x86-64 (this CPU).txt")"
for run in "${TEST_DIRECTORY}"/*.txt; do
  [ "$(basename "${run}")" = caught.txt ] && continue
  if [ "$(tail -n +3 "${run}")" != "${reference}" ]; then
    echo "Run $(basename "${run}" .txt) has other digests!" >&2
    exit "${FAILURE_EXIT_CODE}"
  fi
done
echo "every level on every CPU agrees, digests identical"
