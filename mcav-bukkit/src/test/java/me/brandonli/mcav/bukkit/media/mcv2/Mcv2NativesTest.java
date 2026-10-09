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

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
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
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
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
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Level;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Natives;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Resolution;
import me.brandonli.mcav.bukkit.testing.LogCapture;
import me.brandonli.mcav.bukkit.testing.UtilityClassAssertions;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The loading of the native kernels: which platform names a library, the libraries the jar ships against the digests
 * generated with them, each way a library can fail to load - off, unknown, missing, unreadable, altered, without a folder,
 * unextractable, unloadable, the wrong interface, a kernel short - running Java with the reason, the extraction under
 * the digest that a second load reuses, the level chosen, and the path the live encoders take.
 */
final class Mcv2NativesTest {

  private static final byte[] NOT_A_LIBRARY = "not a library".getBytes(StandardCharsets.US_ASCII);

  @TempDir
  Path folder;

  @AfterEach
  void restore() {
    System.clearProperty(MCV2.NATIVE_PROPERTY);
    System.clearProperty("mcv2.native.level");
    Natives.install(NativeTesting.FOLDER, MCV2.NATIVE_AUTO);
  }

  @Test
  void isAUtilityClass() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(Natives.class);
  }

  @Test
  void namesThePlatformsTheJarHasLibrariesFor() {
    assertEquals("linux-x86_64", Natives.platform("Linux", "amd64"));
    assertEquals("linux-x86_64", Natives.platform("linux", "x86_64"));
    assertEquals("linux-aarch64", Natives.platform("Linux", "aarch64"));
    assertEquals("windows-x86_64", Natives.platform("Windows 11", "amd64"));
    assertEquals("windows-aarch64", Natives.platform("Windows 11", "aarch64"));
    assertEquals("macos-x86_64", Natives.platform("Mac OS X", "x86_64"));
    assertEquals("macos-aarch64", Natives.platform("Mac OS X", "arm64"));
    assertNull(Natives.platform("FreeBSD", "amd64"));
    assertNull(Natives.platform("Linux", "ppc64le"));
    assertEquals("mcv2kernels.dll", Natives.libraryName("windows-x86_64"));
    assertEquals("libmcv2kernels.dylib", Natives.libraryName("macos-aarch64"));
    assertEquals("libmcv2kernels.so", Natives.libraryName("linux-aarch64"));
  }

  @Test
  void shipsTheLibrariesItsDigestsName() throws IOException {
    final Map<String, String> listed = new HashMap<>();
    try (final InputStream sums = MCV2.class.getResourceAsStream("/mcav/mcv2/natives/SHA256SUMS")) {
      assertNotNull(sums);
      for (final String line : new String(sums.readAllBytes(), StandardCharsets.US_ASCII).lines().toList()) {
        final List<String> fields = Splitter.on(' ').omitEmptyStrings().splitToList(line);
        listed.put(fields.get(1).substring(0, fields.get(1).indexOf('/')), fields.get(0));
      }
    }
    assertEquals(MCV2.NATIVE_DIGESTS, listed);
    for (final Map.Entry<String, String> library : MCV2.NATIVE_DIGESTS.entrySet()) {
      final String platform = library.getKey();
      final byte[] bytes = Natives.read(
        MCV2.class.getResourceAsStream("/mcav/mcv2/natives/" + platform + "/" + Natives.libraryName(platform))
      );
      assertNotNull(bytes, platform);
      assertEquals(library.getValue(), Mcv2Resources.sha256(bytes), platform);
      // the one megabyte a native library may take in the jar
      assertTrue(bytes.length < 1_000_000, platform);
    }
  }

  @Test
  void readsALibraryOrNothing() {
    assertNull(Natives.read(null));
    assertArrayEquals(new byte[] { 1, 2 }, Natives.read(new ByteArrayInputStream(new byte[] { 1, 2 })));
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
    assertThrows(UncheckedIOException.class, () -> Natives.read(failing));
  }

  @Test
  void readsGeneratedDigestsAndRefusesMalformedManifests() {
    final String digest = "a".repeat(64);
    final String entry = digest + "  linux-x86_64/libmcv2kernels.so\n";
    assertEquals(Map.of("linux-x86_64", digest), Natives.digests(new ByteArrayInputStream(entry.getBytes(StandardCharsets.US_ASCII))));
    assertThrows(UnsupportedOperationException.class, () ->
      Natives.digests(new ByteArrayInputStream(entry.getBytes(StandardCharsets.US_ASCII))).clear()
    );
    assertEquals(Map.of(), Natives.digests(null));
    for (final String invalid : List.of(
      "",
      "garbage",
      "a  linux-x86_64/libmcv2kernels.so",
      entry + entry,
      entry + "garbage",
      digest + "  linux-x86_64/wrong.so",
      digest + "  linux-ppc64/libmcv2kernels.so"
    )) {
      assertEquals(Map.of(), Natives.digests(new ByteArrayInputStream(invalid.getBytes(StandardCharsets.US_ASCII))));
    }
    final InputStream failing = new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("unreadable manifest");
      }

      @Override
      public int read(final byte[] buffer, final int offset, final int length) throws IOException {
        throw new IOException("unreadable manifest");
      }
    };
    assertEquals(Map.of(), Natives.digests(failing));
  }

  private static byte @Nullable [] unexpected(final String platform) {
    throw new AssertionError("no library should be read for " + platform);
  }

  @Test
  void runsJavaWhenOffUnknownOrWithoutALibrary() {
    final Resolution off = Natives.resolve(MCV2.NATIVE_OFF, "linux-x86_64", this.folder, Mcv2NativesTest::unexpected, null);
    assertNull(off.binding());
    assertFalse(off.failed());
    assertEquals("Java, turned off by mcv2.native=off", off.description());
    assertSame(NativeTesting.javaFactory(), NativeTesting.factory(off));
    final Resolution unknown = Natives.resolve("sometimes", "linux-x86_64", this.folder, Mcv2NativesTest::unexpected, null);
    assertTrue(unknown.failed());
    assertEquals("Java, unknown mode mcv2.native=sometimes", unknown.description());
    final Resolution nowhere = Natives.resolve(MCV2.NATIVE_AUTO, null, this.folder, Mcv2NativesTest::unexpected, null);
    assertFalse(nowhere.failed());
    assertEquals("Java, no library for this operating system and processor", nowhere.description());
    final Resolution unlisted = Natives.resolve(MCV2.NATIVE_AUTO, "solaris-sparc", this.folder, Mcv2NativesTest::unexpected, null);
    assertFalse(unlisted.failed());
    assertEquals("Java, no library for solaris-sparc", unlisted.description());
    final Resolution missing = Natives.resolve(MCV2.NATIVE_AUTO, "linux-x86_64", this.folder, platform -> null, null);
    assertFalse(missing.failed());
    assertEquals("Java, no library for linux-x86_64", missing.description());
    final Resolution unreadable = Natives.resolve(
      MCV2.NATIVE_AUTO,
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
    final Resolution altered = Natives.load("linux-x86_64", "0".repeat(64), NOT_A_LIBRARY, this.folder, null);
    assertTrue(altered.failed());
    assertEquals("Java, the library for linux-x86_64 does not match its SHA-256", altered.description());
    final Resolution homeless = Natives.load("linux-x86_64", digest, NOT_A_LIBRARY, null, null);
    assertTrue(homeless.failed());
    assertEquals("Java, no folder was given to extract the library into", homeless.description());
    final Path file = Files.writeString(this.folder.resolve("a file"), "in the way");
    final Resolution blocked = Natives.load("linux-x86_64", digest, NOT_A_LIBRARY, file, null);
    assertTrue(blocked.failed());
    assertTrue(blocked.description().startsWith("Java, the library could not be extracted"));
    final Resolution unloadable = Natives.load("linux-x86_64", digest, NOT_A_LIBRARY, this.folder, null);
    assertTrue(unloadable.failed());
    assertTrue(unloadable.description().startsWith("Java, the library could not be loaded"));
  }

  @Test
  void extractsOnceUnderItsDigestAndReplacesAnythingElse() throws IOException {
    final String digest = Mcv2Resources.sha256(NOT_A_LIBRARY);
    final Path folder = this.folder.resolve("created");
    final Path file = Natives.extract(folder, "library", NOT_A_LIBRARY, digest);
    assertArrayEquals(NOT_A_LIBRARY, Files.readAllBytes(file));
    // a file already there with the library is kept as it is
    final FileTime longAgo = FileTime.fromMillis(0);
    Files.setLastModifiedTime(file, longAgo);
    assertEquals(file, Natives.extract(folder, "library", NOT_A_LIBRARY, digest));
    assertEquals(longAgo, Files.getLastModifiedTime(file));
    // anything else is replaced
    Files.writeString(file, "changed");
    Natives.extract(folder, "library", NOT_A_LIBRARY, digest);
    assertArrayEquals(NOT_A_LIBRARY, Files.readAllBytes(file));
    // a folder in the way stops it, and leaves no partial file behind
    final Path taken = Files.createDirectories(folder.resolve("taken"));
    Files.writeString(taken.resolve("inside"), "keeps the folder non-empty");
    assertThrows(IOException.class, () -> Natives.extract(folder, "taken", NOT_A_LIBRARY, digest));
    try (final Stream<Path> files = Files.list(folder)) {
      assertEquals(2, files.count());
    }
  }

  @Test
  void choosesTheBestLevelAtMostTheHighestAsked() {
    // an Ice Lake: scalar, sse2, sse41, avx2 and avx512
    final int iceLake = 1 | 16 | 2 | 4 | 32;
    assertEquals(Level.AVX512, Natives.level(iceLake, null));
    assertEquals(Level.AVX2, Natives.level(iceLake, "avx2"));
    assertEquals(Level.SSE41, Natives.level(iceLake, "sse41"));
    assertEquals(Level.SSE2, Natives.level(iceLake, "sse2"));
    assertEquals(Level.SCALAR, Natives.level(iceLake, "scalar"));
    assertEquals(Level.SCALAR, Natives.level(iceLake, "unknown"));
    // a Core 2 without SSE4.1, a Haswell
    assertEquals(Level.SSE2, Natives.level(1 | 16, null));
    assertEquals(Level.SSE2, Natives.level(1 | 16, "avx2"));
    assertEquals(Level.AVX2, Natives.level(1 | 16 | 2 | 4, "avx512"));
    // AArch64 without SVE, with 256-bit and with 512-bit SVE
    assertEquals(Level.NEON, Natives.level(1 | 8, null));
    assertEquals(Level.SVE256, Natives.level(1 | 8 | 64, null));
    assertEquals(Level.SVE512, Natives.level(1 | 8 | 128, null));
    assertEquals(Level.NEON, Natives.level(1 | 8 | 128, "neon"));
    assertEquals(Level.SCALAR, Natives.level(1, null));
    assertTrue(Level.AVX512.isIn(iceLake));
    assertFalse(Level.NEON.isIn(iceLake));
  }

  @Test
  void readsTheProcessorFeaturesLinuxGives() throws IOException {
    assertEquals(0, Natives.hardwareCapabilities(this.folder.resolve("missing")));
    // AT_PAGESZ, AT_HWCAP, AT_NULL, and the start of another entry
    final ByteBuffer vector = ByteBuffer.allocate(56).order(ByteOrder.nativeOrder());
    vector.putLong(6).putLong(4096).putLong(16).putLong(0x40_0000L).putLong(0).putLong(0).putInt(16);
    final Path given = Files.write(this.folder.resolve("auxv"), vector.array());
    assertEquals(0x40_0000L, Natives.hardwareCapabilities(given));
    final Path without = Files.write(this.folder.resolve("without"), Arrays.copyOfRange(vector.array(), 0, 16));
    assertEquals(0, Natives.hardwareCapabilities(without));
    final Path truncated = Files.write(this.folder.resolve("truncated"), Arrays.copyOfRange(vector.array(), 0, 24));
    assertEquals(0, Natives.hardwareCapabilities(truncated));
    final ByteBuffer last = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
    last.putLong(16).putLong(0x40_0000L);
    assertEquals(0x40_0000L, Natives.hardwareCapabilities(Files.write(this.folder.resolve("last"), last.array())));
  }

  @Test
  @SuppressWarnings("restricted")
  void passesLinuxFeatureBitsToTheNativeLevelQuery() throws ReflectiveOperationException {
    assumeTrue(NativeTesting.expected(), "no library loads here");
    final long features = Natives.hardwareCapabilities(Path.of("/proc/self/auxv"));
    assumeTrue(features != 0, "Linux must supply processor feature bits");
    final AtomicLong received = new AtomicLong(-1);
    final MethodHandle capture = MethodHandles.lookup()
      .findVirtual(AtomicLong.class, "getAndSet", MethodType.methodType(long.class, long.class))
      .bindTo(received);
    final MethodHandle scalar = MethodHandles.dropArguments(MethodHandles.constant(int.class, 1), 0, long.class);
    final MethodHandle query = MethodHandles.filterReturnValue(capture, scalar);
    try (final Arena arena = Arena.ofConfined()) {
      final MemorySegment entry = Linker.nativeLinker().upcallStub(query, FunctionDescriptor.of(JAVA_INT, JAVA_LONG), arena);
      final SymbolLookup original = NativeTesting.library();
      final SymbolLookup library = name -> name.equals("mcv2_cpu_levels") ? Optional.of(entry) : original.find(name);
      assertFalse(Natives.bind(library, "linux-test", null, Natives.ABI).failed());
      assertEquals(features, received.get());
      assertFalse(Natives.bind(library, "other-test", null, Natives.ABI).failed());
      assertEquals(0, received.get());
    }
  }

  @Test
  void warnsOnlyWhenNativeResolutionFails() {
    Natives.install(this.folder, MCV2.NATIVE_OFF);
    try (final LogCapture logs = LogCapture.capture(MCV2.class)) {
      assertFalse(Natives.resolved().failed());
      assertEquals(
        List.of("INFO"),
        logs
          .getEvents()
          .stream()
          .map(event -> event.getLevel().name())
          .toList()
      );
    }
    System.setProperty(MCV2.NATIVE_PROPERTY, "sometimes");
    Natives.install(this.folder, MCV2.NATIVE_AUTO);
    try (final LogCapture logs = LogCapture.capture(MCV2.class)) {
      assertTrue(Natives.resolved().failed());
      assertEquals(
        List.of("WARN", "INFO"),
        logs
          .getEvents()
          .stream()
          .map(event -> event.getLevel().name())
          .toList()
      );
      assertTrue(logs.getEvents().getFirst().getMessage().contains("MCV2 native kernels did not load"));
    }
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
        flags.addAll(
          Splitter.on(' ')
            .omitEmptyStrings()
            .splitToList(line.substring(line.indexOf(':') + 1))
        );
        break;
      }
    }
    final int levels = NativeTesting.resolution().levels();
    final boolean isX86 = flags.contains("sse2");
    assertTrue(Level.SCALAR.isIn(levels));
    assertEquals(isX86, Level.SSE2.isIn(levels));
    assertEquals(flags.contains("sse4_1"), Level.SSE41.isIn(levels));
    assertEquals(flags.contains("avx2"), Level.AVX2.isIn(levels));
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
    assertEquals(flags.containsAll(iceLake), Level.AVX512.isIn(levels));
    assertEquals(!isX86, Level.NEON.isIn(levels));
    // SVE's vector length is not in /proc/cpuinfo: without SVE neither SVE level runs, with it at most one
    final boolean sve = Level.SVE256.isIn(levels) || Level.SVE512.isIn(levels);
    assertTrue(flags.contains("sve") || !sve);
    assertFalse(Level.SVE256.isIn(levels) && Level.SVE512.isIn(levels));
  }

  @Test
  void bindsTheLibraryAndRefusesAWrongOne() {
    assumeTrue(NativeTesting.expected(), "no library loads here");
    final SymbolLookup library = NativeTesting.library();
    final int levels = NativeTesting.resolution().levels();
    final Resolution best = Natives.bind(library, "here", null, Natives.ABI);
    assertEquals(Natives.level(levels, null), Objects.requireNonNull(best.binding()).level());
    assertEquals("native " + Natives.level(levels, null).symbol() + " (here)", best.description());
    assertInstanceOf(Mcv2Internals.nested("NativeKernels"), NativeTesting.factory(best).get());
    assertEquals(Level.SCALAR, Objects.requireNonNull(Natives.bind(library, "here", "scalar", Natives.ABI).binding()).level());
    final Resolution unversioned = Natives.bind(
      name -> name.equals("mcv2_abi") ? Optional.empty() : library.find(name),
      "here",
      null,
      Natives.ABI
    );
    assertTrue(unversioned.failed());
    assertTrue(unversioned.description().startsWith("Java, the library could not be bound"));
    // a library of another interface than the bindings are written for
    final Resolution other = Natives.bind(library, "here", null, Natives.ABI + 1);
    assertTrue(other.failed());
    assertEquals("Java, the library's interface " + Natives.ABI + " is not " + (Natives.ABI + 1), other.description());
    final Resolution lacking = Natives.bind(
      name -> name.endsWith("_residual_target") ? Optional.empty() : library.find(name),
      "here",
      null,
      Natives.ABI
    );
    assertTrue(lacking.failed());
  }

  @Test
  void loadsTheExpectedKernelsAndTellsWhich() throws IOException {
    Natives.install(NativeTesting.FOLDER, MCV2.NATIVE_AUTO);
    if (NativeTesting.expected()) {
      assertTrue(MCV2.describeNatives().startsWith("native "), MCV2.describeNatives());
      assertInstanceOf(Mcv2Internals.nested("NativeKernels"), NativeTesting.factory(Natives.resolved()).get());
    } else {
      assertTrue(MCV2.describeNatives().startsWith("Java, "), MCV2.describeNatives());
      assertSame(NativeTesting.javaFactory(), NativeTesting.factory(Natives.resolved()));
    }
    // decided once, until the next install
    assertSame(Natives.resolved(), Natives.resolved());
    Natives.install(this.folder, MCV2.NATIVE_OFF);
    assertEquals("Java, turned off by mcv2.native=off", MCV2.describeNatives());
    assertSame(NativeTesting.javaFactory(), NativeTesting.factory(Natives.resolved()));
    // the system property wins over the configuration
    System.setProperty(MCV2.NATIVE_PROPERTY, MCV2.NATIVE_OFF);
    Natives.install(this.folder, MCV2.NATIVE_AUTO);
    assertEquals("Java, turned off by mcv2.native=off", MCV2.describeNatives());
    System.clearProperty(MCV2.NATIVE_PROPERTY);
    // where a library should load, a folder it cannot be extracted into is a failure, logged, and Java runs
    Natives.install(Files.writeString(this.folder.resolve("a file"), "in the way"), MCV2.NATIVE_AUTO);
    assertTrue(MCV2.describeNatives().startsWith("Java, "));
    assertEquals(NativeTesting.expected(), Natives.resolved().failed());
    assertThrows(IllegalArgumentException.class, () -> Natives.install(this.folder, "sometimes"));
    assertThrows(NullPointerException.class, () -> MCV2.installNatives(null, MCV2.NATIVE_OFF));
    assertThrows(NullPointerException.class, () -> MCV2.installNatives(this.folder, null));
    assertThrows(IllegalArgumentException.class, () -> MCV2.installNatives(this.folder, "other"));
  }

  @Test
  void publicInstallationSelectsEachLevelAndPreservesExistingEncoders() {
    MCV2.installNatives(this.folder, MCV2.NATIVE_OFF);
    final MCV2 java = new MCV2(MCV2.Settings.DEFAULT, ForkJoinPool.commonPool(), 1, true);
    final Supplier<?> javaFactory = (Supplier<?>) Mcv2Internals.field(MCV2.class, java, "kernels");
    assertSame(NativeTesting.javaFactory(), javaFactory);
    assertEquals("Java, turned off by mcv2.native=off", MCV2.describeNatives());
    for (final Level level : NativeTesting.levels()) {
      System.setProperty("mcv2.native.level", level.symbol());
      System.clearProperty(MCV2.NATIVE_PROPERTY);
      MCV2.installNatives(this.folder, MCV2.NATIVE_AUTO);
      final MCV2 encoder = new MCV2(MCV2.Settings.DEFAULT, ForkJoinPool.commonPool(), 1, true);
      final Supplier<?> nativeFactory = (Supplier<?>) Mcv2Internals.field(MCV2.class, encoder, "kernels");
      assertEquals(level, NativeTesting.view(nativeFactory.get()).level());
      assertTrue(MCV2.describeNatives().startsWith("native " + level.symbol() + " ("));
      assertSame(javaFactory, Mcv2Internals.field(MCV2.class, java, "kernels"));
      System.setProperty(MCV2.NATIVE_PROPERTY, MCV2.NATIVE_OFF);
      MCV2.installNatives(this.folder, MCV2.NATIVE_AUTO);
      final MCV2 disabled = new MCV2(MCV2.Settings.DEFAULT, ForkJoinPool.commonPool(), 1, true);
      assertSame(javaFactory, Mcv2Internals.field(MCV2.class, disabled, "kernels"));
      assertSame(nativeFactory, Mcv2Internals.field(MCV2.class, encoder, "kernels"));
      assertEquals(level, NativeTesting.view(nativeFactory.get()).level());
    }
  }
}
