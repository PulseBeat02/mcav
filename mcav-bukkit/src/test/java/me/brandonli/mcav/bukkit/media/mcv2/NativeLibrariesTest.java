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
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Level;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Natives;
import me.brandonli.mcav.bukkit.media.mcv2.NativeLibraryFile.Format;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class NativeLibrariesTest {

  private static final String SOURCES_RESOURCE = "/mcav/mcv2/natives/SOURCES";
  private static final String NATIVES_RESOURCE_FOLDER = "/mcav/mcv2/natives/";
  private static final String PATH_SEPARATOR = "/";

  private static final Path SOURCES = Path.of("src/main/native/mcv2");

  private static final String FORMAT_SETTINGS = ".clang-format";

  private static final String REBUILD = "rebuild the libraries: ./gradlew :mcav-bukkit:buildMcv2Natives";

  private static final Pattern KERNEL = Pattern.compile("\\bKERNEL\\(\\s*\\w+\\s*,\\s*(\\w+)\\s*,");

  private static final Set<String> QUERIES = Set.of("mcv2_abi", "mcv2_cpu_levels");

  private static final Set<String> MACH_O_LINKER_SYMBOLS = Set.of("_mh_dylib_header", "__dso_handle");

  private static final int EM_X86_64 = 62;

  private static final int EM_AARCH64 = 183;

  private static final int IMAGE_FILE_MACHINE_AMD64 = 0x8664;

  private static final int IMAGE_FILE_MACHINE_ARM64 = 0xaa64;

  private static final int CPU_TYPE_X86_64 = 0x01000007;

  private static final int CPU_TYPE_ARM64 = 0x0100000c;

  private static final List<Level> X86_64 = List.of(Level.SCALAR, Level.SSE2, Level.SSE41, Level.AVX2, Level.AVX512);

  // macOS saves ZMM state lazily, so XCR0 cannot authorize AVX-512
  private static final List<Level> MACOS_X86_64 = List.of(Level.SCALAR, Level.SSE2, Level.SSE41, Level.AVX2);

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

  @Test
  void shouldCheckEveryPlatformTheLoaderHasALibraryFor() {
    final Set<String> expectedPlatforms = Mcv2.NATIVE_DIGESTS.keySet();
    final Set<String> platforms = PLATFORMS.keySet();
    assertEquals(expectedPlatforms, platforms);
  }

  @ParameterizedTest
  @ValueSource(strings = { "linux-x86_64", "linux-aarch64", "windows-x86_64", "windows-aarch64", "macos-x86_64", "macos-aarch64" })
  void shouldBeBuiltForItsPlatformAndExportItsLevels(final String platform) throws IOException {
    final Expected expected = PLATFORMS.get(platform);
    final NativeLibraryFile library = NativeLibraryFile.read(library(platform));

    final Format expectedFormat = expected.format();
    final Format format = library.format();
    assertEquals(expectedFormat, format, platform);

    final int expectedMachine = expected.machine();
    final int machine = library.machine();
    assertEquals(expectedMachine, machine, platform);

    final Set<String> entryPoints = new TreeSet<>(QUERIES);
    for (final Level level : expected.levels()) {
      for (final String kernel : kernels()) {
        entryPoints.add("mcv2_" + level.getSymbol() + "_" + kernel);
      }
    }

    final Set<String> own = new TreeSet<>();
    final Set<String> others = new TreeSet<>();
    for (final String symbol : library.exports()) {
      (symbol.startsWith("mcv2_") ? own : others).add(symbol);
    }

    assertEquals(entryPoints, own, platform);

    final Set<String> expectedOthers = expected.format() == Format.MACH_O ? MACH_O_LINKER_SYMBOLS : Set.of();
    assertEquals(expectedOthers, others, platform);

    if (expected.format() == Format.ELF) {
      final List<String> needed = library.needed();
      assertEquals(List.of(), needed, platform);

      final Set<String> undefined = library.undefined();
      assertEquals(Set.of(), undefined, platform);
    }
  }

  @Test
  void shouldReadOnlyTheThreeFormats() {
    assertThrows(IllegalArgumentException.class, () -> NativeLibraryFile.read(new byte[64]));
  }

  @Test
  void shouldBeBuiltFromTheSourcesInTheTree() throws IOException {
    final Map<String, String> built = new TreeMap<>();
    try (final InputStream manifest = Mcv2.class.getResourceAsStream(SOURCES_RESOURCE)) {
      assertNotNull(manifest, "no /mcav/mcv2/natives/SOURCES: " + REBUILD);

      for (final String line : new String(manifest.readAllBytes(), StandardCharsets.US_ASCII).lines().toList()) {
        final List<String> fields = Splitter.on(' ').omitEmptyStrings().splitToList(line);
        built.put(fields.get(1), fields.get(0));
      }
    }

    final Map<String, String> tree = new TreeMap<>();
    try (final Stream<Path> files = Files.list(SOURCES)) {
      final List<Path> sources = files.filter(file -> !file.getFileName().toString().equals(FORMAT_SETTINGS)).toList();
      for (final Path file : sources) {
        tree.put(file.getFileName().toString(), Mcv2Resources.sha256(Files.readAllBytes(file)));
      }
    }

    assertEquals(tree, built, "the native sources changed since the libraries were built: " + REBUILD);
  }

  private static List<String> kernels() throws IOException {
    final String header = Files.readString(SOURCES.resolve("mcv2.cpp"), StandardCharsets.US_ASCII);
    final int start = header.indexOf("#define MCV2_KERNELS(KERNEL)");
    final Matcher matcher = KERNEL.matcher(header.substring(start, header.indexOf("extern \"C\"", start)));
    final List<String> kernels = new ArrayList<>();
    while (matcher.find()) {
      kernels.add(matcher.group(1));
    }

    final boolean empty = kernels.isEmpty();
    assertFalse(empty, "no kernel in the source's list");

    return kernels;
  }

  private static byte[] library(final String platform) {
    final byte[] bytes = Natives.read(
      Mcv2.class.getResourceAsStream(NATIVES_RESOURCE_FOLDER + platform + PATH_SEPARATOR + Natives.libraryName(platform))
    );
    assertNotNull(bytes, platform);

    return bytes;
  }

  private record Expected(Format format, int machine, List<Level> levels) {}
}
