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

script_directory=$(cd "$(dirname "$0")" && pwd)
native_sources=$script_directory/../../../main/native/mcv2
native_libraries=$script_directory/../../../../build/generated/natives/mcav/mcv2/natives
test_directory=${WORK:-$(mktemp -d)}
compiler=${CLANG:-clang++}
llvm_tools=${LLVM_BIN:-/usr/lib/llvm-18/bin}
zig_compiler=${ZIG:-zig}
x86_emulator=${QEMU_X86_64:-qemu-x86_64-static}
aarch64_emulator=${QEMU_AARCH64:-qemu-aarch64-static}
aarch64_sysroot=${SYSROOT_AARCH64:?the aarch64 sysroot}
instruction_emulator=${SDE:?the path of sde64, Intel SDE}
resource_options=()
[ -n "${RESOURCE_DIR:-}" ] && resource_options=(-resource-dir="$RESOURCE_DIR")
kernel_flags=(-std=c++17 -ffp-contract=off -fwrapv -fno-strict-aliasing -Wall -Wextra -Werror -I"$native_sources")
sanitizer_flags=(-O1 -g -fsanitize=address,undefined -fno-sanitize-recover=all -fno-omit-frame-pointer)

aarch64_flags=(--target=aarch64-linux-gnu --sysroot="$aarch64_sysroot" -fuse-ld=lld -Wno-unused-command-line-argument)

avx512_flags="-mavx512f -mavx512dq -mavx512bw -mavx512vl -mavx512vbmi -mavx512vbmi2 -mavx512vnni -mavx512bitalg"
x86_units=(level_scalar level_sse2 level_sse41:-msse4.1 level_avx2:-mavx2 "level_avx512:$avx512_flags")
aarch64_units=(level_scalar level_neon "level_sve256:-march=armv8-a+sve -msve-vector-bits=256"
  "level_sve512:-march=armv8-a+sve -msve-vector-bits=512")

emulated_x86_cpus=(spr:avx512 icx:avx512 skx:avx2 hsw:avx2 mrm:sse2)
aarch64_cpus=(cortex-a72:neon max,sve-default-vector-length=16:neon max,sve-default-vector-length=32:sve256
  max,sve-default-vector-length=64:sve512)

compile_direct_test() {
  local name=$1
  local -n units=$2
  shift 2
  local object_files=()
  for unit in "${units[@]}" test; do
    local unit_name=${unit%%:*} instruction_flags=""
    [ "$unit" != "$unit_name" ] && instruction_flags=${unit#*:}
    local source_file=$native_sources/$unit_name.cpp
    [ "$unit_name" = test ] && source_file=$script_directory/kernels_test.cpp
    # shellcheck disable=SC2086
    "$compiler" "${resource_options[@]}" "${kernel_flags[@]}" -DMCV2_TEST_DIRECT "$@" $instruction_flags -c "$source_file" -o "$test_directory/$name-$unit_name.o"
    object_files+=("$test_directory/$name-$unit_name.o")
  done
  "$compiler" "${resource_options[@]}" "$@" "${object_files[@]}" -o "$test_directory/$name"
}

compile_canary() {
  local name=$1
  shift
  printf '#include <stdlib.h>\nint main(int argument_count, char **arguments) { char *buffer = calloc(16, 1); return buffer[15 + argument_count] + !arguments; }\n' \
    > "$test_directory/canary.c"
  "${compiler%++}" "${resource_options[@]}" "$@" -O1 -fsanitize=address "$test_directory/canary.c" -o "$test_directory/$name"
}

assert_overflow_caught() {
  if ASAN_OPTIONS=detect_leaks=0 "$@" > /dev/null 2> "$test_directory/caught.txt" || ! grep -q heap-buffer-overflow "$test_directory/caught.txt"; then
    echo "the sanitizer missed a heap overflow under: $*" >&2
    exit 1
  fi
}

assert_levels_agree() {
  local label=$1
  shift
  "$@" > "$test_directory/$label.txt"
  printf '%-44s %s  %s\n' "$label" "$(sed -n 's/^dispatched: //p' "$test_directory/$label.txt")" \
    "$(sed -n 's/^all *//p' "$test_directory/$label.txt")"
}

echo "== AddressSanitizer + UndefinedBehaviorSanitizer"
compile_direct_test sanitized-x86_64 x86_units "${sanitizer_flags[@]}"
compile_canary canary-x86_64
assert_overflow_caught "$test_directory/canary-x86_64"
assert_overflow_caught "$instruction_emulator" -icx -- "$test_directory/canary-x86_64"
ASAN_OPTIONS=detect_leaks=1 assert_levels_agree "asan x86-64 (this CPU)" "$test_directory/sanitized-x86_64"
ASAN_OPTIONS=detect_leaks=0 assert_levels_agree "asan x86-64 (SDE -icx)" "$instruction_emulator" -icx -- "$test_directory/sanitized-x86_64" expect=avx512
compile_direct_test sanitized-aarch64 aarch64_units "${aarch64_flags[@]}" "${sanitizer_flags[@]}"
compile_canary canary-aarch64 "${aarch64_flags[@]}"
assert_overflow_caught "$aarch64_emulator" -L "$aarch64_sysroot" "$test_directory/canary-aarch64"
for entry in "${aarch64_cpus[@]}"; do
  ASAN_OPTIONS=detect_leaks=0 assert_levels_agree "asan aarch64 (qemu -cpu ${entry%%:*})" \
    "$aarch64_emulator" -L "$aarch64_sysroot" -cpu "${entry%%:*}" "$test_directory/sanitized-aarch64" "expect=${entry#*:}"
done

echo "== symbols"

check_symbols() {
  local label=$1
  shift
  local -n units=$1
  shift
  for unit in "${units[@]}"; do
    local unit_name=${unit%%:*} instruction_flags=""
    [ "$unit" != "$unit_name" ] && instruction_flags=${unit#*:}
    # shellcheck disable=SC2086
    "$compiler" "${resource_options[@]}" "${kernel_flags[@]}" -O0 "$@" $instruction_flags -c "$native_sources/$unit_name.cpp" -o "$test_directory/symbols.o"
    if "$llvm_tools/llvm-nm" --defined-only --extern-only "$test_directory/symbols.o" | awk '{print $3}' | grep -v '^mcv2_' | grep -q .; then
      echo "$unit_name ($label) defines a shared symbol:" >&2
      "$llvm_tools/llvm-nm" --defined-only --extern-only -C "$test_directory/symbols.o" | grep -v ' mcv2_' >&2
      exit 1
    fi
  done
  echo "$label: every unit defines only its kernels"
}
check_symbols x86-64 x86_units
check_symbols aarch64 aarch64_units "${aarch64_flags[@]}"

echo "== coverage (llvm-cov)"
compile_direct_test covered x86_units -O1 -fprofile-instr-generate -fcoverage-mapping
LLVM_PROFILE_FILE="$test_directory/kernels-%p.profraw" "$instruction_emulator" -icx -- "$test_directory/covered" > /dev/null
"$llvm_tools/llvm-profdata" merge -o "$test_directory/kernels.profdata" "$test_directory"/kernels-*.profraw
"$llvm_tools/llvm-cov" report "$test_directory/covered" -instr-profile="$test_directory/kernels.profdata" "$native_sources"/*.cpp

echo "== the shipped libraries"
for target in x86_64 aarch64; do
  library=$native_libraries/linux-$target/libmcv2kernels.so
  if "$llvm_tools/llvm-readelf" --dynamic "$library" | grep -q NEEDED ||
    "$llvm_tools/llvm-readelf" --dyn-syms "$library" | awk '$7 == "UND" && $8 != ""' | grep -q .; then
    echo "linux-$target imports something" >&2
    exit 1
  fi
  "$zig_compiler" c++ -target "$target-linux-gnu.2.28" -O1 "${kernel_flags[@]}" "$script_directory/kernels_test.cpp" -o "$test_directory/glibc-$target" \
    -ldl 2> /dev/null
  "$zig_compiler" c++ -target "$target-linux-musl" -dynamic -O1 "${kernel_flags[@]}" "$script_directory/kernels_test.cpp" \
    -o "$test_directory/musl-$target" 2> /dev/null
done
x86_library=$native_libraries/linux-x86_64/libmcv2kernels.so
aarch64_library=$native_libraries/linux-aarch64/libmcv2kernels.so
assert_levels_agree "linux-x86_64 glibc (this CPU)" "$test_directory/glibc-x86_64" "$x86_library"
assert_levels_agree "linux-x86_64 musl (this CPU)" "${MUSL_X86_64:?the path of ld-musl-x86_64.so.1}" "$test_directory/musl-x86_64" "$x86_library"
assert_levels_agree "linux-x86_64 glibc (qemu -cpu qemu64)" "$x86_emulator" -cpu qemu64 "$test_directory/glibc-x86_64" "$x86_library" expect=sse2
for entry in "${emulated_x86_cpus[@]}"; do
  assert_levels_agree "linux-x86_64 glibc (SDE -${entry%%:*})" "$instruction_emulator" "-${entry%%:*}" -- "$test_directory/glibc-x86_64" "$x86_library" \
    "expect=${entry#*:}"
done
for entry in "${aarch64_cpus[@]}"; do
  assert_levels_agree "linux-aarch64 glibc (qemu -cpu ${entry%%:*})" "$aarch64_emulator" -L "$aarch64_sysroot" -cpu "${entry%%:*}" \
    "$test_directory/glibc-aarch64" "$aarch64_library" "expect=${entry#*:}"
done
assert_levels_agree "linux-aarch64 musl (qemu -cpu max,sve 64)" "$aarch64_emulator" -cpu max,sve-default-vector-length=64 \
  "${MUSL_AARCH64:?the path of ld-musl-aarch64.so.1}" "$test_directory/musl-aarch64" "$aarch64_library" expect=sve512
reference=$(tail -n +3 "$test_directory/asan x86-64 (this CPU).txt")
for run in "$test_directory"/*.txt; do
  [ "$(basename "$run")" = caught.txt ] && continue
  if [ "$(tail -n +3 "$run")" != "$reference" ]; then
    echo "$(basename "$run" .txt) has other digests" >&2
    exit 1
  fi
done
echo "every level on every CPU agrees, digests identical"
