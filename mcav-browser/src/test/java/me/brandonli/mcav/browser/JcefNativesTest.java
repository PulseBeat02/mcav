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
package me.brandonli.mcav.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import me.brandonli.mcav.browser.testing.Await;
import me.brandonli.mcav.utils.IoUtils;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.OsUtils;
import me.friwi.jcefmaven.EnumPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.mockito.MockedStatic;

class JcefNativesTest {

  private static final String EXTERNAL_LOCK_FILE = "install.lock.file";
  private static final String NATIVE_BUNDLE_CLASS_FILE = "me/friwi/jcefmaven/CefNativeBundle.class";
  private static final String NATIVES_ARCHIVE_SUFFIX = ".tar.gz";
  private static final String BUILD_METADATA_FILE = "build_meta.json";
  private static final String CACHED_NATIVES_FOLDER = "home/.mcav/cache/jcef";
  private static final String PARTIAL_LIBRARY_FILE = "half-written.so";
  private static final String LOCK_FILE_SUFFIX = ".lock";
  private static final String JCEF_CACHE_FOLDER = "jcef";
  private static final String JAR_FILE_SUFFIX = ".jar";
  private static final String PINNED_NATIVE_DOWNLOAD_PATH =
    "jcef-natives-linux-amd64/jcef-17e805a%2Bcef-152.0.6%2Bg708dc14%2Bchromium-152.0.7977.83/jcef-natives-linux-amd64-jcef-17e805a%2Bcef-152.0.6%2Bg708dc14%2Bchromium-152.0.7977.83.jar";

  private static final String CHECKSUM_MISMATCH_MESSAGE = "The checksum does not match!";

  private static final String HASH_SEPARATOR = " ";

  private static final String MAC_PLATFORM_SUFFIX = "-macosx-arm64";

  private static final String NATIVE_LIBRARY_TEXT = "native";

  private static final int KEEP_TRAILING_EMPTY_PARTS = -1;

  private static final String CLASS_PATH_PROPERTY = "java.class.path";

  private static final String REPOSITORY = "https://repository.test/me/friwi/";

  private static final String PLATFORM = "linux-amd64";
  private static final String PLATFORM_SUFFIX = "-" + PLATFORM;

  private static final String PINNED_HASH = "pinned";
  private static final long PINNED_SIZE = 200_000_000L;

  private static final String INSTALLATION_PREFIX = "jcef-";
  private static final String LIBRARY_FILE = "libjcef.so";

  private static final String POSIX_ATTRIBUTE_VIEW = "posix";
  private static final String EXECUTABLE_PERMISSIONS = "rwxr-xr-x";

  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern INSTALL_TIME = Pattern.compile("Installed the CEF natives for \\S+ in (\\d+) ms\\.");

  private static final long MAXIMUM_INSTALL_MILLISECONDS = 600_000L;

  @TempDir
  private Path folder;

  private final AtomicInteger downloads = new AtomicInteger();
  private final List<URI> requested = new ArrayList<>();

  // A natives jar the way jcefmaven publishes it: a zip with the tar.gz of the natives and some class files.
  private byte[] nativesJar(final JarContent content) throws IOException {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (final ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry(NATIVE_BUNDLE_CLASS_FILE));
      zip.write(new byte[] { 1, 2, 3 });
      zip.closeEntry();
      if (content == JarContent.WITH_ARCHIVE) {
        zip.putNextEntry(new ZipEntry("jcef-natives-linux-amd64-" + JcefNatives.RELEASE_TAG + NATIVES_ARCHIVE_SUFFIX));
        final InputStream archive = new ArchiveExtractorTest.Archive()
          .file(BUILD_METADATA_FILE, 0644, "{}")
          .file(LIBRARY_FILE, 0755, NATIVE_LIBRARY_TEXT)
          .finish();
        archive.transferTo(zip);
        zip.closeEntry();
      }
    }

    return bytes.toByteArray();
  }

  private JcefNatives natives(final byte[] jar) {
    return this.natives(jar, this.folder);
  }

  private JcefNatives natives(final byte[] jar, final Path installFolder) {
    return new JcefNatives(
      installFolder,
      (uri, destination, sha256, _) -> this.downloadJar(jar, uri, destination, sha256),
      REPOSITORY,
      new ArchiveExtractor()
    );
  }

  private void downloadJar(final byte[] jar, final URI uri, final Path destination, final String sha256) throws IOException {
    this.downloads.incrementAndGet();
    synchronized (this.requested) {
      this.requested.add(uri);
    }

    if (!sha256.equals(PINNED_HASH)) {
      final String message = CHECKSUM_MISMATCH_MESSAGE;
      throw new IOException(message);
    }

    try (final OutputStream output = Files.newOutputStream(destination)) {
      output.write(jar);
    }
  }

  private List<String> leftovers() throws IOException {
    try (final Stream<Path> files = Files.list(this.folder)) {
      return files
        .map(path -> path.getFileName().toString())
        .filter(name -> !name.endsWith(LOCK_FILE_SUFFIX))
        .sorted()
        .toList();
    }
  }

  private Object installLockOf(final ClassLoader loader) throws ReflectiveOperationException {
    final Class<?> natives = Class.forName(JcefNatives.class.getName(), true, loader);
    final Field field = natives.getDeclaredField("INSTALL_LOCK");
    field.setAccessible(true);

    return field.get(null);
  }

  @Test
  void shouldShareTheLockOfTheInstallationWithEveryCopyOfMcavInTheJvm() throws Exception {
    final String[] entries = System.getProperty(CLASS_PATH_PROPERTY).split(Pattern.quote(File.pathSeparator), KEEP_TRAILING_EMPTY_PARTS);
    final URL[] urls = new URL[entries.length];
    for (int index = 0; index < entries.length; index++) {
      urls[index] = Path.of(entries[index]).toUri().toURL();
    }

    try (final URLClassLoader first = new URLClassLoader(urls, null); final URLClassLoader second = new URLClassLoader(urls, null)) {
      final Class<?> secondNatives = second.loadClass(JcefNatives.class.getName());
      final Class<?> firstNatives = first.loadClass(JcefNatives.class.getName());
      assertNotSame(firstNatives, secondNatives);

      final Object secondInstallLock = this.installLockOf(second);
      final Object firstInstallLock = this.installLockOf(first);
      assertSame(firstInstallLock, secondInstallLock);
    }
  }

  @Test
  void shouldFindEveryPlatformWithNatives() {
    final Optional<JcefNatives.NativePlatform> linuxAmd64Platform = JcefNatives.detect(Os.LINUX, Arch.X86, Bits.BITS_64);
    final Optional<JcefNatives.NativePlatform> expectedLinuxAmd64Platform = Optional.of(JcefNatives.NativePlatform.LINUX_AMD64);
    assertEquals(expectedLinuxAmd64Platform, linuxAmd64Platform);

    final Optional<JcefNatives.NativePlatform> linuxArm64Platform = JcefNatives.detect(Os.LINUX, Arch.ARM, Bits.BITS_64);
    final Optional<JcefNatives.NativePlatform> expectedLinuxArm64Platform = Optional.of(JcefNatives.NativePlatform.LINUX_ARM64);
    assertEquals(expectedLinuxArm64Platform, linuxArm64Platform);

    final Optional<JcefNatives.NativePlatform> windowsAmd64Platform = JcefNatives.detect(Os.WINDOWS, Arch.X86, Bits.BITS_64);
    final Optional<JcefNatives.NativePlatform> expectedWindowsAmd64Platform = Optional.of(JcefNatives.NativePlatform.WINDOWS_AMD64);
    assertEquals(expectedWindowsAmd64Platform, windowsAmd64Platform);

    final Optional<JcefNatives.NativePlatform> windowsArm64Platform = JcefNatives.detect(Os.WINDOWS, Arch.ARM, Bits.BITS_64);
    final Optional<JcefNatives.NativePlatform> expectedWindowsArm64Platform = Optional.of(JcefNatives.NativePlatform.WINDOWS_ARM64);
    assertEquals(expectedWindowsArm64Platform, windowsArm64Platform);

    final Optional<JcefNatives.NativePlatform> macAmd64Platform = JcefNatives.detect(Os.MAC, Arch.X86, Bits.BITS_64);
    final Optional<JcefNatives.NativePlatform> expectedMacAmd64Platform = Optional.of(JcefNatives.NativePlatform.MACOS_AMD64);
    assertEquals(expectedMacAmd64Platform, macAmd64Platform);

    final Optional<JcefNatives.NativePlatform> macArm64Platform = JcefNatives.detect(Os.MAC, Arch.ARM, Bits.BITS_64);
    final Optional<JcefNatives.NativePlatform> expectedMacArm64Platform = Optional.of(JcefNatives.NativePlatform.MACOS_ARM64);
    assertEquals(expectedMacArm64Platform, macArm64Platform);
  }

  @Test
  void shouldNotFindPlatformsWithoutNatives() {
    final Optional<JcefNatives.NativePlatform> linuxX86Bits32Platform = JcefNatives.detect(Os.LINUX, Arch.X86, Bits.BITS_32);
    assertEquals(Optional.empty(), linuxX86Bits32Platform);

    final Optional<JcefNatives.NativePlatform> windowsArmBits32Platform = JcefNatives.detect(Os.WINDOWS, Arch.ARM, Bits.BITS_32);
    assertEquals(Optional.empty(), windowsArmBits32Platform);

    final Optional<JcefNatives.NativePlatform> freebsdAmd64Platform = JcefNatives.detect(Os.FREEBSD, Arch.X86, Bits.BITS_64);
    assertEquals(Optional.empty(), freebsdAmd64Platform);

    final Optional<JcefNatives.NativePlatform> linuxOtherArchitecturePlatform = JcefNatives.detect(Os.LINUX, Arch.OTHER, Bits.BITS_64);
    assertEquals(Optional.empty(), linuxOtherArchitecturePlatform);

    final Optional<JcefNatives.NativePlatform> otherSystemArm64Platform = JcefNatives.detect(Os.OTHER, Arch.ARM, Bits.BITS_64);
    assertEquals(Optional.empty(), otherSystemArm64Platform);
  }

  @Test
  void shouldPinASha256AndAPlausibleSizeForEveryPlatform() {
    for (final JcefNatives.NativePlatform platform : JcefNatives.NativePlatform.values()) {
      final boolean hexadecimalSha256 = SHA256.matcher(platform.getSha256()).matches();
      assertTrue(hexadecimalSha256, platform.getIdentifier());

      final boolean plausibleSize = platform.getSize() > 100_000_000L && platform.getSize() < 200_000_000L;
      assertTrue(plausibleSize, platform.getIdentifier());
    }
  }

  @Test
  void shouldNameEveryPlatformAsJcefmavenDoes() {
    final List<String> known = Arrays.stream(EnumPlatform.values())
      .map(EnumPlatform::getIdentifier)
      .toList();
    for (final JcefNatives.NativePlatform platform : JcefNatives.NativePlatform.values()) {
      final String identifier = platform.getIdentifier();
      final boolean named = known.contains(identifier);
      assertTrue(named, identifier);
    }
  }

  @Test
  void shouldCreateTheCacheFolderWritableByItsOwnerOnly() throws IOException {
    final Path cache = this.folder.resolve(CACHED_NATIVES_FOLDER);
    final Path installation = this.natives(this.nativesJar(JarContent.WITH_ARCHIVE), cache).install(PLATFORM, PINNED_HASH, PINNED_SIZE);
    final Path expectedInstallation = cache.resolve(INSTALLATION_PREFIX + JcefNatives.JCEFMAVEN_VERSION + PLATFORM_SUFFIX);
    assertEquals(expectedInstallation, installation);

    if (cache.getFileSystem().supportedFileAttributeViews().contains(POSIX_ATTRIBUTE_VIEW)) {
      final Set<PosixFilePermission> cachePermissions = Files.getPosixFilePermissions(cache);
      final Set<PosixFilePermission> expectedCachePermissions = PosixFilePermissions.fromString(EXECUTABLE_PERMISSIONS);
      assertEquals(expectedCachePermissions, cachePermissions);

      final Set<PosixFilePermission> cacheParentPermissions = Files.getPosixFilePermissions(cache.getParent());
      final Set<PosixFilePermission> expectedCacheParentPermissions = PosixFilePermissions.fromString(EXECUTABLE_PERMISSIONS);
      assertEquals(expectedCacheParentPermissions, cacheParentPermissions);
    }
  }

  @Test
  void shouldDownloadExtractAndMarkTheNativesOnce() throws IOException {
    final JcefNatives natives = this.natives(this.nativesJar(JarContent.WITH_ARCHIVE));
    final Path installation = natives.install(PLATFORM, PINNED_HASH, PINNED_SIZE);
    final Path expectedInstallation = this.folder.resolve(INSTALLATION_PREFIX + JcefNatives.JCEFMAVEN_VERSION + PLATFORM_SUFFIX);
    assertEquals(expectedInstallation, installation);

    final String libraryContent = Files.readString(installation.resolve(LIBRARY_FILE));
    assertEquals(NATIVE_LIBRARY_TEXT, libraryContent);

    final boolean markerPresent = Files.isRegularFile(installation.resolve(JcefNatives.INSTALL_MARKER));
    assertTrue(markerPresent);

    if (installation.getFileSystem().supportedFileAttributeViews().contains(POSIX_ATTRIBUTE_VIEW)) {
      final Set<PosixFilePermission> installationPermissions = Files.getPosixFilePermissions(installation);
      final Set<PosixFilePermission> expectedInstallationPermissions = PosixFilePermissions.fromString(EXECUTABLE_PERMISSIONS);
      assertEquals(expectedInstallationPermissions, installationPermissions);
    }

    final URI firstRequest = this.requested.getFirst();
    final URI expectedFirstRequest = URI.create(REPOSITORY + PINNED_NATIVE_DOWNLOAD_PATH);
    assertEquals(expectedFirstRequest, firstRequest);

    final List<String> leftoverNames = this.leftovers();
    final List<String> expectedLeftoverNames = List.of(installation.getFileName().toString());
    assertEquals(expectedLeftoverNames, leftoverNames, "the jar and the staging folder are gone");

    final Path secondInstallation = natives.install(PLATFORM, PINNED_HASH, PINNED_SIZE);
    assertEquals(installation, secondInstallation);

    final int downloadCount = this.downloads.get();
    assertEquals(1, downloadCount);
  }

  @Test
  void shouldTakeTheGroupWritePermissionFromNativesAnEarlierVersionInstalled() throws IOException {
    assumeTrue(
      this.folder.getFileSystem().supportedFileAttributeViews().contains(POSIX_ATTRIBUTE_VIEW),
      "This file system has no POSIX permissions!"
    );
    final JcefNatives natives = this.natives(this.nativesJar(JarContent.WITH_ARCHIVE));
    final Path installation = natives.install(PLATFORM, PINNED_HASH, PINNED_SIZE);
    final Path library = installation.resolve(LIBRARY_FILE);
    final Path marker = installation.resolve(JcefNatives.INSTALL_MARKER);
    Files.setPosixFilePermissions(this.folder, PosixFilePermissions.fromString("rwxrwxr-x"));
    Files.setPosixFilePermissions(installation, PosixFilePermissions.fromString("rwxrwxr-x"));
    Files.setPosixFilePermissions(library, PosixFilePermissions.fromString("rw-rw-rw-"));
    Files.setPosixFilePermissions(marker, PosixFilePermissions.fromString("rw-rw-r--"));
    final Path secondInstallation = natives.install(PLATFORM, PINNED_HASH, PINNED_SIZE);
    assertEquals(installation, secondInstallation);

    final int downloadCount = this.downloads.get();
    assertEquals(1, downloadCount, "nothing is installed again");

    final Set<PosixFilePermission> folderPermissions = Files.getPosixFilePermissions(this.folder);
    final Set<PosixFilePermission> expectedFolderPermissions = PosixFilePermissions.fromString(EXECUTABLE_PERMISSIONS);
    assertEquals(expectedFolderPermissions, folderPermissions);

    final Set<PosixFilePermission> installationPermissions = Files.getPosixFilePermissions(installation);
    final Set<PosixFilePermission> expectedInstallationPermissions = PosixFilePermissions.fromString(EXECUTABLE_PERMISSIONS);
    assertEquals(expectedInstallationPermissions, installationPermissions);

    final Set<PosixFilePermission> libraryPermissions = Files.getPosixFilePermissions(library);
    final Set<PosixFilePermission> expectedLibraryPermissions = PosixFilePermissions.fromString("rw-r--r--");
    assertEquals(expectedLibraryPermissions, libraryPermissions);

    final Set<PosixFilePermission> markerPermissions = Files.getPosixFilePermissions(marker);
    final Set<PosixFilePermission> expectedMarkerPermissions = PosixFilePermissions.fromString("rw-r--r--");
    assertEquals(expectedMarkerPermissions, markerPermissions);
  }

  @Test
  void shouldInstallAPlatformWithItsPin() throws IOException {
    final List<String> hashes = new ArrayList<>();
    final JcefNatives natives = new JcefNatives(
      this.folder,
      (_, destination, sha256, size) -> this.recordHashAndWriteNatives(hashes, destination, sha256, size),
      REPOSITORY,
      new ArchiveExtractor()
    );
    natives.install(JcefNatives.NativePlatform.MACOS_ARM64);
    final JcefNatives.NativePlatform mac = JcefNatives.NativePlatform.MACOS_ARM64;
    final List<String> expectedHashes = List.of(mac.getSha256() + HASH_SEPARATOR + mac.getSize());
    assertEquals(expectedHashes, hashes);

    final boolean macArm64Installed = Files.isDirectory(
      this.folder.resolve(INSTALLATION_PREFIX + JcefNatives.JCEFMAVEN_VERSION + MAC_PLATFORM_SUFFIX)
    );
    assertTrue(macArm64Installed);
  }

  private void recordHashAndWriteNatives(final List<String> hashes, final Path destination, final String sha256, final long size)
    throws IOException {
    hashes.add(sha256 + HASH_SEPARATOR + size);
    Files.write(destination, this.nativesJar(JarContent.WITH_ARCHIVE));
  }

  @Test
  void shouldGiveThisMachineItsOwnNatives() throws IOException {
    final JcefNatives natives = new JcefNatives(
      this.folder,
      (uri, destination, sha256, size) -> Files.write(destination, this.nativesJar(JarContent.WITH_ARCHIVE)),
      REPOSITORY,
      new ArchiveExtractor()
    );
    final Optional<JcefNatives.NativePlatform> platform = JcefNatives.detect(OsUtils.getOs(), OsUtils.getArch(), OsUtils.getBits());
    if (platform.isPresent()) {
      final Path installation = natives.install();
      final boolean namedForThisMachine = installation.getFileName().toString().endsWith(platform.get().getIdentifier());
      assertTrue(namedForThisMachine);
    } else {
      final IOException failure = assertThrows(IOException.class, natives::install);
      final boolean reportsNoCefBuild = failure.getMessage().contains("has no CEF build");
      assertTrue(reportsNoCefBuild);
    }
  }

  @Test
  void shouldReplaceAPartialInstallationWithoutItsMarker() throws IOException {
    final Path partial = this.folder.resolve(INSTALLATION_PREFIX + JcefNatives.JCEFMAVEN_VERSION + PLATFORM_SUFFIX);
    Files.createDirectories(partial);
    Files.writeString(partial.resolve(PARTIAL_LIBRARY_FILE), "?");
    final Path installation = this.natives(this.nativesJar(JarContent.WITH_ARCHIVE)).install(PLATFORM, PINNED_HASH, PINNED_SIZE);
    final boolean halfWrittenLeft = Files.exists(installation.resolve(PARTIAL_LIBRARY_FILE));
    assertFalse(halfWrittenLeft);

    final boolean libraryPresent = Files.exists(installation.resolve(LIBRARY_FILE));
    assertTrue(libraryPresent);
  }

  @Test
  void shouldLeaveNothingBehindAfterAFailedDownload() throws IOException {
    final JcefNatives natives = this.natives(this.nativesJar(JarContent.WITH_ARCHIVE));
    final IOException failure = assertThrows(IOException.class, () -> natives.install(PLATFORM, "tampered", PINNED_SIZE));
    final String message = failure.getMessage();
    assertEquals(CHECKSUM_MISMATCH_MESSAGE, message);

    final List<String> leftoverNames = this.leftovers();
    assertEquals(List.of(), leftoverNames);
  }

  @Test
  void shouldRefuseAJarWithoutTheArchive() throws IOException {
    final JcefNatives natives = this.natives(this.nativesJar(JarContent.CLASSES_ONLY));
    final IOException failure = assertThrows(IOException.class, () -> natives.install(PLATFORM, PINNED_HASH, PINNED_SIZE));
    final String message = failure.getMessage();
    assertEquals("The natives jar holds no .tar.gz archive!", message);

    final List<String> leftoverNames = this.leftovers();
    assertEquals(List.of(), leftoverNames);
  }

  @Test
  void shouldDownloadOnceForTwoStartsAtOnce() throws Exception {
    try (final ExecutorService starts = Executors.newFixedThreadPool(2)) {
      final CountDownLatch release = new CountDownLatch(1);
      final CountDownLatch downloading = new CountDownLatch(1);
      final AtomicReference<Thread> secondCaller = new AtomicReference<>();
      final byte[] jar = this.nativesJar(JarContent.WITH_ARCHIVE);
      final JcefNatives natives = new JcefNatives(
        this.folder,
        (uri, destination, sha256, size) -> this.downloadWhenReleased(downloading, release, destination, jar),
        REPOSITORY,
        new ArchiveExtractor()
      );
      final CompletableFuture<Path> first = CompletableFuture.supplyAsync(() -> this.install(natives), starts);
      final CompletableFuture<Path> second;
      try {
        final boolean downloadStarted = downloading.await(10, TimeUnit.SECONDS);
        assertTrue(downloadStarted, "the first install owns the download");

        second = CompletableFuture.supplyAsync(() -> this.installRecordingTheCaller(secondCaller, natives), starts);
        Await.until("the second installer waits on the held installation lock", () -> this.isBlocked(secondCaller));
        final boolean done = second.isDone();
        assertFalse(done);
      } finally {
        release.countDown();
      }
      final Path secondInstallation = second.get(30, TimeUnit.SECONDS);
      final Path firstInstallation = first.get(30, TimeUnit.SECONDS);
      assertEquals(firstInstallation, secondInstallation);

      final int downloadCount = this.downloads.get();
      assertEquals(1, downloadCount);
    }
  }

  private void downloadWhenReleased(
    final CountDownLatch downloading,
    final CountDownLatch release,
    final Path destination,
    final byte[] jar
  ) throws IOException {
    this.downloads.incrementAndGet();
    downloading.countDown();
    try {
      final boolean releasedInTime = release.await(10, TimeUnit.SECONDS);
      assertTrue(releasedInTime, "the first download is released");
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }

    Files.write(destination, jar);
  }

  private Path installRecordingTheCaller(final AtomicReference<Thread> caller, final JcefNatives natives) {
    caller.set(Thread.currentThread());

    return this.install(natives);
  }

  private boolean isBlocked(final AtomicReference<Thread> secondCaller) {
    final Thread caller = secondCaller.get();
    return caller != null && caller.getState() == Thread.State.BLOCKED;
  }

  @Test
  void shouldRefuseAPlatformWithoutNativesBeforeAnythingIsDownloaded() throws IOException {
    final JcefNatives natives = this.natives(this.nativesJar(JarContent.WITH_ARCHIVE));
    final IOException failure = assertThrows(IOException.class, () -> this.installFreeBsd(natives));
    final String message = failure.getMessage();
    assertEquals("Platform FREEBSD X86 BITS_64 has no CEF build in jcefmaven, so the browser cannot run here!", message);

    final int downloadCount = this.downloads.get();
    assertEquals(0, downloadCount);
  }

  private Path installFreeBsd(final JcefNatives natives) throws IOException {
    return natives.install(JcefNatives.detectOrFail(Os.FREEBSD, Arch.X86, Bits.BITS_64));
  }

  @Test
  void shouldSupportAMachineWhenJcefmavenHasACefBuildForIt() {
    final boolean linuxAmd64Supported = JcefNatives.isSupported(Os.LINUX, Arch.X86, Bits.BITS_64);
    assertTrue(linuxAmd64Supported);

    final boolean macArm64Supported = JcefNatives.isSupported(Os.MAC, Arch.ARM, Bits.BITS_64);
    assertTrue(macArm64Supported);

    final boolean linuxX86Bits32Supported = JcefNatives.isSupported(Os.LINUX, Arch.X86, Bits.BITS_32);
    assertFalse(linuxX86Bits32Supported, "no 32-bit builds");

    final boolean freebsdAmd64Supported = JcefNatives.isSupported(Os.FREEBSD, Arch.X86, Bits.BITS_64);
    assertFalse(freebsdAmd64Supported);

    final boolean thisMachineSupported = JcefNatives.isSupported();
    assertTrue(thisMachineSupported);

    final boolean playerSupported = BrowserPlayer.isSupported();
    assertTrue(playerSupported);

    final boolean moduleSupported = new BrowserModule().isSupported();
    assertTrue(moduleSupported);
  }

  @Test
  void shouldNotSupportAMachineWithoutACefBuild() {
    try (final MockedStatic<OsUtils> machine = mockStatic(OsUtils.class, CALLS_REAL_METHODS)) {
      machine.when(OsUtils::getOs).thenReturn(Os.FREEBSD);
      final boolean thisMachineSupported = JcefNatives.isSupported();
      assertFalse(thisMachineSupported);

      final boolean playerSupported = BrowserPlayer.isSupported();
      assertFalse(playerSupported);

      final boolean moduleSupported = new BrowserModule().isSupported();
      assertFalse(moduleSupported);
    }
  }

  @Test
  @ResourceLock(Resources.SYSTEM_ERR)
  void shouldLogTheDownloadSizeAndHowLongTheInstallationTook() throws IOException {
    final JcefNatives natives = this.natives(this.nativesJar(JarContent.WITH_ARCHIVE));
    final PrintStream standardError = System.err;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      natives.install(PLATFORM, PINNED_HASH, PINNED_SIZE);
    } finally {
      System.setErr(standardError);
    }

    final String log = captured.toString(StandardCharsets.UTF_8);
    final Matcher installTime = INSTALL_TIME.matcher(log);
    final boolean timeLogged = installTime.find();
    assertTrue(timeLogged, log);

    final long elapsed = Long.parseLong(installTime.group(1));
    final boolean plausible = elapsed < MAXIMUM_INSTALL_MILLISECONDS;
    assertTrue(plausible, log);

    final boolean sizeLogged = log.contains("(190 MB, once)");
    assertTrue(sizeLogged, log);
  }

  @Test
  void shouldFailTheInstallationClearlyWhenAnotherCopyOfMcavInThisJvmHoldsTheLock() throws IOException {
    final JcefNatives natives = this.natives(this.nativesJar(JarContent.WITH_ARCHIVE));
    final Path lockFile = this.folder.resolve(INSTALLATION_PREFIX + JcefNatives.JCEFMAVEN_VERSION + PLATFORM_SUFFIX + LOCK_FILE_SUFFIX);
    try (
      final FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      final FileLock held = channel.lock()
    ) {
      final IOException failure = assertThrows(IOException.class, () -> natives.install(PLATFORM, PINNED_HASH, PINNED_SIZE));
      final String message = failure.getMessage();
      assertEquals("Another copy of MCAV in this server is installing the CEF natives; try again once it is done!", message);

      final boolean valid = held.isValid();
      assertTrue(valid);
    }

    final int downloadsWhileLocked = this.downloads.get();
    assertEquals(0, downloadsWhileLocked);

    natives.install(PLATFORM, PINNED_HASH, PINNED_SIZE);
    final int downloadsAfterRelease = this.downloads.get();
    assertEquals(1, downloadsAfterRelease);
  }

  private Path install(final JcefNatives natives) {
    try {
      return natives.install(PLATFORM, PINNED_HASH, PINNED_SIZE);
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  @Test
  void shouldKeepTheNativesInTheCacheOfMcavByDefault() {
    final Path defaultFolder = new JcefNatives().getFolder();
    final Path expectedDefaultFolder = IoUtils.getCachedFolder().resolve(JCEF_CACHE_FOLDER);
    assertEquals(expectedDefaultFolder, defaultFolder);
  }

  @Test
  void shouldLockTheChannelOfAnInstallation() throws IOException {
    final Path file = this.folder.resolve(EXTERNAL_LOCK_FILE);
    try (
      final FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      final FileLock lock = JcefNatives.lock(channel)
    ) {
      final boolean valid = lock.isValid();
      assertTrue(valid);

      final FileChannel lockedChannel = lock.channel();
      assertSame(channel, lockedChannel);
    }
  }

  @Test
  void shouldPinTheNativesOfTheJcefOnTheClassPath() {
    // the natives and the Java side of JCEF must be of one release: bump the catalog and the pins together
    final String classPath = System.getProperty(CLASS_PATH_PROPERTY);
    final boolean namesTheJcefApiJar = classPath.contains("jcef-api-" + JcefNatives.RELEASE_TAG + JAR_FILE_SUFFIX);
    assertTrue(namesTheJcefApiJar, classPath);

    final boolean namesTheJcefmavenJar = classPath.contains("jcefmaven-" + JcefNatives.JCEFMAVEN_VERSION + JAR_FILE_SUFFIX);
    assertTrue(namesTheJcefmavenJar, classPath);
  }

  private enum JarContent {
    WITH_ARCHIVE,
    CLASSES_ONLY,
  }
}
