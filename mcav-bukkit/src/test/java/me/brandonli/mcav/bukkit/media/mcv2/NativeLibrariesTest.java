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
package me.brandonli.mcav.bukkit.media.mcv2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.base.Splitter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Level;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Natives;
import me.brandonli.mcav.bukkit.media.mcv2.NativeLibraryFile.Format;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The generated native libraries, read from their files with no emulator: each is built for its platform's machine and
 * exports exactly the entry points of the levels that platform dispatches to, the Linux ones need no library and leave
 * no symbol undefined, and all of them were built from the sources in the tree.
 */
final class NativeLibrariesTest {

  /** The module folder is the working folder of its tests, under Gradle and under PIT alike. */
  private static final Path SOURCES = Path.of("src/main/native/mcv2");

  /** The formatter's settings, which no library is built from. */
  private static final String FORMAT_SETTINGS = ".clang-format";

  private static final String REBUILD = "rebuild the libraries: ./gradlew :mcav-bukkit:buildMcv2Natives";

  /** One entry of the source's kernel list, {@code X(return type, name, parameters)}. */
  private static final Pattern KERNEL = Pattern.compile("\\bX\\(\\s*\\w+\\s*,\\s*(\\w+)\\s*,");

  private static final Set<String> QUERIES = Set.of("mcv2_abi", "mcv2_cpu_levels");

  /** What the Mach-O linker exports from every dylib besides the library's own symbols. */
  private static final Set<String> MACH_O_LINKER_SYMBOLS = Set.of("_mh_dylib_header", "__dso_handle");

  private static final int EM_X86_64 = 62;

  private static final int EM_AARCH64 = 183;

  private static final int IMAGE_FILE_MACHINE_AMD64 = 0x8664;

  private static final int IMAGE_FILE_MACHINE_ARM64 = 0xaa64;

  private static final int CPU_TYPE_X86_64 = 0x01000007;

  private static final int CPU_TYPE_ARM64 = 0x0100000c;

  private static final List<Level> X86_64 = List.of(Level.SCALAR, Level.SSE2, Level.SSE41, Level.AVX2, Level.AVX512);

  /** macOS saves ZMM state lazily, so XCR0 cannot authorize AVX-512 and the library dispatches to AVX2 at most. */
  private static final List<Level> MACOS_X86_64 = List.of(Level.SCALAR, Level.SSE2, Level.SSE41, Level.AVX2);

  /** Only Linux says whether a program may run SVE, and at which vector length. */
  private static final List<Level> LINUX_AARCH64 = List.of(Level.SCALAR, Level.NEON, Level.SVE256, Level.SVE512);

  private static final List<Level> AARCH64 = List.of(Level.SCALAR, Level.NEON);

  private static final Map<String, Expected> PLATFORMS = Map.of(
    "linux-x86_64",
    new Expected(Format.ELF, EM_X86_64, X86_64),
    "linux-aarch64",
    new Expected(Format.ELF, EM_AARCH64, LINUX_AARCH64),
    "windows-x86_64",
    new Expected(Format.PE, IMAGE_FILE_MACHINE_AMD64, X86_64),
    "windows-aarch64",
    new Expected(Format.PE, IMAGE_FILE_MACHINE_ARM64, AARCH64),
    "macos-x86_64",
    new Expected(Format.MACH_O, CPU_TYPE_X86_64, MACOS_X86_64),
    "macos-aarch64",
    new Expected(Format.MACH_O, CPU_TYPE_ARM64, AARCH64)
  );

  private record Expected(Format format, int machine, List<Level> levels) {}

  @Test
  void checksEveryPlatformTheLoaderHasALibraryFor() {
    assertEquals(MCV2.NATIVE_DIGESTS.keySet(), PLATFORMS.keySet());
  }

  @ParameterizedTest
  @ValueSource(strings = { "linux-x86_64", "linux-aarch64", "windows-x86_64", "windows-aarch64", "macos-x86_64", "macos-aarch64" })
  void isBuiltForItsPlatformAndExportsItsLevels(final String platform) throws IOException {
    final Expected expected = PLATFORMS.get(platform);
    final NativeLibraryFile library = NativeLibraryFile.read(library(platform));
    assertEquals(expected.format(), library.format(), platform);
    assertEquals(expected.machine(), library.machine(), platform);
    final Set<String> entryPoints = new TreeSet<>(QUERIES);
    for (final Level level : expected.levels()) {
      for (final String kernel : kernels()) {
        entryPoints.add("mcv2_" + level.symbol() + "_" + kernel);
      }
    }
    final Set<String> own = new TreeSet<>();
    final Set<String> others = new TreeSet<>();
    for (final String symbol : library.exports()) {
      (symbol.startsWith("mcv2_") ? own : others).add(symbol);
    }
    assertEquals(entryPoints, own, platform);
    assertEquals(expected.format() == Format.MACH_O ? MACH_O_LINKER_SYMBOLS : Set.of(), others, platform);
    if (expected.format() == Format.ELF) {
      assertEquals(List.of(), library.needed(), platform);
      assertEquals(Set.of(), library.undefined(), platform);
    }
  }

  @Test
  void readsOnlyTheThreeFormats() {
    assertThrows(IllegalArgumentException.class, () -> NativeLibraryFile.read(new byte[64]));
  }

  @Test
  void wasBuiltFromTheSourcesInTheTree() throws IOException {
    final Map<String, String> built = new TreeMap<>();
    try (final InputStream manifest = MCV2.class.getResourceAsStream("/mcav/mcv2/natives/SOURCES")) {
      assertNotNull(manifest, "no /mcav/mcv2/natives/SOURCES: " + REBUILD);
      for (final String line : new String(manifest.readAllBytes(), StandardCharsets.US_ASCII).lines().toList()) {
        final List<String> fields = Splitter.on(' ').omitEmptyStrings().splitToList(line);
        built.put(fields.get(1), fields.get(0));
      }
    }
    final Map<String, String> tree = new TreeMap<>();
    try (final Stream<Path> files = Files.list(SOURCES)) {
      for (final Path file : files.toList()) {
        final String name = file.getFileName().toString();
        if (!name.equals(FORMAT_SETTINGS)) {
          tree.put(name, Mcv2Resources.sha256(Files.readAllBytes(file)));
        }
      }
    }
    assertEquals(tree, built, "the native sources changed since the libraries were built: " + REBUILD);
  }

  /** The kernels every level exports, from the source's list of them. */
  private static List<String> kernels() throws IOException {
    final String header = Files.readString(SOURCES.resolve("mcv2.cpp"), StandardCharsets.US_ASCII);
    final int start = header.indexOf("#define MCV2_KERNELS(X)");
    final Matcher matcher = KERNEL.matcher(header.substring(start, header.indexOf("extern \"C\"", start)));
    final List<String> kernels = new ArrayList<>();
    while (matcher.find()) {
      kernels.add(matcher.group(1));
    }
    assertFalse(kernels.isEmpty(), "no kernel in the source's list");
    return kernels;
  }

  private static byte[] library(final String platform) {
    final byte[] bytes = Natives.read(
      MCV2.class.getResourceAsStream("/mcav/mcv2/natives/" + platform + "/" + Natives.libraryName(platform))
    );
    assertNotNull(bytes, platform);
    return bytes;
  }
}
