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
# The standalone tests of the MCV2 native kernels (kernels_test.cpp), outside the JVM and outside the Gradle build:
#   1. the kernels under AddressSanitizer and UndefinedBehaviorSanitizer, every x86-64 level against the scalar one;
#   2. the same with llvm-cov coverage, reported per source file (reported, not gated);
#   3. the shipped linux-x86_64 library natively and the shipped linux-aarch64 one under qemu-user, whose digests must
#      be identical: the JVM tests prove x86-64 equal to Java, so equal digests prove aarch64 equal to Java as well.
#
# Tools, all installed in user space on the machine that built the libraries: CLANG (clang++ 18), RESOURCE_DIR (a
# clang resource folder with the compiler-rt sanitizer and profile runtimes), LLVM_BIN (llvm-profdata, llvm-cov), ZIG
# (0.16.0), QEMU_AARCH64 (qemu-aarch64-static) and SYSROOT_AARCH64 (an aarch64 glibc to run the test program on).
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
sources=$here/../../../main/native/mcv2
libraries=$here/../../../main/resources/me/brandonli/mcav/media/mcv2/encode/natives
work=${WORK:-$(mktemp -d)}
clang=${CLANG:-clang++}
llvm=${LLVM_BIN:-/usr/lib/llvm-18/bin}
zig=${ZIG:-zig}
resource=()
[ -n "${RESOURCE_DIR:-}" ] && resource=(-resource-dir="$RESOURCE_DIR")
flags=(-std=c++17 -ffp-contract=off -fwrapv -fno-strict-aliasing -Wall -Wextra -Werror -I"$sources")

# direct <name> <extra flags...>: the test with the x86-64 level sources linked in
direct() {
  local name=$1
  shift
  local objects=()
  for unit in cpu level_scalar level_sse41:-msse4.1 level_avx2:-mavx2 test; do
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

echo "== AddressSanitizer + UndefinedBehaviorSanitizer"
direct sanitized -O1 -g -fsanitize=address,undefined -fno-sanitize-recover=all -fno-omit-frame-pointer
ASAN_OPTIONS=detect_leaks=1 "$work/sanitized" | tail -2

echo "== coverage (llvm-cov)"
direct covered -O1 -fprofile-instr-generate -fcoverage-mapping
LLVM_PROFILE_FILE="$work/kernels.profraw" "$work/covered" > /dev/null
"$llvm/llvm-profdata" merge -o "$work/kernels.profdata" "$work/kernels.profraw"
"$llvm/llvm-cov" report "$work/covered" -instr-profile="$work/kernels.profdata" "$sources"/*.cpp "$sources"/*.hpp

echo "== the shipped libraries"
for target in x86_64 aarch64; do
  "$zig" c++ -target "$target-linux-gnu.2.28" -O1 "${flags[@]}" -c "$here/kernels_test.cpp" -o "$work/shipped-$target.o"
  "$zig" c++ -target "$target-linux-gnu.2.28" "$work/shipped-$target.o" -o "$work/shipped-$target" -ldl 2> /dev/null
done
"$work/shipped-x86_64" "$libraries/linux-x86_64/libmcv2kernels.so" > "$work/x86_64.txt"
"${QEMU_AARCH64:-qemu-aarch64-static}" -L "${SYSROOT_AARCH64:?the aarch64 sysroot}" "$work/shipped-aarch64" \
  "$libraries/linux-aarch64/libmcv2kernels.so" > "$work/aarch64.txt"
head -1 "$work/x86_64.txt" "$work/aarch64.txt"
diff <(tail -n +2 "$work/x86_64.txt") <(tail -n +2 "$work/aarch64.txt")
grep '^all' "$work/x86_64.txt"
echo "linux-x86_64 and linux-aarch64 agree on every kernel"
