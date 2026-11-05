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
import static java.util.Objects.requireNonNull;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Binding;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Level;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Natives;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Resolution;
import me.brandonli.mcav.bukkit.testing.LogCapture;
import me.brandonli.mcav.bukkit.testing.UtilityClassAssertions;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class Mcv2NativesTest {

  private static final char FIELD_SEPARATOR = ' ';

  private static final byte[] NOT_A_LIBRARY = "not a library".getBytes(StandardCharsets.US_ASCII);

  private static final String LEVEL_PROPERTY = "mcv2.native.level";

  private static final String LINUX_PLATFORM = "linux-x86_64";

  private static final String DIGESTS_RESOURCE = "/mcav/mcv2/natives/SHA256SUMS";
  private static final String NATIVES_RESOURCE_FOLDER = "/mcav/mcv2/natives/";
  private static final String PATH_SEPARATOR = "/";

  private static final String AUXILIARY_VECTOR_PATH = "/proc/self/auxv";
  private static final String CPU_INFO_PATH = "/proc/cpuinfo";

  private static final String WINDOWS_LIBRARY_FILE_NAME = "mcv2kernels.dll";
  private static final String MACOS_LIBRARY_FILE_NAME = "libmcv2kernels.dylib";
  private static final String LINUX_LIBRARY_FILE_NAME = "libmcv2kernels.so";

  private static final String EXTRACTION_DIRECTORY = "created";
  private static final String OCCUPIED_DIRECTORY = "taken";
  private static final String DIRECTORY_CONTENT_FILE_NAME = "inside";

  private static final String MISSING_AUXILIARY_VECTOR_FILE_NAME = "missing";
  private static final String AUXILIARY_VECTOR_FILE_NAME = "auxv";
  private static final String WITHOUT_CAPABILITIES_FILE_NAME = "without";
  private static final String TRUNCATED_AUXILIARY_VECTOR_FILE_NAME = "truncated";
  private static final String LAST_CAPABILITIES_FILE_NAME = "last";

  private static final String UNREADABLE_MESSAGE = "unreadable!";
  private static final String NO_LIBRARY_MESSAGE = "no library loads here!";

  private static final int DIGEST_LENGTH = 64;

  private static final String OFF_DESCRIPTION = "Java, turned off by mcv2.native=off";
  private static final String NATIVE_DESCRIPTION_PREFIX = "native ";

  private static final String UNKNOWN_SETTING = "sometimes";

  private static final String BLOCKING_FILE_NAME = "a file";
  private static final String BLOCKING_FILE_TEXT = "in the way";

  private static final String AVX2_LEVEL = "avx2";
  private static final String SSE2_LEVEL = "sse2";
  private static final String SCALAR_LEVEL = "scalar";

  private static final String NATIVE_KERNELS_CLASS = "NativeKernels";

  @TempDir
  Path folder;

  @AfterEach
  void restore() {
    System.clearProperty(Mcv2.NATIVE_PROPERTY);
    System.clearProperty(LEVEL_PROPERTY);
    Natives.install(NativeTesting.NATIVES_FOLDER, Mcv2.NATIVE_AUTO);
  }

  @Test
  void shouldBeAUtilityClass() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(Natives.class);
  }

  @Test
  void shouldNameThePlatformsTheJarHasLibrariesFor() {
    final @Nullable String linuxAmd64 = Natives.platform("Linux", "amd64");
    assertEquals(LINUX_PLATFORM, linuxAmd64);

    final @Nullable String linuxLowerCase = Natives.platform("linux", "x86_64");
    assertEquals(LINUX_PLATFORM, linuxLowerCase);

    final @Nullable String linuxAarch64 = Natives.platform("Linux", "aarch64");
    assertEquals("linux-aarch64", linuxAarch64);

    final @Nullable String windowsAmd64 = Natives.platform("Windows 11", "amd64");
    assertEquals("windows-x86_64", windowsAmd64);

    final @Nullable String windowsAarch64 = Natives.platform("Windows 11", "aarch64");
    assertEquals("windows-aarch64", windowsAarch64);

    final @Nullable String macosX86 = Natives.platform("Mac OS X", "x86_64");
    assertEquals("macos-x86_64", macosX86);

    final @Nullable String macosArm64 = Natives.platform("Mac OS X", "arm64");
    assertEquals("macos-aarch64", macosArm64);

    final @Nullable String freeBsd = Natives.platform("FreeBSD", "amd64");
    assertNull(freeBsd);

    final @Nullable String linuxPowerPc = Natives.platform("Linux", "ppc64le");
    assertNull(linuxPowerPc);

    final String windowsLibrary = Natives.libraryName("windows-x86_64");
    assertEquals(WINDOWS_LIBRARY_FILE_NAME, windowsLibrary);

    final String macosLibrary = Natives.libraryName("macos-aarch64");
    assertEquals(MACOS_LIBRARY_FILE_NAME, macosLibrary);

    final String linuxLibrary = Natives.libraryName("linux-aarch64");
    assertEquals(LINUX_LIBRARY_FILE_NAME, linuxLibrary);
  }

  @Test
  void shouldShipTheLibrariesItsDigestsName() throws IOException {
    final Map<String, String> listed = new HashMap<>();
    try (final InputStream sums = Mcv2.class.getResourceAsStream(DIGESTS_RESOURCE)) {
      assertNotNull(sums);

      for (final String line : new String(sums.readAllBytes(), StandardCharsets.US_ASCII).lines().toList()) {
        final List<String> fields = Splitter.on(FIELD_SEPARATOR).omitEmptyStrings().splitToList(line);
        listed.put(fields.get(1).substring(0, fields.get(1).indexOf('/')), fields.get(0));
      }
    }

    assertEquals(Mcv2.NATIVE_DIGESTS, listed);

    for (final Map.Entry<String, String> library : Mcv2.NATIVE_DIGESTS.entrySet()) {
      final String platform = library.getKey();
      final byte[] bytes = Natives.read(
        Mcv2.class.getResourceAsStream(NATIVES_RESOURCE_FOLDER + platform + PATH_SEPARATOR + Natives.libraryName(platform))
      );
      assertNotNull(bytes, platform);
      final String expectedDigest = library.getValue();
      final String digest = Mcv2Resources.sha256(bytes);
      assertEquals(expectedDigest, digest, platform);
      // the one megabyte a native library may take in the jar
      final boolean small = bytes.length < 1_000_000;
      assertTrue(small, platform);
    }
  }

  @Test
  void shouldReadALibraryOrNothing() {
    final byte @Nullable [] nothing = Natives.read(null);
    assertNull(nothing);

    final byte[] expected = { 1, 2 };
    final byte @Nullable [] bytes = Natives.read(new ByteArrayInputStream(new byte[] { 1, 2 }));
    assertArrayEquals(expected, bytes);

    final InputStream failing = new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException(UNREADABLE_MESSAGE);
      }

      @Override
      public int read(final byte[] buffer, final int offset, final int length) throws IOException {
        throw new IOException(UNREADABLE_MESSAGE);
      }
    };
    assertThrows(UncheckedIOException.class, () -> Natives.read(failing));
  }

  @Test
  void shouldReadGeneratedDigestsAndRefuseMalformedManifests() {
    final String digest = "a".repeat(DIGEST_LENGTH);
    final String entry = "%s  linux-x86_64/libmcv2kernels.so\n".formatted(digest);
    final Map<String, String> expected = Map.of(LINUX_PLATFORM, digest);
    final Map<String, String> digests = Natives.digests(new ByteArrayInputStream(entry.getBytes(StandardCharsets.US_ASCII)));
    assertEquals(expected, digests);

    assertThrows(UnsupportedOperationException.class, () ->
      Natives.digests(new ByteArrayInputStream(entry.getBytes(StandardCharsets.US_ASCII))).clear()
    );
    final Map<String, String> nothing = Natives.digests(null);
    assertEquals(Map.of(), nothing);

    for (final String invalid : List.of(
      "",
      "garbage",
      "a  linux-x86_64/libmcv2kernels.so",
      entry + entry,
      "%sgarbage".formatted(entry),
      "%s  linux-x86_64/wrong.so".formatted(digest),
      "%s  linux-ppc64/libmcv2kernels.so".formatted(digest)
    )) {
      final Map<String, String> invalidDigests = Natives.digests(new ByteArrayInputStream(invalid.getBytes(StandardCharsets.US_ASCII)));
      assertEquals(Map.of(), invalidDigests);
    }

    final InputStream failing = new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("unreadable manifest!");
      }

      @Override
      public int read(final byte[] buffer, final int offset, final int length) throws IOException {
        throw new IOException("unreadable manifest!");
      }
    };
    final Map<String, String> failingDigests = Natives.digests(failing);
    assertEquals(Map.of(), failingDigests);
  }

  private byte @Nullable [] unexpected(final String platform) {
    final String message = "For platform %s, no library should be read!".formatted(platform);
    throw new AssertionError(message);
  }

  @Test
  void shouldRunJavaWhenOffUnknownOrWithoutALibrary() {
    final Resolution off = Natives.resolve(Mcv2.NATIVE_OFF, LINUX_PLATFORM, this.folder, this::unexpected, null);
    final @Nullable Binding offBinding = off.binding();
    assertNull(offBinding);

    final boolean offFailed = off.failed();
    assertFalse(offFailed);

    final String offDescription = off.description();
    assertEquals(OFF_DESCRIPTION, offDescription);

    final Supplier<?> expectedFactory = NativeTesting.javaFactory();
    final Supplier<?> offFactory = NativeTesting.factory(off);
    assertSame(expectedFactory, offFactory);

    final Resolution unknown = Natives.resolve(UNKNOWN_SETTING, LINUX_PLATFORM, this.folder, this::unexpected, null);
    final boolean unknownFailed = unknown.failed();
    assertTrue(unknownFailed);

    final String unknownDescription = unknown.description();
    assertEquals("Java, unknown mode mcv2.native=sometimes", unknownDescription);

    final Resolution nowhere = Natives.resolve(Mcv2.NATIVE_AUTO, null, this.folder, this::unexpected, null);
    final boolean nowhereFailed = nowhere.failed();
    assertFalse(nowhereFailed);

    final String nowhereDescription = nowhere.description();
    assertEquals("Java, no library for this operating system and processor", nowhereDescription);

    final Resolution unlisted = Natives.resolve(Mcv2.NATIVE_AUTO, "solaris-sparc", this.folder, this::unexpected, null);
    final boolean unlistedFailed = unlisted.failed();
    assertFalse(unlistedFailed);

    final String unlistedDescription = unlisted.description();
    assertEquals("Java, no library for solaris-sparc", unlistedDescription);

    final Resolution missing = Natives.resolve(Mcv2.NATIVE_AUTO, LINUX_PLATFORM, this.folder, platform -> null, null);
    final boolean missingFailed = missing.failed();
    assertFalse(missingFailed);

    final String missingDescription = missing.description();
    assertEquals("Java, no library for linux-x86_64", missingDescription);

    final Resolution unreadable = Natives.resolve(Mcv2.NATIVE_AUTO, LINUX_PLATFORM, this.folder, this::rejectUnreadableLibrary, null);
    final boolean unreadableFailed = unreadable.failed();
    assertTrue(unreadableFailed);

    final boolean unreadableDescribed = unreadable.description().startsWith("Java, the library could not be read");
    assertTrue(unreadableDescribed);
  }

  private byte[] rejectUnreadableLibrary(final String platform) {
    final IOException failure = new IOException(UNREADABLE_MESSAGE);
    throw new UncheckedIOException(failure);
  }

  @Test
  void shouldRefuseALibraryThatDoesNotMatchOrCannotBePlaced() throws IOException {
    final String digest = Mcv2Resources.sha256(NOT_A_LIBRARY);
    final Resolution altered = Natives.load(LINUX_PLATFORM, "0".repeat(DIGEST_LENGTH), NOT_A_LIBRARY, this.folder, null);
    final boolean alteredFailed = altered.failed();
    assertTrue(alteredFailed);

    final String alteredDescription = altered.description();
    assertEquals("Java, the library for linux-x86_64 does not match its SHA-256", alteredDescription);

    final Resolution homeless = Natives.load(LINUX_PLATFORM, digest, NOT_A_LIBRARY, null, null);
    final boolean homelessFailed = homeless.failed();
    assertTrue(homelessFailed);

    final String homelessDescription = homeless.description();
    assertEquals("Java, no folder was given to extract the library into", homelessDescription);

    final Path file = Files.writeString(this.folder.resolve(BLOCKING_FILE_NAME), BLOCKING_FILE_TEXT);
    final Resolution blocked = Natives.load(LINUX_PLATFORM, digest, NOT_A_LIBRARY, file, null);
    final boolean blockedFailed = blocked.failed();
    assertTrue(blockedFailed);

    final boolean blockedDescribed = blocked.description().startsWith("Java, the library could not be extracted");
    assertTrue(blockedDescribed);

    final Resolution unloadable = Natives.load(LINUX_PLATFORM, digest, NOT_A_LIBRARY, this.folder, null);
    final boolean unloadableFailed = unloadable.failed();
    assertTrue(unloadableFailed);

    final boolean unloadableDescribed = unloadable.description().startsWith("Java, the library could not be loaded");
    assertTrue(unloadableDescribed);
  }

  @Test
  void shouldExtractOnceUnderItsDigestAndReplaceAnythingElse() throws IOException {
    final String digest = Mcv2Resources.sha256(NOT_A_LIBRARY);
    final Path folder = this.folder.resolve(EXTRACTION_DIRECTORY);
    final Path file = Natives.extract(folder, "library", NOT_A_LIBRARY, digest);
    final byte[] extracted = Files.readAllBytes(file);
    assertArrayEquals(NOT_A_LIBRARY, extracted);

    final FileTime longAgo = FileTime.fromMillis(0);
    Files.setLastModifiedTime(file, longAgo);
    final Path again = Natives.extract(folder, "library", NOT_A_LIBRARY, digest);
    assertEquals(file, again);

    final FileTime modified = Files.getLastModifiedTime(file);
    assertEquals(longAgo, modified);

    Files.writeString(file, "changed");
    Natives.extract(folder, "library", NOT_A_LIBRARY, digest);
    final byte[] replaced = Files.readAllBytes(file);
    assertArrayEquals(NOT_A_LIBRARY, replaced);

    final Path taken = Files.createDirectories(folder.resolve(OCCUPIED_DIRECTORY));
    Files.writeString(taken.resolve(DIRECTORY_CONTENT_FILE_NAME), "keeps the folder non-empty");
    assertThrows(IOException.class, () -> Natives.extract(folder, OCCUPIED_DIRECTORY, NOT_A_LIBRARY, digest));

    try (final Stream<Path> files = Files.list(folder)) {
      final long fileCount = files.count();
      assertEquals(2, fileCount);
    }
  }

  @Test
  void shouldChooseTheBestLevelAtMostTheHighestAsked() {
    // an Ice Lake: scalar, sse2, sse41, avx2 and avx512
    final int iceLake = 1 | 16 | 2 | 4 | 32;
    final Level iceLakeBest = Natives.selectLevel(iceLake, null);
    assertEquals(Level.AVX512, iceLakeBest);

    final Level iceLakeAvx2 = Natives.selectLevel(iceLake, AVX2_LEVEL);
    assertEquals(Level.AVX2, iceLakeAvx2);

    final Level iceLakeSse41 = Natives.selectLevel(iceLake, "sse41");
    assertEquals(Level.SSE41, iceLakeSse41);

    final Level iceLakeSse2 = Natives.selectLevel(iceLake, SSE2_LEVEL);
    assertEquals(Level.SSE2, iceLakeSse2);

    final Level iceLakeScalar = Natives.selectLevel(iceLake, SCALAR_LEVEL);
    assertEquals(Level.SCALAR, iceLakeScalar);

    final Level iceLakeUnknown = Natives.selectLevel(iceLake, "unknown");
    assertEquals(Level.SCALAR, iceLakeUnknown);
    // a Core 2 without SSE4.1, a Haswell
    final Level coreTwoBest = Natives.selectLevel(1 | 16, null);
    assertEquals(Level.SSE2, coreTwoBest);

    final Level coreTwoAvx2 = Natives.selectLevel(1 | 16, AVX2_LEVEL);
    assertEquals(Level.SSE2, coreTwoAvx2);

    final Level haswellAvx512 = Natives.selectLevel(1 | 16 | 2 | 4, "avx512");
    assertEquals(Level.AVX2, haswellAvx512);
    // AArch64 without SVE, with 256-bit and with 512-bit SVE
    final Level aarch64Best = Natives.selectLevel(1 | 8, null);
    assertEquals(Level.NEON, aarch64Best);

    final Level sve256Best = Natives.selectLevel(1 | 8 | 64, null);
    assertEquals(Level.SVE256, sve256Best);

    final Level sve512Best = Natives.selectLevel(1 | 8 | 128, null);
    assertEquals(Level.SVE512, sve512Best);

    final Level sve512Neon = Natives.selectLevel(1 | 8 | 128, "neon");
    assertEquals(Level.NEON, sve512Neon);

    final Level scalarOnly = Natives.selectLevel(1, null);
    assertEquals(Level.SCALAR, scalarOnly);

    final boolean iceLakeHasAvx512 = Level.AVX512.isIn(iceLake);
    assertTrue(iceLakeHasAvx512);

    final boolean iceLakeHasNeon = Level.NEON.isIn(iceLake);
    assertFalse(iceLakeHasNeon);
  }

  @Test
  void shouldReadTheProcessorFeaturesLinuxGives() throws IOException {
    final long missingCapabilities = Natives.readHardwareCapabilities(this.folder.resolve(MISSING_AUXILIARY_VECTOR_FILE_NAME));
    assertEquals(0, missingCapabilities);

    // AT_PAGESZ, AT_HWCAP, AT_NULL, and the start of another entry
    final ByteBuffer vector = ByteBuffer.allocate(56).order(ByteOrder.nativeOrder());
    vector.putLong(6).putLong(4096).putLong(16).putLong(0x40_0000L).putLong(0).putLong(0).putInt(16);
    final Path given = Files.write(this.folder.resolve(AUXILIARY_VECTOR_FILE_NAME), vector.array());
    final long givenCapabilities = Natives.readHardwareCapabilities(given);
    assertEquals(0x40_0000L, givenCapabilities);

    final Path without = Files.write(this.folder.resolve(WITHOUT_CAPABILITIES_FILE_NAME), Arrays.copyOfRange(vector.array(), 0, 16));
    final long withoutCapabilities = Natives.readHardwareCapabilities(without);
    assertEquals(0, withoutCapabilities);

    final Path truncated = Files.write(
      this.folder.resolve(TRUNCATED_AUXILIARY_VECTOR_FILE_NAME),
      Arrays.copyOfRange(vector.array(), 0, 24)
    );
    final long truncatedCapabilities = Natives.readHardwareCapabilities(truncated);
    assertEquals(0, truncatedCapabilities);

    final ByteBuffer last = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
    last.putLong(16).putLong(0x40_0000L);
    final long lastCapabilities = Natives.readHardwareCapabilities(
      Files.write(this.folder.resolve(LAST_CAPABILITIES_FILE_NAME), last.array())
    );
    assertEquals(0x40_0000L, lastCapabilities);
  }

  @Test
  @SuppressWarnings("restricted") // the Linker downcalls are restricted methods of the foreign function API
  void shouldPassLinuxFeatureBitsToTheNativeLevelQuery() throws ReflectiveOperationException {
    final boolean nativesExpected = NativeTesting.areNativesExpected();
    assumeTrue(nativesExpected, NO_LIBRARY_MESSAGE);

    final long features = Natives.readHardwareCapabilities(Path.of(AUXILIARY_VECTOR_PATH));
    assumeTrue(features != 0, "Linux must supply processor feature bits!");

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
      final boolean linuxFailed = Natives.bind(library, "linux-test", null, Natives.ABI_VERSION).failed();
      assertFalse(linuxFailed);

      final long linuxFeatures = received.get();
      assertEquals(features, linuxFeatures);

      final boolean otherFailed = Natives.bind(library, "other-test", null, Natives.ABI_VERSION).failed();
      assertFalse(otherFailed);

      final long otherFeatures = received.get();
      assertEquals(0, otherFeatures);
    }
  }

  @Test
  void shouldWarnOnlyWhenNativeResolutionFails() {
    Natives.install(this.folder, Mcv2.NATIVE_OFF);
    try (final LogCapture logs = LogCapture.capture(Mcv2.class)) {
      final boolean failed = Natives.resolved().failed();
      assertFalse(failed);

      final List<String> expectedLevels = List.of("INFO");
      final List<String> levels = logs
        .getEvents()
        .stream()
        .map(event -> event.getLevel().name())
        .toList();
      assertEquals(expectedLevels, levels);
    }

    System.setProperty(Mcv2.NATIVE_PROPERTY, UNKNOWN_SETTING);
    Natives.install(this.folder, Mcv2.NATIVE_AUTO);
    try (final LogCapture logs = LogCapture.capture(Mcv2.class)) {
      final boolean failed = Natives.resolved().failed();
      assertTrue(failed);

      final List<String> expectedLevels = List.of("WARN", "INFO");
      final List<String> levels = logs
        .getEvents()
        .stream()
        .map(event -> event.getLevel().name())
        .toList();
      assertEquals(expectedLevels, levels);

      final boolean reportsTheFailure = logs.getEvents().getFirst().getMessage().contains("MCV2 native kernels did not load");
      assertTrue(reportsTheFailure);
    }
  }

  @Test
  void shouldRunTheLevelsProcCpuinfoListsTheFeaturesOf() throws IOException {
    final boolean nativesExpected = NativeTesting.areNativesExpected();
    assumeTrue(nativesExpected, NO_LIBRARY_MESSAGE);

    final Path cpuinfo = Path.of(CPU_INFO_PATH);
    final boolean readable = Files.isReadable(cpuinfo);
    assumeTrue(readable, "no /proc/cpuinfo here!");

    final Set<String> flags = new HashSet<>();
    for (final String line : Files.readAllLines(cpuinfo)) {
      // x86-64 lists its features as flags, AArch64 as Features
      if (line.startsWith("flags") || line.startsWith("Features")) {
        flags.addAll(
          Splitter.on(FIELD_SEPARATOR)
            .omitEmptyStrings()
            .splitToList(line.substring(line.indexOf(':') + 1))
        );
        break;
      }
    }

    final int levels = NativeTesting.resolution().levels();
    final boolean isX86 = flags.contains(SSE2_LEVEL);
    final boolean scalar = Level.SCALAR.isIn(levels);
    assertTrue(scalar);

    final boolean sse2 = Level.SSE2.isIn(levels);
    assertEquals(isX86, sse2);

    final boolean expectedSse41 = flags.contains("sse4_1");
    final boolean sse41 = Level.SSE41.isIn(levels);
    assertEquals(expectedSse41, sse41);

    final boolean expectedAvx2 = flags.contains(AVX2_LEVEL);
    final boolean avx2 = Level.AVX2.isIn(levels);
    assertEquals(expectedAvx2, avx2);

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
    final boolean expectedAvx512 = flags.containsAll(iceLake);
    final boolean avx512 = Level.AVX512.isIn(levels);
    assertEquals(expectedAvx512, avx512);

    final boolean expectedNeon = !isX86;
    final boolean neon = Level.NEON.isIn(levels);
    assertEquals(expectedNeon, neon);
    // SVE's vector length is not in /proc/cpuinfo: without SVE neither SVE level runs, with it at most one
    final boolean sve = Level.SVE256.isIn(levels) || Level.SVE512.isIn(levels);
    final boolean sveListed = flags.contains("sve") || !sve;
    assertTrue(sveListed);

    final boolean bothSve = Level.SVE256.isIn(levels) && Level.SVE512.isIn(levels);
    assertFalse(bothSve);
  }

  @Test
  void shouldBindTheLibraryAndRefuseAWrongOne() {
    final boolean nativesExpected = NativeTesting.areNativesExpected();
    assumeTrue(nativesExpected, NO_LIBRARY_MESSAGE);

    final SymbolLookup library = NativeTesting.library();
    final int levels = NativeTesting.resolution().levels();
    // The resolution's platform, as bind passes the library the hardware capabilities (SVE's permission) only for Linux
    final String platform = requireNonNull(Natives.platform(System.getProperty("os.name", ""), System.getProperty("os.arch", "")));
    final Resolution best = Natives.bind(library, platform, null, Natives.ABI_VERSION);
    final Level expectedLevel = Natives.selectLevel(levels, null);
    final Level bestLevel = requireNonNull(best.binding()).getLevel();
    assertEquals(expectedLevel, bestLevel);

    final String expectedDescription = NATIVE_DESCRIPTION_PREFIX + Natives.selectLevel(levels, null).getSymbol() + " (" + platform + ")";
    final String bestDescription = best.description();
    assertEquals(expectedDescription, bestDescription);

    final Class<?> expectedKernelsType = Mcv2Internals.nested(NATIVE_KERNELS_CLASS);
    final Object kernels = NativeTesting.factory(best).get();
    assertInstanceOf(expectedKernelsType, kernels);

    final Level scalarLevel = requireNonNull(Natives.bind(library, "here", SCALAR_LEVEL, Natives.ABI_VERSION).binding()).getLevel();
    assertEquals(Level.SCALAR, scalarLevel);

    final Resolution unversioned = Natives.bind(
      name -> name.equals("mcv2_abi") ? Optional.empty() : library.find(name),
      "here",
      null,
      Natives.ABI_VERSION
    );
    final boolean unversionedFailed = unversioned.failed();
    assertTrue(unversionedFailed);

    final boolean unversionedDescribed = unversioned.description().startsWith("Java, the library could not be bound");
    assertTrue(unversionedDescribed);

    final Resolution other = Natives.bind(library, "here", null, Natives.ABI_VERSION + 1);
    final boolean otherFailed = other.failed();
    assertTrue(otherFailed);

    final String expectedOtherDescription = "Java, the library's interface " + Natives.ABI_VERSION + " is not " + (Natives.ABI_VERSION + 1);
    final String otherDescription = other.description();
    assertEquals(expectedOtherDescription, otherDescription);

    final Resolution lacking = Natives.bind(
      name -> name.endsWith("_residual_target") ? Optional.empty() : library.find(name),
      "here",
      null,
      Natives.ABI_VERSION
    );
    final boolean lackingFailed = lacking.failed();
    assertTrue(lackingFailed);
  }

  @Test
  void shouldLoadTheExpectedKernelsAndTellWhich() throws IOException {
    Natives.install(NativeTesting.NATIVES_FOLDER, Mcv2.NATIVE_AUTO);

    if (NativeTesting.areNativesExpected()) {
      final boolean describesNative = Mcv2.describeNatives().startsWith(NATIVE_DESCRIPTION_PREFIX);
      assertTrue(describesNative, Mcv2.describeNatives());

      final Class<?> expectedKernelsType = Mcv2Internals.nested(NATIVE_KERNELS_CLASS);
      final Object kernels = NativeTesting.factory(Natives.resolved()).get();
      assertInstanceOf(expectedKernelsType, kernels);
    } else {
      final boolean describesJava = Mcv2.describeNatives().startsWith("Java, ");
      assertTrue(describesJava, Mcv2.describeNatives());

      final Supplier<?> expectedFactory = NativeTesting.javaFactory();
      final Supplier<?> factory = NativeTesting.factory(Natives.resolved());
      assertSame(expectedFactory, factory);
    }

    final Resolution expectedResolution = Natives.resolved();
    final Resolution resolution = Natives.resolved();
    assertSame(expectedResolution, resolution);

    Natives.install(this.folder, Mcv2.NATIVE_OFF);
    final String offDescription = Mcv2.describeNatives();
    assertEquals(OFF_DESCRIPTION, offDescription);

    final Supplier<?> expectedOffFactory = NativeTesting.javaFactory();
    final Supplier<?> offFactory = NativeTesting.factory(Natives.resolved());
    assertSame(expectedOffFactory, offFactory);

    System.setProperty(Mcv2.NATIVE_PROPERTY, Mcv2.NATIVE_OFF);
    Natives.install(this.folder, Mcv2.NATIVE_AUTO);
    final String propertyOffDescription = Mcv2.describeNatives();
    assertEquals(OFF_DESCRIPTION, propertyOffDescription);

    System.clearProperty(Mcv2.NATIVE_PROPERTY);
    Natives.install(Files.writeString(this.folder.resolve(BLOCKING_FILE_NAME), BLOCKING_FILE_TEXT), Mcv2.NATIVE_AUTO);
    final boolean describesJavaAfterFailure = Mcv2.describeNatives().startsWith("Java, ");
    assertTrue(describesJavaAfterFailure);

    final boolean expectedFailed = NativeTesting.areNativesExpected();
    final boolean failed = Natives.resolved().failed();
    assertEquals(expectedFailed, failed);

    assertThrows(IllegalArgumentException.class, () -> Natives.install(this.folder, UNKNOWN_SETTING));
    assertThrows(NullPointerException.class, () -> Mcv2.installNatives(null, Mcv2.NATIVE_OFF));
    assertThrows(NullPointerException.class, () -> Mcv2.installNatives(this.folder, null));
    assertThrows(IllegalArgumentException.class, () -> Mcv2.installNatives(this.folder, "other"));
  }

  @Test
  void shouldSelectEachLevelAndPreserveExistingEncodersOnPublicInstallation() {
    // loads from NativeTesting's folder: Windows locks a loaded library, so JUnit could not delete a temporary folder after the test
    Mcv2.installNatives(NativeTesting.NATIVES_FOLDER, Mcv2.NATIVE_OFF);
    final Mcv2 java = new Mcv2(Mcv2.Settings.DEFAULT, ForkJoinPool.commonPool(), 1, Mcv2.Verification.VERIFIED);
    final Supplier<?> javaFactory = (Supplier<?>) Mcv2Internals.field(Mcv2.class, java, "kernels");
    final Supplier<?> expectedJavaFactory = NativeTesting.javaFactory();
    assertSame(expectedJavaFactory, javaFactory);

    final String offDescription = Mcv2.describeNatives();
    assertEquals(OFF_DESCRIPTION, offDescription);

    for (final Level level : NativeTesting.levels()) {
      System.setProperty(LEVEL_PROPERTY, level.getSymbol());
      System.clearProperty(Mcv2.NATIVE_PROPERTY);
      Mcv2.installNatives(NativeTesting.NATIVES_FOLDER, Mcv2.NATIVE_AUTO);
      final Mcv2 encoder = new Mcv2(Mcv2.Settings.DEFAULT, ForkJoinPool.commonPool(), 1, Mcv2.Verification.VERIFIED);
      final Supplier<?> nativeFactory = (Supplier<?>) Mcv2Internals.field(Mcv2.class, encoder, "kernels");
      final Level nativeLevel = NativeTesting.view(nativeFactory.get()).getLevel();
      assertEquals(level, nativeLevel);
      final boolean describesLevel = Mcv2.describeNatives().startsWith(NATIVE_DESCRIPTION_PREFIX + level.getSymbol() + " (");
      assertTrue(describesLevel);
      final Object javaKernels = Mcv2Internals.field(Mcv2.class, java, "kernels");
      assertSame(javaFactory, javaKernels);

      System.setProperty(Mcv2.NATIVE_PROPERTY, Mcv2.NATIVE_OFF);
      Mcv2.installNatives(NativeTesting.NATIVES_FOLDER, Mcv2.NATIVE_AUTO);
      final Mcv2 disabled = new Mcv2(Mcv2.Settings.DEFAULT, ForkJoinPool.commonPool(), 1, Mcv2.Verification.VERIFIED);
      final Object disabledKernels = Mcv2Internals.field(Mcv2.class, disabled, "kernels");
      assertSame(javaFactory, disabledKernels);
      final Object encoderKernels = Mcv2Internals.field(Mcv2.class, encoder, "kernels");
      assertSame(nativeFactory, encoderKernels);
      final Level nativeLevelAfterDisabling = NativeTesting.view(nativeFactory.get()).getLevel();
      assertEquals(level, nativeLevelAfterDisabling);
    }
  }
}
