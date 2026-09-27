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
# Builds the MCV2 native kernels for every platform with one pinned toolchain, Zig 0.16.0 (its clang 21.1.0 and
# linkers), into the resources next to Mcv2Natives, and writes their SHA-256 manifest (SHA256SUMS) and that of the
# sources they are built from (SOURCES). The normal build never runs this: the libraries are committed, and
# `./gradlew :mcav-bukkit:buildMcv2Natives -Pmcav.natives=build` runs it.
#
#   build.sh [output folder]      (ZIG=/path/to/zig to choose the compiler; its version must be 0.16.0)
#
# Every unit is compiled for its architecture's baseline (x86-64 with SSE2, ARMv8-A with NEON), the SSE4.1, AVX2,
# AVX-512 and SVE units with their own extensions added and nothing else, so no instruction a CPU lacks runs before
# mcv2_cpu_levels() says it has it; never -march=native.
# Floating point stays IEEE and unfused (-ffp-contract=off, never -ffast-math), signed arithmetic wraps as Java's
# (-fwrapv), and the libraries use no C++ runtime. The Linux libraries import nothing, so they need no particular glibc.
# The builds are reproducible: a macOS library is named @rpath/libmcv2kernels.dylib, not the path it was built at, which
# would otherwise vary with the output folder, and its UUID with it.
set -euo pipefail

ZIG_VERSION=0.16.0
# the Windows linker would stamp the link time into the library
export SOURCE_DATE_EPOCH=0
here=$(cd "$(dirname "$0")" && pwd)
out=${1:-$here/../../resources/me/brandonli/mcav/bukkit/media/mcv2/encode/natives}
zig=${ZIG:-zig}
version=$("$zig" version)
if [ "$version" != "$ZIG_VERSION" ]; then
  echo "build.sh: Zig $ZIG_VERSION is required, $zig is $version" >&2
  exit 1
fi

flags=(-std=c++17 -O2 -fPIC -fno-exceptions -fno-rtti -fvisibility=hidden -ffp-contract=off -fwrapv
  -fno-strict-aliasing -Wall -Wextra -Werror)

# build <platform> <zig target> <cpu> <library name> <link flags> <unit>[:<flags>]...
build() {
  local platform=$1 target=$2 cpu=$3 name=$4 link=$5
  shift 5
  local work
  work=$(mktemp -d)
  local objects=()
  for unit in "$@"; do
    local source=${unit%%:*} extra=""
    [ "$unit" != "$source" ] && extra=${unit#*:}
    # shellcheck disable=SC2086
    "$zig" c++ -target "$target" -mcpu="$cpu" "${flags[@]}" $extra -c "$here/$source.cpp" -o "$work/$source.o"
    objects+=("$work/$source.o")
  done
  mkdir -p "$out/$platform"
  # shellcheck disable=SC2086
  "$zig" cc -target "$target" -shared -s $link -o "$out/$platform/$name" "${objects[@]}"
  # the Windows linker also writes an import library, which nothing loads
  rm -rf "$work" "$out/$platform/"*.lib
  echo "$platform/$name: $(wc -c < "$out/$platform/$name") bytes"
}

# x86-64: SSE2 is the baseline; AVX-512 only with the Ice Lake feature set, and not on macOS (cpu.cpp). Branches are
# kept inside 32-byte blocks on Linux and Windows: on Skylake-family CPUs a jump that crosses one runs slower since the
# JCC erratum's microcode update, and without it a kernel's speed moved by up to 15 % with where the linker placed it
# (NatBench). LLVM pads only ELF and COFF output, so the macOS library is built without the option.
avx512="-mavx512f -mavx512dq -mavx512bw -mavx512vl -mavx512vbmi -mavx512vbmi2 -mavx512vnni -mavx512bitalg"
jcc="-mbranches-within-32B-boundaries"
x86=("cpu:$jcc" "level_scalar:$jcc" "level_sse2:$jcc" "level_sse41:-msse4.1 $jcc" "level_avx2:-mavx2 $jcc"
  "level_avx512:$avx512 $jcc")
x86_macos=(cpu level_scalar level_sse2 level_sse41:-msse4.1 level_avx2:-mavx2)
# AArch64: NEON is the baseline; SVE at 256 and 512 bits on Linux, whose kernel says whether SVE may run
arm=(cpu level_scalar level_neon)
arm_linux=("${arm[@]}" "level_sve256:-mcpu=baseline+sve -msve-vector-bits=256"
  "level_sve512:-mcpu=baseline+sve -msve-vector-bits=512")
# no executable stack: nothing in the library needs one, and a JVM warns about a library that asks for it
elf="-Wl,-z,noexecstack"
build linux-x86_64 x86_64-linux-gnu.2.28 baseline libmcv2kernels.so "$elf" "${x86[@]}"
build linux-aarch64 aarch64-linux-gnu.2.28 baseline libmcv2kernels.so "$elf" "${arm_linux[@]}"
build windows-x86_64 x86_64-windows-gnu baseline mcv2kernels.dll "" "${x86[@]}"
build windows-aarch64 aarch64-windows-gnu baseline mcv2kernels.dll "" "${arm[@]}"
macho="-Wl,-install_name,@rpath/libmcv2kernels.dylib"
build macos-x86_64 x86_64-macos.11.0 baseline libmcv2kernels.dylib "$macho" "${x86_macos[@]}"
build macos-aarch64 aarch64-macos.11.0 baseline libmcv2kernels.dylib "$macho" "${arm[@]}"

(cd "$out" && sha256sum ./*/*mcv2kernels* | sed 's| \./| |' > SHA256SUMS)
# the sources the libraries are built from (all but the formatter's settings), which NativeLibrariesTest compares with
# the sources in the tree: a source changed without the libraries rebuilt from it fails the default build
(cd "$here" && LC_ALL=C ls -A | grep -vx '.clang-format' | xargs sha256sum) > "$out/SOURCES"
cat "$out/SHA256SUMS" "$out/SOURCES"
