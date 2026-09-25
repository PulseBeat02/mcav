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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.OS;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JcefNativesTest {

  private static final String REPOSITORY = "https://repository.test/me/friwi/";

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
    return new JcefNatives(
      this.folder,
      (uri, destination, sha256) -> {
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
      return files.map(path -> path.getFileName().toString()).filter(name -> !name.endsWith(".lock")).sorted().toList();
    }
  }

  @Test
  void everyPlatformWithNativesIsFound() {
    assertEquals(Optional.of(JcefNatives.NativePlatform.LINUX_AMD64), JcefNatives.detect(OS.LINUX, Arch.X86, Bits.BITS_64));
    assertEquals(Optional.of(JcefNatives.NativePlatform.LINUX_ARM64), JcefNatives.detect(OS.LINUX, Arch.ARM, Bits.BITS_64));
    assertEquals(Optional.of(JcefNatives.NativePlatform.WINDOWS_AMD64), JcefNatives.detect(OS.WINDOWS, Arch.X86, Bits.BITS_64));
    assertEquals(Optional.of(JcefNatives.NativePlatform.WINDOWS_ARM64), JcefNatives.detect(OS.WINDOWS, Arch.ARM, Bits.BITS_64));
    assertEquals(Optional.of(JcefNatives.NativePlatform.MACOS_AMD64), JcefNatives.detect(OS.MAC, Arch.X86, Bits.BITS_64));
    assertEquals(Optional.of(JcefNatives.NativePlatform.MACOS_ARM64), JcefNatives.detect(OS.MAC, Arch.ARM, Bits.BITS_64));
  }

  @Test
  void platformsWithoutNativesAreNotFound() {
    assertEquals(Optional.empty(), JcefNatives.detect(OS.LINUX, Arch.X86, Bits.BITS_32));
    assertEquals(Optional.empty(), JcefNatives.detect(OS.WINDOWS, Arch.ARM, Bits.BITS_32));
    assertEquals(Optional.empty(), JcefNatives.detect(OS.FREEBSD, Arch.X86, Bits.BITS_64));
    assertEquals(Optional.empty(), JcefNatives.detect(OS.LINUX, Arch.OTHER, Bits.BITS_64));
    assertEquals(Optional.empty(), JcefNatives.detect(OS.OTHER, Arch.ARM, Bits.BITS_64));
  }

  @Test
  void everyPinIsASha256AndEverySizeIsPlausible() {
    for (final JcefNatives.NativePlatform platform : JcefNatives.NativePlatform.values()) {
      assertTrue(platform.getSha256().matches("[0-9a-f]{64}"), platform.getIdentifier());
      assertTrue(platform.getSize() > 100_000_000L && platform.getSize() < 200_000_000L, platform.getIdentifier());
      assertEquals(platform.getSize() / (1024L * 1024L), JcefNatives.NativePlatform.approximateMegabytes(platform.getIdentifier()));
    }
    assertEquals(0L, JcefNatives.NativePlatform.approximateMegabytes("amiga-m68k"));
  }

  @Test
  void theNativesAreDownloadedExtractedAndMarkedOnce() throws IOException {
    final JcefNatives natives = this.natives(nativesJar(true));
    final Path installation = natives.install("linux-amd64", "pinned");
    assertEquals(this.folder.resolve("jcef-" + JcefNatives.JCEFMAVEN_VERSION + "-linux-amd64"), installation);
    assertEquals("native", Files.readString(installation.resolve("libjcef.so")));
    assertTrue(Files.isRegularFile(installation.resolve(JcefNatives.INSTALL_MARKER)));
    assertEquals(
      URI.create(
        REPOSITORY +
        "jcef-natives-linux-amd64/jcef-d3de827%2Bcef-146.0.10%2Bg8219561%2Bchromium-146.0.7680.179/jcef-natives-linux-amd64-jcef-d3de827%2Bcef-146.0.10%2Bg8219561%2Bchromium-146.0.7680.179.jar"
      ),
      this.requested.getFirst()
    );
    assertEquals(List.of(installation.getFileName().toString()), this.leftovers(), "the jar and the staging folder are gone");
    assertEquals(installation, natives.install("linux-amd64", "pinned"));
    assertEquals(1, this.downloads.get());
  }

  @Test
  void aPlatformIsInstalledWithItsPin() throws IOException {
    final List<String> hashes = new ArrayList<>();
    final JcefNatives natives = new JcefNatives(
      this.folder,
      (uri, destination, sha256) -> {
        hashes.add(sha256);
        Files.write(destination, nativesJar(true));
      },
      REPOSITORY,
      new ArchiveExtractor()
    );
    natives.install(JcefNatives.NativePlatform.MACOS_ARM64);
    assertEquals(List.of(JcefNatives.NativePlatform.MACOS_ARM64.getSha256()), hashes);
    assertTrue(Files.isDirectory(this.folder.resolve("jcef-" + JcefNatives.JCEFMAVEN_VERSION + "-macosx-arm64")));
  }

  @Test
  void thisMachineGetsItsOwnNatives() throws IOException {
    final JcefNatives natives = new JcefNatives(
      this.folder,
      (uri, destination, sha256) -> Files.write(destination, nativesJar(true)),
      REPOSITORY,
      new ArchiveExtractor()
    );
    final Optional<JcefNatives.NativePlatform> platform = JcefNatives.detect(
      me.brandonli.mcav.utils.os.OSUtils.getOS(),
      me.brandonli.mcav.utils.os.OSUtils.getArch(),
      me.brandonli.mcav.utils.os.OSUtils.getBits()
    );
    if (platform.isPresent()) {
      final Path installation = natives.install();
      assertTrue(installation.getFileName().toString().endsWith(platform.get().getIdentifier()));
    } else {
      final IOException failure = assertThrows(IOException.class, natives::install);
      assertTrue(failure.getMessage().contains("has no CEF build"));
    }
  }

  @Test
  void aPartialInstallationWithoutItsMarkerIsReplaced() throws IOException {
    final Path partial = this.folder.resolve("jcef-" + JcefNatives.JCEFMAVEN_VERSION + "-linux-amd64");
    Files.createDirectories(partial);
    Files.writeString(partial.resolve("half-written.so"), "?");
    final Path installation = this.natives(nativesJar(true)).install("linux-amd64", "pinned");
    assertFalse(Files.exists(installation.resolve("half-written.so")));
    assertTrue(Files.exists(installation.resolve("libjcef.so")));
  }

  @Test
  void aFailedDownloadLeavesNothingBehind() throws IOException {
    final JcefNatives natives = this.natives(nativesJar(true));
    final IOException failure = assertThrows(IOException.class, () -> natives.install("linux-amd64", "tampered"));
    assertEquals("Checksum mismatch", failure.getMessage());
    assertEquals(List.of(), this.leftovers());
  }

  @Test
  void aJarWithoutTheArchiveIsRefused() throws IOException {
    final JcefNatives natives = this.natives(nativesJar(false));
    final IOException failure = assertThrows(IOException.class, () -> natives.install("linux-amd64", "pinned"));
    assertEquals("The natives jar holds no .tar.gz archive", failure.getMessage());
    assertEquals(List.of(), this.leftovers());
  }

  @Test
  void twoStartsAtOnceDownloadOnce() throws Exception {
    final CountDownLatch release = new CountDownLatch(1);
    final byte[] jar = nativesJar(true);
    final JcefNatives natives = new JcefNatives(
      this.folder,
      (uri, destination, sha256) -> {
        this.downloads.incrementAndGet();
        try {
          release.await(10, TimeUnit.SECONDS);
        } catch (final InterruptedException exception) {
          Thread.currentThread().interrupt();
        }
        Files.write(destination, jar);
      },
      REPOSITORY,
      new ArchiveExtractor()
    );
    final CompletableFuture<Path> first = CompletableFuture.supplyAsync(() -> install(natives));
    final CompletableFuture<Path> second = CompletableFuture.supplyAsync(() -> install(natives));
    Thread.sleep(100L);
    release.countDown();
    assertEquals(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
    assertEquals(1, this.downloads.get());
  }

  @Test
  void aPlatformWithoutNativesIsRefusedBeforeAnythingIsDownloaded() throws IOException {
    final JcefNatives natives = this.natives(nativesJar(true));
    final IOException failure = assertThrows(IOException.class, () -> natives.install(OS.FREEBSD, Arch.X86, Bits.BITS_64));
    assertEquals("jcefmaven has no CEF build for FREEBSD X86 BITS_64; the browser cannot run here", failure.getMessage());
    assertEquals(0, this.downloads.get());
  }

  @Test
  void aLockHeldByAnotherCopyOfMcavInThisJvmFailsTheInstallationClearly() throws IOException {
    final JcefNatives natives = this.natives(nativesJar(true));
    final Path lockFile = this.folder.resolve("jcef-" + JcefNatives.JCEFMAVEN_VERSION + "-linux-amd64.lock");
    try (
      final FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      final FileLock held = channel.lock()
    ) {
      final IOException failure = assertThrows(IOException.class, () -> natives.install("linux-amd64", "pinned"));
      assertEquals("Another copy of mcav in this server is installing the CEF natives; try again once it is done", failure.getMessage());
      assertTrue(held.isValid());
    }
    assertEquals(0, this.downloads.get());
    natives.install("linux-amd64", "pinned");
    assertEquals(1, this.downloads.get());
  }

  private static Path install(final JcefNatives natives) {
    try {
      return natives.install("linux-amd64", "pinned");
    } catch (final IOException exception) {
      throw new java.io.UncheckedIOException(exception);
    }
  }
}
