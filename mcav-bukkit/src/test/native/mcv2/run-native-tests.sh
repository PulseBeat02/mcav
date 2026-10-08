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

here=$(cd "$(dirname "$0")" && pwd)
sources=$here/../../../main/native/mcv2
libraries=$here/../../../../build/generated/natives/mcav/mcv2/natives
work=${WORK:-$(mktemp -d)}
clang=${CLANG:-clang++}
llvm=${LLVM_BIN:-/usr/lib/llvm-18/bin}
zig=${ZIG:-zig}
qemu_x86_64=${QEMU_X86_64:-qemu-x86_64-static}
qemu_aarch64=${QEMU_AARCH64:-qemu-aarch64-static}
sysroot=${SYSROOT_AARCH64:?the aarch64 sysroot}
sde=${SDE:?the path of sde64, Intel SDE}
resource=()
[ -n "${RESOURCE_DIR:-}" ] && resource=(-resource-dir="$RESOURCE_DIR")
flags=(-std=c++17 -ffp-contract=off -fwrapv -fno-strict-aliasing -Wall -Wextra -Werror -I"$sources")
sanitize=(-O1 -g -fsanitize=address,undefined -fno-sanitize-recover=all -fno-omit-frame-pointer)

aarch64=(--target=aarch64-linux-gnu --sysroot="$sysroot" -fuse-ld=lld -Wno-unused-command-line-argument)

avx512="-mavx512f -mavx512dq -mavx512bw -mavx512vl -mavx512vbmi -mavx512vbmi2 -mavx512vnni -mavx512bitalg"
x86_units=(level_scalar level_sse2 level_sse41:-msse4.1 level_avx2:-mavx2 "level_avx512:$avx512")
arm_units=(level_scalar level_neon "level_sve256:-march=armv8-a+sve -msve-vector-bits=256"
  "level_sve512:-march=armv8-a+sve -msve-vector-bits=512")

sde_cpus=(spr:avx512 icx:avx512 skx:avx2 hsw:avx2 mrm:sse2)
arm_cpus=(cortex-a72:neon max,sve-default-vector-length=16:neon max,sve-default-vector-length=32:sve256
  max,sve-default-vector-length=64:sve512)

direct() {
  local name=$1
  local -n units=$2
  shift 2
  local objects=()
  for unit in "${units[@]}" test; do
    local source=${unit%%:*} extra=""
    [ "$unit" != "$source" ] && extra=${unit#*:}
    local file=$sources/$source.cpp
    [ "$source" = test ] && file=$here/kernels_test.cpp
    # shellcheck disable=SC2086
    "$clang" "${resource[@]}" "${flags[@]}" -DMCV2_TEST_DIRECT "$@" $extra -c "$file" -o "$work/$name-$source.o"
    objects+=("$work/$name-$source.o")
  done
  "$clang" "${resource[@]}" "$@" "${objects[@]}" -o "$work/$name"
}

canary() {
  local name=$1
  shift
  printf '#include <stdlib.h>\nint main(int c, char **v) { char *p = calloc(16, 1); return p[15 + c] + !v; }\n' \
    > "$work/canary.c"
  "${clang%++}" "${resource[@]}" "$@" -O1 -fsanitize=address "$work/canary.c" -o "$work/$name"
}

caught() {
  if ASAN_OPTIONS=detect_leaks=0 "$@" > /dev/null 2> "$work/caught.txt" || ! grep -q heap-buffer-overflow "$work/caught.txt"; then
    echo "the sanitizer missed a heap overflow under: $*" >&2
    exit 1
  fi
}

agrees() {
  local label=$1
  shift
  "$@" > "$work/$label.txt"
  printf '%-44s %s  %s\n' "$label" "$(sed -n 's/^dispatched: //p' "$work/$label.txt")" \
    "$(sed -n 's/^all *//p' "$work/$label.txt")"
}

echo "== AddressSanitizer + UndefinedBehaviorSanitizer"
direct sanitized-x86_64 x86_units "${sanitize[@]}"
canary canary-x86_64
caught "$work/canary-x86_64"
caught "$sde" -icx -- "$work/canary-x86_64"
ASAN_OPTIONS=detect_leaks=1 agrees "asan x86-64 (this CPU)" "$work/sanitized-x86_64"
ASAN_OPTIONS=detect_leaks=0 agrees "asan x86-64 (SDE -icx)" "$sde" -icx -- "$work/sanitized-x86_64" expect=avx512
direct sanitized-aarch64 arm_units "${aarch64[@]}" "${sanitize[@]}"
canary canary-aarch64 "${aarch64[@]}"
caught "$qemu_aarch64" -L "$sysroot" "$work/canary-aarch64"
for entry in "${arm_cpus[@]}"; do
  ASAN_OPTIONS=detect_leaks=0 agrees "asan aarch64 (qemu -cpu ${entry%%:*})" \
    "$qemu_aarch64" -L "$sysroot" -cpu "${entry%%:*}" "$work/sanitized-aarch64" "expect=${entry#*:}"
done

echo "== symbols"

symbols() {
  local label=$1
  shift
  local -n units=$1
  shift
  for unit in "${units[@]}"; do
    local source=${unit%%:*} extra=""
    [ "$unit" != "$source" ] && extra=${unit#*:}
    # shellcheck disable=SC2086
    "$clang" "${resource[@]}" "${flags[@]}" -O0 "$@" $extra -c "$sources/$source.cpp" -o "$work/symbols.o"
    if "$llvm/llvm-nm" --defined-only --extern-only "$work/symbols.o" | awk '{print $3}' | grep -v '^mcv2_' | grep -q .; then
      echo "$source ($label) defines a shared symbol:" >&2
      "$llvm/llvm-nm" --defined-only --extern-only -C "$work/symbols.o" | grep -v ' mcv2_' >&2
      exit 1
    fi
  done
  echo "$label: every unit defines only its kernels"
}
symbols x86-64 x86_units
symbols aarch64 arm_units "${aarch64[@]}"

echo "== coverage (llvm-cov)"
direct covered x86_units -O1 -fprofile-instr-generate -fcoverage-mapping
LLVM_PROFILE_FILE="$work/kernels-%p.profraw" "$sde" -icx -- "$work/covered" > /dev/null
"$llvm/llvm-profdata" merge -o "$work/kernels.profdata" "$work"/kernels-*.profraw
"$llvm/llvm-cov" report "$work/covered" -instr-profile="$work/kernels.profdata" "$sources"/*.cpp

echo "== the shipped libraries"
for target in x86_64 aarch64; do
  library=$libraries/linux-$target/libmcv2kernels.so
  if "$llvm/llvm-readelf" --dynamic "$library" | grep -q NEEDED ||
    "$llvm/llvm-readelf" --dyn-syms "$library" | awk '$7 == "UND" && $8 != ""' | grep -q .; then
    echo "linux-$target imports something" >&2
    exit 1
  fi
  "$zig" c++ -target "$target-linux-gnu.2.28" -O1 "${flags[@]}" "$here/kernels_test.cpp" -o "$work/glibc-$target" \
    -ldl 2> /dev/null
  "$zig" c++ -target "$target-linux-musl" -dynamic -O1 "${flags[@]}" "$here/kernels_test.cpp" \
    -o "$work/musl-$target" 2> /dev/null
done
x86_64=$libraries/linux-x86_64/libmcv2kernels.so
arm=$libraries/linux-aarch64/libmcv2kernels.so
agrees "linux-x86_64 glibc (this CPU)" "$work/glibc-x86_64" "$x86_64"
agrees "linux-x86_64 musl (this CPU)" "${MUSL_X86_64:?the path of ld-musl-x86_64.so.1}" "$work/musl-x86_64" "$x86_64"
agrees "linux-x86_64 glibc (qemu -cpu qemu64)" "$qemu_x86_64" -cpu qemu64 "$work/glibc-x86_64" "$x86_64" expect=sse2
for entry in "${sde_cpus[@]}"; do
  agrees "linux-x86_64 glibc (SDE -${entry%%:*})" "$sde" "-${entry%%:*}" -- "$work/glibc-x86_64" "$x86_64" \
    "expect=${entry#*:}"
done
for entry in "${arm_cpus[@]}"; do
  agrees "linux-aarch64 glibc (qemu -cpu ${entry%%:*})" "$qemu_aarch64" -L "$sysroot" -cpu "${entry%%:*}" \
    "$work/glibc-aarch64" "$arm" "expect=${entry#*:}"
done
agrees "linux-aarch64 musl (qemu -cpu max,sve 64)" "$qemu_aarch64" -cpu max,sve-default-vector-length=64 \
  "${MUSL_AARCH64:?the path of ld-musl-aarch64.so.1}" "$work/musl-aarch64" "$arm" expect=sve512
reference=$(tail -n +3 "$work/asan x86-64 (this CPU).txt")
for run in "$work"/*.txt; do
  [ "$(basename "$run")" = caught.txt ] && continue
  if [ "$(tail -n +3 "$run")" != "$reference" ]; then
    echo "$(basename "$run" .txt) has other digests" >&2
    exit 1
  fi
done
echo "every level on every CPU agrees, digests identical"
