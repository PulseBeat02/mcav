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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JcefNativesTest {

  private static final String REPOSITORY = "https://repository.test/me/friwi/";
  private static final long PINNED_SIZE = 200_000_000L;

  @TempDir
  Path folder;

  private final AtomicInteger downloads = new AtomicInteger();
  private final List<URI> requested = new ArrayList<>();

  /** A natives jar the way jcefmaven publishes it: a zip with the tar.gz of the natives and some class files. */
  private static byte[] nativesJar(final boolean withArchive) throws IOException {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (final ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("me/friwi/jcefmaven/CefNativeBundle.class"));
      zip.write(new byte[] { 1, 2, 3 });
      zip.closeEntry();
      if (withArchive) {
        zip.putNextEntry(new ZipEntry("jcef-natives-linux-amd64-" + JcefNatives.RELEASE_TAG + ".tar.gz"));
        final InputStream archive = new ArchiveExtractorTest.Archive()
          .file("build_meta.json", 0644, "{}")
          .file("libjcef.so", 0755, "native")
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
      (uri, destination, sha256, size) -> {
        this.downloads.incrementAndGet();
        synchronized (this.requested) {
          this.requested.add(uri);
        }
        if (!sha256.equals("pinned")) {
          throw new IOException("Checksum mismatch");
        }
        try (final OutputStream out = Files.newOutputStream(destination)) {
          out.write(jar);
        }
      },
      REPOSITORY,
      new ArchiveExtractor()
    );
  }

  private List<String> leftovers() throws IOException {
    try (final Stream<Path> files = Files.list(this.folder)) {
      return files
        .map(path -> path.getFileName().toString())
        .filter(name -> !name.endsWith(".lock"))
        .sorted()
        .toList();
    }
  }

  private static Object installLockOf(final ClassLoader loader) throws ReflectiveOperationException {
    final Class<?> natives = Class.forName(JcefNatives.class.getName(), true, loader);
    final Field field = natives.getDeclaredField("INSTALL_LOCK");
    field.setAccessible(true);
    return field.get(null);
  }

  @Test
  void shouldShareTheLockOfTheInstallationWithEveryCopyOfMcavInTheJvm() throws Exception {
    final String[] entries = System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator), -1);
    final URL[] urls = new URL[entries.length];
    for (int index = 0; index < entries.length; index++) {
      urls[index] = Path.of(entries[index]).toUri().toURL();
    }
    try (final URLClassLoader first = new URLClassLoader(urls, null); final URLClassLoader second = new URLClassLoader(urls, null)) {
      final Class<?> secondNatives = second.loadClass(JcefNatives.class.getName());
      assertNotSame(first.loadClass(JcefNatives.class.getName()), secondNatives);
      final Object secondInstallLock = installLockOf(second);
      assertSame(installLockOf(first), secondInstallLock);
    }
  }

  @Test
  void shouldFindEveryPlatformWithNatives() {
    final Optional<JcefNatives.NativePlatform> linuxAmd64Platform = JcefNatives.detect(Os.LINUX, Arch.X86, Bits.BITS_64);
    assertEquals(Optional.of(JcefNatives.NativePlatform.LINUX_AMD64), linuxAmd64Platform);
    final Optional<JcefNatives.NativePlatform> linuxArm64Platform = JcefNatives.detect(Os.LINUX, Arch.ARM, Bits.BITS_64);
    assertEquals(Optional.of(JcefNatives.NativePlatform.LINUX_ARM64), linuxArm64Platform);
    final Optional<JcefNatives.NativePlatform> windowsAmd64Platform = JcefNatives.detect(Os.WINDOWS, Arch.X86, Bits.BITS_64);
    assertEquals(Optional.of(JcefNatives.NativePlatform.WINDOWS_AMD64), windowsAmd64Platform);
    final Optional<JcefNatives.NativePlatform> windowsArm64Platform = JcefNatives.detect(Os.WINDOWS, Arch.ARM, Bits.BITS_64);
    assertEquals(Optional.of(JcefNatives.NativePlatform.WINDOWS_ARM64), windowsArm64Platform);
    final Optional<JcefNatives.NativePlatform> macAmd64Platform = JcefNatives.detect(Os.MAC, Arch.X86, Bits.BITS_64);
    assertEquals(Optional.of(JcefNatives.NativePlatform.MACOS_AMD64), macAmd64Platform);
    final Optional<JcefNatives.NativePlatform> macArm64Platform = JcefNatives.detect(Os.MAC, Arch.ARM, Bits.BITS_64);
    assertEquals(Optional.of(JcefNatives.NativePlatform.MACOS_ARM64), macArm64Platform);
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
      final boolean hexadecimalSha256 = platform.getSha256().matches("[0-9a-f]{64}");
      assertTrue(hexadecimalSha256, platform.getIdentifier());
      final boolean plausibleSize = platform.getSize() > 100_000_000L && platform.getSize() < 200_000_000L;
      assertTrue(plausibleSize, platform.getIdentifier());
      final long platformMegabytes = JcefNatives.NativePlatform.approximateMegabytes(platform.getIdentifier());
      assertEquals(platform.getSize() / (1024L * 1024L), platformMegabytes);
    }
    final long unknownPlatformMegabytes = JcefNatives.NativePlatform.approximateMegabytes("amiga-m68k");
    assertEquals(0L, unknownPlatformMegabytes);
  }

  @Test
  void shouldCreateTheCacheFolderWritableByItsOwnerOnly() throws IOException {
    final Path cache = this.folder.resolve("home/.mcav/cache/jcef");
    final Path installation = this.natives(nativesJar(true), cache).install("linux-amd64", "pinned", PINNED_SIZE);
    assertEquals(cache.resolve("jcef-" + JcefNatives.JCEFMAVEN_VERSION + "-linux-amd64"), installation);
    if (cache.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      final Set<PosixFilePermission> cachePermissions = Files.getPosixFilePermissions(cache);
      assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), cachePermissions);
      final Set<PosixFilePermission> cacheParentPermissions = Files.getPosixFilePermissions(cache.getParent());
      assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), cacheParentPermissions);
    }
  }

  @Test
  void shouldDownloadExtractAndMarkTheNativesOnce() throws IOException {
    final JcefNatives natives = this.natives(nativesJar(true));
    final Path installation = natives.install("linux-amd64", "pinned", PINNED_SIZE);
    assertEquals(this.folder.resolve("jcef-" + JcefNatives.JCEFMAVEN_VERSION + "-linux-amd64"), installation);
    final String libraryContent = Files.readString(installation.resolve("libjcef.so"));
    assertEquals("native", libraryContent);
    final boolean markerPresent = Files.isRegularFile(installation.resolve(JcefNatives.INSTALL_MARKER));
    assertTrue(markerPresent);
    if (installation.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      final Set<PosixFilePermission> installationPermissions = Files.getPosixFilePermissions(installation);
      assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), installationPermissions);
    }
    final URI firstRequest = this.requested.getFirst();
    assertEquals(
      URI.create(
        REPOSITORY +
          "jcef-natives-linux-amd64/jcef-17e805a%2Bcef-152.0.6%2Bg708dc14%2Bchromium-152.0.7977.83/jcef-natives-linux-amd64-jcef-17e805a%2Bcef-152.0.6%2Bg708dc14%2Bchromium-152.0.7977.83.jar"
      ),
      firstRequest
    );
    final List<String> leftoverNames = this.leftovers();
    assertEquals(List.of(installation.getFileName().toString()), leftoverNames, "the jar and the staging folder are gone");
    final Path secondInstallation = natives.install("linux-amd64", "pinned", PINNED_SIZE);
    assertEquals(installation, secondInstallation);
    final int downloadCount = this.downloads.get();
    assertEquals(1, downloadCount);
  }

  @Test
  void shouldTakeTheGroupWritePermissionFromNativesAnEarlierVersionInstalled() throws IOException {
    assumeTrue(this.folder.getFileSystem().supportedFileAttributeViews().contains("posix"), "POSIX permissions");
    final JcefNatives natives = this.natives(nativesJar(true));
    final Path installation = natives.install("linux-amd64", "pinned", PINNED_SIZE);
    final Path library = installation.resolve("libjcef.so");
    final Path marker = installation.resolve(JcefNatives.INSTALL_MARKER);
    Files.setPosixFilePermissions(this.folder, PosixFilePermissions.fromString("rwxrwxr-x"));
    Files.setPosixFilePermissions(installation, PosixFilePermissions.fromString("rwxrwxr-x"));
    Files.setPosixFilePermissions(library, PosixFilePermissions.fromString("rw-rw-rw-"));
    Files.setPosixFilePermissions(marker, PosixFilePermissions.fromString("rw-rw-r--"));
    final Path secondInstallation = natives.install("linux-amd64", "pinned", PINNED_SIZE);
    assertEquals(installation, secondInstallation);
    final int downloadCount = this.downloads.get();
    assertEquals(1, downloadCount, "nothing is installed again");
    final Set<PosixFilePermission> folderPermissions = Files.getPosixFilePermissions(this.folder);
    assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), folderPermissions);
    final Set<PosixFilePermission> installationPermissions = Files.getPosixFilePermissions(installation);
    assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), installationPermissions);
    final Set<PosixFilePermission> libraryPermissions = Files.getPosixFilePermissions(library);
    assertEquals(PosixFilePermissions.fromString("rw-r--r--"), libraryPermissions);
    final Set<PosixFilePermission> markerPermissions = Files.getPosixFilePermissions(marker);
    assertEquals(PosixFilePermissions.fromString("rw-r--r--"), markerPermissions);
  }

  @Test
  void shouldInstallAPlatformWithItsPin() throws IOException {
    final List<String> hashes = new ArrayList<>();
    final JcefNatives natives = new JcefNatives(
      this.folder,
      (uri, destination, sha256, size) -> {
        hashes.add(sha256 + " " + size);
        Files.write(destination, nativesJar(true));
      },
      REPOSITORY,
      new ArchiveExtractor()
    );
    natives.install(JcefNatives.NativePlatform.MACOS_ARM64);
    final JcefNatives.NativePlatform mac = JcefNatives.NativePlatform.MACOS_ARM64;
    assertEquals(List.of(mac.getSha256() + " " + mac.getSize()), hashes);
    final boolean macArm64Installed = Files.isDirectory(this.folder.resolve("jcef-" + JcefNatives.JCEFMAVEN_VERSION + "-macosx-arm64"));
    assertTrue(macArm64Installed);
  }

  @Test
  void shouldGiveThisMachineItsOwnNatives() throws IOException {
    final JcefNatives natives = new JcefNatives(
      this.folder,
      (uri, destination, sha256, size) -> Files.write(destination, nativesJar(true)),
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
    final Path partial = this.folder.resolve("jcef-" + JcefNatives.JCEFMAVEN_VERSION + "-linux-amd64");
    Files.createDirectories(partial);
    Files.writeString(partial.resolve("half-written.so"), "?");
    final Path installation = this.natives(nativesJar(true)).install("linux-amd64", "pinned", PINNED_SIZE);
    final boolean halfWrittenLeft = Files.exists(installation.resolve("half-written.so"));
    assertFalse(halfWrittenLeft);
    final boolean libraryPresent = Files.exists(installation.resolve("libjcef.so"));
    assertTrue(libraryPresent);
  }

  @Test
  void shouldLeaveNothingBehindAfterAFailedDownload() throws IOException {
    final JcefNatives natives = this.natives(nativesJar(true));
    final IOException failure = assertThrows(IOException.class, () -> natives.install("linux-amd64", "tampered", PINNED_SIZE));
    final String message = failure.getMessage();
    assertEquals("Checksum mismatch", message);
    final List<String> leftoverNames = this.leftovers();
    assertEquals(List.of(), leftoverNames);
  }

  @Test
  void shouldRefuseAJarWithoutTheArchive() throws IOException {
    final JcefNatives natives = this.natives(nativesJar(false));
    final IOException failure = assertThrows(IOException.class, () -> natives.install("linux-amd64", "pinned", PINNED_SIZE));
    final String message = failure.getMessage();
    assertEquals("The natives jar holds no .tar.gz archive", message);
    final List<String> leftoverNames = this.leftovers();
    assertEquals(List.of(), leftoverNames);
  }

  @Test
  void shouldDownloadOnceForTwoStartsAtOnce() throws Exception {
    try (final ExecutorService starts = Executors.newFixedThreadPool(2)) {
      final CountDownLatch release = new CountDownLatch(1);
      final CountDownLatch downloading = new CountDownLatch(1);
      final AtomicReference<Thread> secondCaller = new AtomicReference<>();
      final byte[] jar = nativesJar(true);
      final JcefNatives natives = new JcefNatives(
        this.folder,
        (uri, destination, sha256, size) -> {
          this.downloads.incrementAndGet();
          downloading.countDown();
          try {
            final boolean releasedInTime = release.await(10, TimeUnit.SECONDS);
            assertTrue(releasedInTime, "the first download is released");
          } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
          }
          Files.write(destination, jar);
        },
        REPOSITORY,
        new ArchiveExtractor()
      );
      final CompletableFuture<Path> first = CompletableFuture.supplyAsync(() -> install(natives), starts);
      final CompletableFuture<Path> second;
      try {
        final boolean downloadStarted = downloading.await(10, TimeUnit.SECONDS);
        assertTrue(downloadStarted, "the first install owns the download");
        second = CompletableFuture.supplyAsync(() -> {
          secondCaller.set(Thread.currentThread());
          return install(natives);
        }, starts);
        Await.until("the second installer waits on the held installation lock", () -> {
          final Thread caller = secondCaller.get();
          return caller != null && caller.getState() == Thread.State.BLOCKED;
        });
        final boolean done = second.isDone();
        assertFalse(done);
      } finally {
        release.countDown();
      }
      final Path secondInstallation = second.get(30, TimeUnit.SECONDS);
      assertEquals(first.get(30, TimeUnit.SECONDS), secondInstallation);
      final int downloadCount = this.downloads.get();
      assertEquals(1, downloadCount);
    }
  }

  @Test
  void shouldRefuseAPlatformWithoutNativesBeforeAnythingIsDownloaded() throws IOException {
    final JcefNatives natives = this.natives(nativesJar(true));
    final IOException failure = assertThrows(IOException.class, () ->
      natives.install(JcefNatives.detectOrFail(Os.FREEBSD, Arch.X86, Bits.BITS_64))
    );
    final String message = failure.getMessage();
    assertEquals("jcefmaven has no CEF build for FREEBSD X86 BITS_64; the browser cannot run here", message);
    final int downloadCount = this.downloads.get();
    assertEquals(0, downloadCount);
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
  void shouldFailTheInstallationClearlyWhenAnotherCopyOfMcavInThisJvmHoldsTheLock() throws IOException {
    final JcefNatives natives = this.natives(nativesJar(true));
    final Path lockFile = this.folder.resolve("jcef-" + JcefNatives.JCEFMAVEN_VERSION + "-linux-amd64.lock");
    try (
      final FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      final FileLock held = channel.lock()
    ) {
      final IOException failure = assertThrows(IOException.class, () -> natives.install("linux-amd64", "pinned", PINNED_SIZE));
      final String message = failure.getMessage();
      assertEquals("Another copy of mcav in this server is installing the CEF natives; try again once it is done", message);
      final boolean valid = held.isValid();
      assertTrue(valid);
    }
    final int downloadsWhileLocked = this.downloads.get();
    assertEquals(0, downloadsWhileLocked);
    natives.install("linux-amd64", "pinned", PINNED_SIZE);
    final int downloadsAfterRelease = this.downloads.get();
    assertEquals(1, downloadsAfterRelease);
  }

  private static Path install(final JcefNatives natives) {
    try {
      return natives.install("linux-amd64", "pinned", PINNED_SIZE);
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  @Test
  void shouldKeepTheNativesInTheCacheOfMcavByDefault() {
    final Path defaultFolder = new JcefNatives().getFolder();
    assertEquals(IoUtils.getCachedFolder().resolve("jcef"), defaultFolder);
  }

  @Test
  void shouldHoldTheLockOfAnInstallationUntilItIsReleased() throws IOException {
    final Path file = this.folder.resolve("install.lock.file");
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
    // the natives and the Java side of JCEF must be of one release: a bump of the catalog needs new pins, and the
    // pins a bump of the catalog
    final String classPath = System.getProperty("java.class.path");
    final boolean namesTheJcefApiJar = classPath.contains("jcef-api-" + JcefNatives.RELEASE_TAG + ".jar");
    assertTrue(namesTheJcefApiJar, classPath);
    final boolean namesTheJcefmavenJar = classPath.contains("jcefmaven-" + JcefNatives.JCEFMAVEN_VERSION + ".jar");
    assertTrue(namesTheJcefmavenJar, classPath);
  }
}
