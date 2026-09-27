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
package me.brandonli.mcav.bukkit.media.mcv2.encode;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.common.base.Splitter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.SymbolLookup;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Resources;
import me.brandonli.mcav.bukkit.testing.UtilityClassAssertions;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The loading of the native kernels: which platform names a library, the libraries the jar ships against the digests
 * compiled in, each way a library can fail to load - off, unknown, missing, unreadable, altered, without a folder,
 * unextractable, unloadable, the wrong interface, a kernel short - running Java with the reason, the extraction under
 * the digest that a second load reuses, the level chosen, and the path the live encoders take.
 */
final class Mcv2NativesTest {

  private static final byte[] NOT_A_LIBRARY = "not a library".getBytes(StandardCharsets.US_ASCII);

  @TempDir
  Path folder;

  @AfterEach
  void restore() {
    System.clearProperty(Mcv2Natives.PROPERTY);
    Mcv2Natives.install(NativeTesting.FOLDER, Mcv2Natives.AUTO);
  }

  @Test
  void isAUtilityClass() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(Mcv2Natives.class);
  }

  @Test
  void namesThePlatformsTheJarHasLibrariesFor() {
    assertEquals("linux-x86_64", Mcv2Natives.platform("Linux", "amd64"));
    assertEquals("linux-x86_64", Mcv2Natives.platform("linux", "x86_64"));
    assertEquals("linux-aarch64", Mcv2Natives.platform("Linux", "aarch64"));
    assertEquals("windows-x86_64", Mcv2Natives.platform("Windows 11", "amd64"));
    assertEquals("windows-aarch64", Mcv2Natives.platform("Windows 11", "aarch64"));
    assertEquals("macos-x86_64", Mcv2Natives.platform("Mac OS X", "x86_64"));
    assertEquals("macos-aarch64", Mcv2Natives.platform("Mac OS X", "arm64"));
    assertNull(Mcv2Natives.platform("FreeBSD", "amd64"));
    assertNull(Mcv2Natives.platform("Linux", "ppc64le"));
    assertEquals("mcv2kernels.dll", Mcv2Natives.libraryName("windows-x86_64"));
    assertEquals("libmcv2kernels.dylib", Mcv2Natives.libraryName("macos-aarch64"));
    assertEquals("libmcv2kernels.so", Mcv2Natives.libraryName("linux-aarch64"));
  }

  @Test
  void shipsTheLibrariesItsDigestsName() throws IOException {
    final Map<String, String> listed = new HashMap<>();
    try (InputStream sums = Mcv2Natives.class.getResourceAsStream("natives/SHA256SUMS")) {
      assertNotNull(sums);
      for (final String line : new String(sums.readAllBytes(), StandardCharsets.US_ASCII).lines().toList()) {
        final List<String> fields = Splitter.on(' ').omitEmptyStrings().splitToList(line);
        listed.put(fields.get(1).substring(0, fields.get(1).indexOf('/')), fields.get(0));
      }
    }
    assertEquals(Mcv2Natives.DIGESTS, listed);
    for (final Map.Entry<String, String> library : Mcv2Natives.DIGESTS.entrySet()) {
      final String platform = library.getKey();
      final byte[] bytes = Mcv2Natives.read(
        Mcv2Natives.class.getResourceAsStream("natives/" + platform + "/" + Mcv2Natives.libraryName(platform))
      );
      assertNotNull(bytes, platform);
      assertEquals(library.getValue(), Mcv2Resources.sha256(bytes), platform);
      // the one megabyte a native library may take in the jar
      assertTrue(bytes.length < 1_000_000, platform);
    }
  }

  @Test
  void readsALibraryOrNothing() {
    assertNull(Mcv2Natives.read(null));
    assertArrayEquals(new byte[] { 1, 2 }, Mcv2Natives.read(new ByteArrayInputStream(new byte[] { 1, 2 })));
    final InputStream failing = new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("unreadable");
      }

      @Override
      public int read(final byte[] buffer, final int offset, final int length) throws IOException {
        throw new IOException("unreadable");
      }
    };
    assertThrows(UncheckedIOException.class, () -> Mcv2Natives.read(failing));
  }

  private static byte@Nullable[] unexpected(final String platform) {
    throw new AssertionError("no library should be read for " + platform);
  }

  @Test
  void runsJavaWhenOffUnknownOrWithoutALibrary() {
    final Mcv2Natives.Resolution off = Mcv2Natives.resolve(Mcv2Natives.OFF, "linux-x86_64", this.folder, Mcv2NativesTest::unexpected, null);
    assertNull(off.binding());
    assertFalse(off.failed());
    assertEquals("Java, turned off by mcv2.native=off", off.description());
    assertSame(JavaKernels.FACTORY, off.factory());
    final Mcv2Natives.Resolution unknown = Mcv2Natives.resolve("sometimes", "linux-x86_64", this.folder, Mcv2NativesTest::unexpected, null);
    assertTrue(unknown.failed());
    assertEquals("Java, unknown mode mcv2.native=sometimes", unknown.description());
    final Mcv2Natives.Resolution nowhere = Mcv2Natives.resolve(Mcv2Natives.AUTO, null, this.folder, Mcv2NativesTest::unexpected, null);
    assertFalse(nowhere.failed());
    assertEquals("Java, no library for this operating system and processor", nowhere.description());
    final Mcv2Natives.Resolution unlisted = Mcv2Natives.resolve(
      Mcv2Natives.AUTO,
      "solaris-sparc",
      this.folder,
      Mcv2NativesTest::unexpected,
      null
    );
    assertFalse(unlisted.failed());
    assertEquals("Java, no library for solaris-sparc", unlisted.description());
    final Mcv2Natives.Resolution missing = Mcv2Natives.resolve(Mcv2Natives.AUTO, "linux-x86_64", this.folder, platform -> null, null);
    assertFalse(missing.failed());
    assertEquals("Java, no library for linux-x86_64", missing.description());
    final Mcv2Natives.Resolution unreadable = Mcv2Natives.resolve(
      Mcv2Natives.AUTO,
      "linux-x86_64",
      this.folder,
      platform -> {
        throw new UncheckedIOException(new IOException("unreadable"));
      },
      null
    );
    assertTrue(unreadable.failed());
    assertTrue(unreadable.description().startsWith("Java, the library could not be read"));
  }

  @Test
  void refusesALibraryThatDoesNotMatchOrCannotBePlaced() throws IOException {
    final String digest = Mcv2Resources.sha256(NOT_A_LIBRARY);
    final Mcv2Natives.Resolution altered = Mcv2Natives.load("linux-x86_64", "0".repeat(64), NOT_A_LIBRARY, this.folder, null);
    assertTrue(altered.failed());
    assertEquals("Java, the library for linux-x86_64 does not match its SHA-256", altered.description());
    final Mcv2Natives.Resolution homeless = Mcv2Natives.load("linux-x86_64", digest, NOT_A_LIBRARY, null, null);
    assertTrue(homeless.failed());
    assertEquals("Java, no folder was given to extract the library into", homeless.description());
    final Path file = Files.writeString(this.folder.resolve("a file"), "in the way");
    final Mcv2Natives.Resolution blocked = Mcv2Natives.load("linux-x86_64", digest, NOT_A_LIBRARY, file, null);
    assertTrue(blocked.failed());
    assertTrue(blocked.description().startsWith("Java, the library could not be extracted"));
    final Mcv2Natives.Resolution unloadable = Mcv2Natives.load("linux-x86_64", digest, NOT_A_LIBRARY, this.folder, null);
    assertTrue(unloadable.failed());
    assertTrue(unloadable.description().startsWith("Java, the library could not be loaded"));
  }

  @Test
  void extractsOnceUnderItsDigestAndReplacesAnythingElse() throws IOException {
    final String digest = Mcv2Resources.sha256(NOT_A_LIBRARY);
    final Path folder = this.folder.resolve("created");
    final Path file = Mcv2Natives.extract(folder, "library", NOT_A_LIBRARY, digest);
    assertArrayEquals(NOT_A_LIBRARY, Files.readAllBytes(file));
    // a file already there with the library is kept as it is
    final FileTime longAgo = FileTime.fromMillis(0);
    Files.setLastModifiedTime(file, longAgo);
    assertEquals(file, Mcv2Natives.extract(folder, "library", NOT_A_LIBRARY, digest));
    assertEquals(longAgo, Files.getLastModifiedTime(file));
    // anything else is replaced
    Files.writeString(file, "changed");
    Mcv2Natives.extract(folder, "library", NOT_A_LIBRARY, digest);
    assertArrayEquals(NOT_A_LIBRARY, Files.readAllBytes(file));
    // a folder in the way stops it, and leaves no partial file behind
    final Path taken = Files.createDirectories(folder.resolve("taken"));
    Files.writeString(taken.resolve("inside"), "keeps the folder non-empty");
    assertThrows(IOException.class, () -> Mcv2Natives.extract(folder, "taken", NOT_A_LIBRARY, digest));
    try (Stream<Path> files = Files.list(folder)) {
      assertEquals(2, files.count());
    }
  }

  @Test
  void choosesTheBestLevelAtMostTheHighestAsked() {
    // an Ice Lake: scalar, sse2, sse41, avx2 and avx512
    final int iceLake = 1 | 16 | 2 | 4 | 32;
    assertEquals(NativeKernels.Level.AVX512, Mcv2Natives.level(iceLake, null));
    assertEquals(NativeKernels.Level.AVX2, Mcv2Natives.level(iceLake, "avx2"));
    assertEquals(NativeKernels.Level.SSE41, Mcv2Natives.level(iceLake, "sse41"));
    assertEquals(NativeKernels.Level.SSE2, Mcv2Natives.level(iceLake, "sse2"));
    assertEquals(NativeKernels.Level.SCALAR, Mcv2Natives.level(iceLake, "scalar"));
    assertEquals(NativeKernels.Level.SCALAR, Mcv2Natives.level(iceLake, "unknown"));
    // a Core 2 without SSE4.1, a Haswell
    assertEquals(NativeKernels.Level.SSE2, Mcv2Natives.level(1 | 16, null));
    assertEquals(NativeKernels.Level.SSE2, Mcv2Natives.level(1 | 16, "avx2"));
    assertEquals(NativeKernels.Level.AVX2, Mcv2Natives.level(1 | 16 | 2 | 4, "avx512"));
    // AArch64 without SVE, with 256-bit and with 512-bit SVE
    assertEquals(NativeKernels.Level.NEON, Mcv2Natives.level(1 | 8, null));
    assertEquals(NativeKernels.Level.SVE256, Mcv2Natives.level(1 | 8 | 64, null));
    assertEquals(NativeKernels.Level.SVE512, Mcv2Natives.level(1 | 8 | 128, null));
    assertEquals(NativeKernels.Level.NEON, Mcv2Natives.level(1 | 8 | 128, "neon"));
    assertEquals(NativeKernels.Level.SCALAR, Mcv2Natives.level(1, null));
    assertTrue(NativeKernels.Level.AVX512.in(iceLake));
    assertFalse(NativeKernels.Level.NEON.in(iceLake));
  }

  @Test
  void readsTheProcessorFeaturesLinuxGives() throws IOException {
    assertEquals(0, Mcv2Natives.hwcap(this.folder.resolve("missing")));
    // AT_PAGESZ, AT_HWCAP, AT_NULL, and the start of another entry
    final ByteBuffer vector = ByteBuffer.allocate(56).order(ByteOrder.nativeOrder());
    vector.putLong(6).putLong(4096).putLong(16).putLong(0x40_0000L).putLong(0).putLong(0).putInt(16);
    final Path given = Files.write(this.folder.resolve("auxv"), vector.array());
    assertEquals(0x40_0000L, Mcv2Natives.hwcap(given));
    final Path without = Files.write(this.folder.resolve("without"), Arrays.copyOfRange(vector.array(), 0, 16));
    assertEquals(0, Mcv2Natives.hwcap(without));
    final Path truncated = Files.write(this.folder.resolve("truncated"), Arrays.copyOfRange(vector.array(), 0, 24));
    assertEquals(0, Mcv2Natives.hwcap(truncated));
  }

  @Test
  void runsTheLevelsProcCpuinfoListsTheFeaturesOf() throws IOException {
    assumeTrue(NativeTesting.expected(), "no library loads here");
    final Path cpuinfo = Path.of("/proc/cpuinfo");
    assumeTrue(Files.isReadable(cpuinfo), "no /proc/cpuinfo here");
    final Set<String> flags = new HashSet<>();
    for (final String line : Files.readAllLines(cpuinfo)) {
      // x86-64 lists its features as flags, AArch64 as Features
      if (line.startsWith("flags") || line.startsWith("Features")) {
        flags.addAll(Splitter.on(' ').omitEmptyStrings().splitToList(line.substring(line.indexOf(':') + 1)));
        break;
      }
    }
    final int levels = NativeTesting.resolution().levels();
    final boolean x86 = flags.contains("sse2");
    assertTrue(NativeKernels.Level.SCALAR.in(levels));
    assertEquals(x86, NativeKernels.Level.SSE2.in(levels));
    assertEquals(flags.contains("sse4_1"), NativeKernels.Level.SSE41.in(levels));
    assertEquals(flags.contains("avx2"), NativeKernels.Level.AVX2.in(levels));
    final List<String> iceLake = List.of(
      "avx512f",
      "avx512dq",
      "avx512bw",
      "avx512vl",
      "avx512vbmi",
      "avx512_vbmi2",
      "avx512_vnni",
      "avx512_bitalg"
    );
    assertEquals(flags.containsAll(iceLake), NativeKernels.Level.AVX512.in(levels));
    assertEquals(!x86, NativeKernels.Level.NEON.in(levels));
    // SVE's vector length is not in /proc/cpuinfo: without SVE neither SVE level runs, with it at most one
    final boolean sve = NativeKernels.Level.SVE256.in(levels) || NativeKernels.Level.SVE512.in(levels);
    assertTrue(flags.contains("sve") || !sve);
    assertFalse(NativeKernels.Level.SVE256.in(levels) && NativeKernels.Level.SVE512.in(levels));
  }

  @Test
  void bindsTheLibraryAndRefusesAWrongOne() {
    assumeTrue(NativeTesting.expected(), "no library loads here");
    final SymbolLookup library = NativeTesting.library();
    final int levels = NativeTesting.resolution().levels();
    final Mcv2Natives.Resolution best = Mcv2Natives.bind(library, "here", null);
    assertEquals(Mcv2Natives.level(levels, null), Objects.requireNonNull(best.binding()).level());
    assertEquals("native " + Mcv2Natives.level(levels, null).symbol() + " (here)", best.description());
    assertInstanceOf(NativeKernels.class, best.factory().create());
    assertEquals(NativeKernels.Level.SCALAR, Objects.requireNonNull(Mcv2Natives.bind(library, "here", "scalar").binding()).level());
    final Mcv2Natives.Resolution unversioned = Mcv2Natives.bind(
      name -> name.equals("mcv2_abi") ? Optional.empty() : library.find(name),
      "here",
      null
    );
    assertTrue(unversioned.failed());
    assertTrue(unversioned.description().startsWith("Java, the library could not be bound"));
    // a library of another interface than the bindings are written for
    final Mcv2Natives.Resolution other = Mcv2Natives.bind(library, "here", null, Mcv2Natives.ABI + 1);
    assertTrue(other.failed());
    assertEquals("Java, the library's interface " + Mcv2Natives.ABI + " is not " + (Mcv2Natives.ABI + 1), other.description());
    final Mcv2Natives.Resolution lacking = Mcv2Natives.bind(
      name -> name.endsWith("_cell_means") ? Optional.empty() : library.find(name),
      "here",
      null
    );
    assertTrue(lacking.failed());
  }

  @Test
  void loadsTheExpectedKernelsAndTellsWhich() throws IOException {
    Mcv2Natives.install(NativeTesting.FOLDER, Mcv2Natives.AUTO);
    if (NativeTesting.expected()) {
      assertTrue(Mcv2Natives.describe().startsWith("native "), Mcv2Natives.describe());
      assertInstanceOf(NativeKernels.class, Mcv2Natives.factory().create());
    } else {
      assertTrue(Mcv2Natives.describe().startsWith("Java, "), Mcv2Natives.describe());
      assertSame(JavaKernels.FACTORY, Mcv2Natives.factory());
    }
    // decided once, until the next install
    assertSame(Mcv2Natives.resolved(), Mcv2Natives.resolved());
    Mcv2Natives.install(this.folder, Mcv2Natives.OFF);
    assertEquals("Java, turned off by mcv2.native=off", Mcv2Natives.describe());
    assertSame(JavaKernels.FACTORY, Mcv2Natives.factory());
    // the system property wins over the configuration
    System.setProperty(Mcv2Natives.PROPERTY, Mcv2Natives.OFF);
    Mcv2Natives.install(this.folder, Mcv2Natives.AUTO);
    assertEquals("Java, turned off by mcv2.native=off", Mcv2Natives.describe());
    System.clearProperty(Mcv2Natives.PROPERTY);
    // where a library should load, a folder it cannot be extracted into is a failure, logged, and Java runs
    Mcv2Natives.install(Files.writeString(this.folder.resolve("a file"), "in the way"), Mcv2Natives.AUTO);
    assertTrue(Mcv2Natives.describe().startsWith("Java, "));
    assertEquals(NativeTesting.expected(), Mcv2Natives.resolved().failed());
    assertThrows(IllegalArgumentException.class, () -> Mcv2Natives.install(this.folder, "sometimes"));
  }
}
