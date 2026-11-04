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
readonly LIBRARY_FILE_NAME="libmcv2kernels.so"
readonly X86_LIBRARY="${NATIVE_LIBRARIES}/linux-x86_64/${LIBRARY_FILE_NAME}"
readonly AARCH64_LIBRARY="${NATIVE_LIBRARIES}/linux-aarch64/${LIBRARY_FILE_NAME}"
TEST_DIRECTORY="${WORK:-$(mktemp -d)}"
readonly TEST_DIRECTORY

readonly COMPILER="${CLANG:-clang++}"
readonly LLVM_TOOLS="${LLVM_BINARIES:-/usr/lib/llvm-18/bin}"
readonly ZIG_COMPILER="${ZIG:-zig}"
readonly X86_EMULATOR="${QEMU_X86_64:-qemu-x86_64-static}"
readonly AARCH64_EMULATOR="${QEMU_AARCH64:-qemu-aarch64-static}"
readonly AARCH64_SYSROOT="${SYSROOT_AARCH64:?must be the path of the aarch64 sysroot!}"
readonly INSTRUCTION_EMULATOR="${SDE:?must be the path of sde64, Intel SDE!}"
readonly X86_MUSL_LOADER="${MUSL_X86_64:?must be the path of ld-musl-x86_64.so.1!}"
readonly AARCH64_MUSL_LOADER="${MUSL_AARCH64:?must be the path of ld-musl-aarch64.so.1!}"

RESOURCE_OPTIONS=()
[ -n "${RESOURCE_DIRECTORY:-}" ] && RESOURCE_OPTIONS=(-resource-dir="${RESOURCE_DIRECTORY}")
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
readonly SVE512_CPU="max,sve-default-vector-length=64"
readonly AARCH64_CPUS=(cortex-a72:neon "max,sve-default-vector-length=16:neon" "max,sve-default-vector-length=32:sve256"
  "${SVE512_CPU}:sve512")

readonly REFERENCE_LABEL="asan x86-64 (this CPU)"
readonly CAUGHT_FILE_NAME="caught.txt"

readonly DISPATCHED_KEY="dispatched: "
readonly TOTAL_DIGEST_KEY="all"
readonly FIRST_DIGEST_LINE=3

readonly OVERFLOW_REPORT="heap-buffer-overflow"
readonly KERNEL_SYMBOL_PREFIX="mcv2_"
readonly NEEDED_ENTRY="NEEDED"

readonly FAILURE_EXIT_CODE=1

main() {
  check_sanitized_kernels
  check_kernel_symbols
  report_kernel_coverage
  check_shipped_libraries
  check_digests_agree
}

check_sanitized_kernels() {
  echo "== AddressSanitizer + UndefinedBehaviorSanitizer"
  check_sanitized_x86_64_kernels
  check_sanitized_aarch64_kernels
}

check_sanitized_x86_64_kernels() {
  local -r canary_program="${TEST_DIRECTORY}/canary-x86_64"
  local -r sanitized_program="${TEST_DIRECTORY}/sanitized-x86_64"
  compile_direct_test sanitized-x86_64 X86_UNITS "${SANITIZER_FLAGS[@]}"
  compile_canary canary-x86_64
  assert_overflow_caught "${canary_program}"
  assert_overflow_caught "${INSTRUCTION_EMULATOR}" -icx -- "${canary_program}"
  ASAN_OPTIONS=detect_leaks=1 assert_levels_agree "${REFERENCE_LABEL}" "${sanitized_program}"
  ASAN_OPTIONS=detect_leaks=0 assert_levels_agree "asan x86-64 (SDE -icx)" "${INSTRUCTION_EMULATOR}" -icx -- \
    "${sanitized_program}" expect=avx512
}

compile_direct_test() {
  local -r name="${1}"
  local -n units="${2}"
  shift 2
  local object_files=()
  local unit
  for unit in "${units[@]}" test; do
    local unit_name instruction_flags=()
    split_unit "${unit}" unit_name instruction_flags
    local source_file="${NATIVE_SOURCES}/${unit_name}.cpp"
    [ "${unit_name}" = test ] && source_file="${SCRIPT_DIRECTORY}/kernels_test.cpp"
    "${COMPILER}" "${RESOURCE_OPTIONS[@]}" "${KERNEL_FLAGS[@]}" -DMCV2_TEST_DIRECT "${@}" "${instruction_flags[@]}" \
      -c "${source_file}" -o "${TEST_DIRECTORY}/${name}-${unit_name}.o"
    object_files+=("${TEST_DIRECTORY}/${name}-${unit_name}.o")
  done

  "${COMPILER}" "${RESOURCE_OPTIONS[@]}" "${@}" "${object_files[@]}" -o "${TEST_DIRECTORY}/${name}"
}

split_unit() {
  local -r unit_specification="${1}"
  local -n unit_name_output="${2}"
  local -n instruction_flags_output="${3}"
  unit_name_output="${unit_specification%%:*}"
  if [ "${unit_specification}" != "${unit_name_output}" ]; then
    read -r -a "${!instruction_flags_output}" <<< "${unit_specification#*:}"
  fi
}

compile_canary() {
  local -r name="${1}"
  shift
  cat > "${TEST_DIRECTORY}/canary.c" << 'EOF'
#include <stdlib.h>
int main(int argument_count, char** arguments) {
  char* buffer = calloc(16, 1);
  return buffer[15 + argument_count] + !arguments;
}
EOF
  "${COMPILER%++}" "${RESOURCE_OPTIONS[@]}" "${@}" -O1 -fsanitize=address "${TEST_DIRECTORY}/canary.c" -o "${TEST_DIRECTORY}/${name}"
}

assert_overflow_caught() {
  local -r caught_file="${TEST_DIRECTORY}/${CAUGHT_FILE_NAME}"
  if ASAN_OPTIONS=detect_leaks=0 "${@}" > /dev/null 2> "${caught_file}" || \
    ! grep -q "${OVERFLOW_REPORT}" "${caught_file}"; then
    echo "The sanitizer under ${*} missed a heap overflow!" >&2
    exit "${FAILURE_EXIT_CODE}"
  fi
}

assert_levels_agree() {
  local -r label="${1}"
  shift
  "${@}" > "${TEST_DIRECTORY}/${label}.txt"
  local dispatched_level
  dispatched_level="$(sed -n "s/^${DISPATCHED_KEY}//p" "${TEST_DIRECTORY}/${label}.txt")"
  local total_digest
  total_digest="$(sed -n "s/^${TOTAL_DIGEST_KEY} *//p" "${TEST_DIRECTORY}/${label}.txt")"
  printf '%-44s %s  %s\n' "${label}" "${dispatched_level}" "${total_digest}"
}

check_sanitized_aarch64_kernels() {
  compile_direct_test sanitized-aarch64 AARCH64_UNITS "${AARCH64_FLAGS[@]}" "${SANITIZER_FLAGS[@]}"
  compile_canary canary-aarch64 "${AARCH64_FLAGS[@]}"
  assert_overflow_caught "${AARCH64_EMULATOR}" -L "${AARCH64_SYSROOT}" "${TEST_DIRECTORY}/canary-aarch64"
  local entry
  for entry in "${AARCH64_CPUS[@]}"; do
    local cpu="${entry%%:*}" level="${entry#*:}"
    ASAN_OPTIONS=detect_leaks=0 assert_levels_agree "asan aarch64 (qemu -cpu ${cpu})" \
      "${AARCH64_EMULATOR}" -L "${AARCH64_SYSROOT}" -cpu "${cpu}" "${TEST_DIRECTORY}/sanitized-aarch64" "expect=${level}"
  done
}

check_kernel_symbols() {
  echo "== symbols"
  check_symbols x86-64 X86_UNITS
  check_symbols aarch64 AARCH64_UNITS "${AARCH64_FLAGS[@]}"
}

check_symbols() {
  local -r label="${1}"
  shift
  local -n units="${1}"
  shift
  local -r object_file="${TEST_DIRECTORY}/symbols.o"
  local unit
  for unit in "${units[@]}"; do
    local unit_name instruction_flags=()
    split_unit "${unit}" unit_name instruction_flags
    "${COMPILER}" "${RESOURCE_OPTIONS[@]}" "${KERNEL_FLAGS[@]}" -O0 "${@}" "${instruction_flags[@]}" \
      -c "${NATIVE_SOURCES}/${unit_name}.cpp" -o "${object_file}"
    if "${LLVM_TOOLS}/llvm-nm" --defined-only --extern-only "${object_file}" | awk '{print $3}' | \
      grep -v "^${KERNEL_SYMBOL_PREFIX}" | grep -q .; then
      echo "Unit ${unit_name} (${label}) defines shared symbols!" >&2
      "${LLVM_TOOLS}/llvm-nm" --defined-only --extern-only -C "${object_file}" | grep -v " ${KERNEL_SYMBOL_PREFIX}" >&2
      exit "${FAILURE_EXIT_CODE}"
    fi
  done

  echo "${label}: every unit defines only its kernels"
}

report_kernel_coverage() {
  echo "== coverage (llvm-cov)"
  local -r covered_program="${TEST_DIRECTORY}/covered"
  local -r profile_data="${TEST_DIRECTORY}/kernels.profdata"
  compile_direct_test covered X86_UNITS -O1 -fprofile-instr-generate -fcoverage-mapping
  LLVM_PROFILE_FILE="${TEST_DIRECTORY}/kernels-%p.profraw" "${INSTRUCTION_EMULATOR}" -icx -- "${covered_program}" > /dev/null
  "${LLVM_TOOLS}/llvm-profdata" merge -o "${profile_data}" "${TEST_DIRECTORY}"/kernels-*.profraw
  "${LLVM_TOOLS}/llvm-cov" report "${covered_program}" -instr-profile="${profile_data}" "${NATIVE_SOURCES}"/*.cpp
}

check_shipped_libraries() {
  echo "== the shipped libraries"
  local target
  for target in x86_64 aarch64; do
    check_library_imports "${target}"
    compile_library_tests "${target}"
  done

  check_x86_64_library
  check_aarch64_library
}

check_library_imports() {
  local -r target="${1}"
  local -r library="${NATIVE_LIBRARIES}/linux-${target}/${LIBRARY_FILE_NAME}"
  if "${LLVM_TOOLS}/llvm-readelf" --dynamic "${library}" | grep -q "${NEEDED_ENTRY}" ||
    "${LLVM_TOOLS}/llvm-readelf" --dyn-syms "${library}" | awk '$7 == "UND" && $8 != ""' | grep -q .; then
    echo "Library linux-${target} imports something!" >&2
    exit "${FAILURE_EXIT_CODE}"
  fi
}

compile_library_tests() {
  local -r target="${1}"
  "${ZIG_COMPILER}" c++ -target "${target}-linux-gnu.2.28" -O1 "${KERNEL_FLAGS[@]}" "${SCRIPT_DIRECTORY}/kernels_test.cpp" \
    -o "${TEST_DIRECTORY}/glibc-${target}" -ldl 2> /dev/null
  "${ZIG_COMPILER}" c++ -target "${target}-linux-musl" -dynamic -O1 "${KERNEL_FLAGS[@]}" "${SCRIPT_DIRECTORY}/kernels_test.cpp" \
    -o "${TEST_DIRECTORY}/musl-${target}" 2> /dev/null
}

check_x86_64_library() {
  local -r glibc_program="${TEST_DIRECTORY}/glibc-x86_64"
  assert_levels_agree "linux-x86_64 glibc (this CPU)" "${glibc_program}" "${X86_LIBRARY}"
  assert_levels_agree "linux-x86_64 musl (this CPU)" "${X86_MUSL_LOADER}" "${TEST_DIRECTORY}/musl-x86_64" "${X86_LIBRARY}"
  assert_levels_agree "linux-x86_64 glibc (qemu -cpu qemu64)" "${X86_EMULATOR}" -cpu qemu64 "${glibc_program}" \
    "${X86_LIBRARY}" expect=sse2
  local entry
  for entry in "${EMULATED_X86_CPUS[@]}"; do
    local cpu="${entry%%:*}" level="${entry#*:}"
    assert_levels_agree "linux-x86_64 glibc (SDE -${cpu})" "${INSTRUCTION_EMULATOR}" "-${cpu}" -- "${glibc_program}" \
      "${X86_LIBRARY}" "expect=${level}"
  done
}

check_aarch64_library() {
  local entry
  for entry in "${AARCH64_CPUS[@]}"; do
    local cpu="${entry%%:*}" level="${entry#*:}"
    assert_levels_agree "linux-aarch64 glibc (qemu -cpu ${cpu})" "${AARCH64_EMULATOR}" -L "${AARCH64_SYSROOT}" -cpu "${cpu}" \
      "${TEST_DIRECTORY}/glibc-aarch64" "${AARCH64_LIBRARY}" "expect=${level}"
  done

  assert_levels_agree "linux-aarch64 musl (qemu -cpu max,sve 64)" "${AARCH64_EMULATOR}" -cpu "${SVE512_CPU}" \
    "${AARCH64_MUSL_LOADER}" "${TEST_DIRECTORY}/musl-aarch64" "${AARCH64_LIBRARY}" expect=sve512
}

check_digests_agree() {
  local reference
  reference="$(tail -n "+${FIRST_DIGEST_LINE}" "${TEST_DIRECTORY}/${REFERENCE_LABEL}.txt")"
  local run
  for run in "${TEST_DIRECTORY}"/*.txt; do
    [ "$(basename "${run}")" = "${CAUGHT_FILE_NAME}" ] && continue
    local digests
    digests="$(tail -n "+${FIRST_DIGEST_LINE}" "${run}")"
    if [ "${digests}" != "${reference}" ]; then
      echo "Run $(basename "${run}" .txt) has other digests!" >&2
      exit "${FAILURE_EXIT_CODE}"
    fi
  done

  echo "every level on every CPU agrees, digests identical"
}

main "${@}"
