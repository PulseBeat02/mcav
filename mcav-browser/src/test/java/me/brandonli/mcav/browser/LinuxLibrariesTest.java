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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import me.brandonli.mcav.browser.testing.Await;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.utils.os.OS;
import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.io.TempDir;

class LinuxLibrariesTest {

  private static final String LIBRARY = "usr/lib/x86_64-linux-gnu/libmcavtest.so.1.2.3";
  private static final String POOL = "pool/main/m/mcavtest/libmcavtest1_1.2.3-1_amd64.deb";

  @TempDir
  Path folder;

  private final List<URI> downloads = new CopyOnWriteArrayList<>();

  /**
   * Builds a Debian package: an ar archive with a data.tar.xz of the given regular files and links.
   */
  static byte[] deb(final Map<String, byte[]> files, final Map<String, String> links, final String dataName) throws IOException {
    return deb(files, links, dataName, "./", false);
  }

  /**
   * Builds a Debian package whose names start with a prefix, optionally with a folder entry before its files.
   */
  static byte[] deb(
    final Map<String, byte[]> files,
    final Map<String, String> links,
    final String dataName,
    final String prefix,
    final boolean folder
  ) throws IOException {
    final ByteArrayOutputStream data = new ByteArrayOutputStream();
    try (final TarArchiveOutputStream tar = new TarArchiveOutputStream(new XZCompressorOutputStream(data))) {
      if (folder) {
        tar.putArchiveEntry(new TarArchiveEntry(prefix + "usr/", TarArchiveEntry.LF_DIR));
        tar.closeArchiveEntry();
      }
      for (final Map.Entry<String, byte[]> file : files.entrySet()) {
        final TarArchiveEntry entry = new TarArchiveEntry(prefix + file.getKey());
        entry.setSize(file.getValue().length);
        tar.putArchiveEntry(entry);
        tar.write(file.getValue());
        tar.closeArchiveEntry();
      }
      for (final Map.Entry<String, String> link : links.entrySet()) {
        // a target that starts with "=" makes a hard link, any other a symbolic one
        final boolean hard = link.getValue().startsWith("=");
        final TarArchiveEntry entry = new TarArchiveEntry(
          prefix + link.getKey(),
          hard ? TarArchiveEntry.LF_LINK : TarArchiveEntry.LF_SYMLINK
        );
        entry.setLinkName(hard ? link.getValue().substring(1) : link.getValue());
        tar.putArchiveEntry(entry);
        tar.closeArchiveEntry();
      }
    }
    final ByteArrayOutputStream deb = new ByteArrayOutputStream();
    try (final ArArchiveOutputStream ar = new ArArchiveOutputStream(deb)) {
      final byte[] version = "2.0\n".getBytes(StandardCharsets.US_ASCII);
      ar.putArchiveEntry(new ArArchiveEntry("debian-binary", version.length));
      ar.write(version);
      ar.closeArchiveEntry();
      final byte[] tarData = data.toByteArray();
      ar.putArchiveEntry(new ArArchiveEntry(dataName, tarData.length));
      ar.write(tarData);
      ar.closeArchiveEntry();
    }
    return deb.toByteArray();
  }

  private static byte[] testPackage() throws IOException {
    return deb(
      Map.of(LIBRARY, "the library".getBytes(StandardCharsets.US_ASCII), "usr/share/doc/libmcavtest1/copyright", new byte[3]),
      Map.of("usr/lib/x86_64-linux-gnu/libmcavtest.so.1", "libmcavtest.so.1.2.3"),
      "data.tar.xz"
    );
  }

  private static String sha256(final byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (final NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String pin(final byte[] deb) {
    return "linux-amd64 libmcavtest1 1.2.3-1 " + sha256(deb) + " " + deb.length + " " + POOL + " libmcavtest.so.1=" + LIBRARY;
  }

  /**
   * An installer whose downloads come from memory: a mirror named "broken" fails, every other one serves the package.
   */
  private LinuxLibraries installer(final byte[] deb, final List<String> mirrors, final List<String> pins) {
    return new LinuxLibraries(
      this.folder.resolve("cache"),
      (uri, destination, sha256) -> {
        this.downloads.add(uri);
        if (uri.getHost().equals("broken.test")) {
          throw new IOException("mirror down");
        }
        if (!sha256.equals(sha256(deb))) {
          throw new IOException("hash mismatch");
        }
        Files.write(destination, deb);
      },
      mirrors,
      pins
    );
  }

  @Test
  void theResourcePinsBothLinuxPlatformsFromTheArchiveOfDebian11() {
    final LinuxLibraries libraries = new LinuxLibraries(
      this.folder,
      (uri, destination, sha256) -> {},
      LinuxLibraries.MIRRORS,
      LinuxLibraries.readResource(LinuxLibraries.class.getClassLoader())
    );
    for (final String platform : List.of("linux-amd64", "linux-arm64")) {
      final List<LinuxLibraries.Pin> pins = libraries.getPins(platform);
      assertEquals(52, pins.size(), platform);
      // Chromium's X11 layer loads it by name and CHECKs that it did, which no NEEDED entry of libcef shows
      assertTrue(pins.stream().anyMatch(pin -> pin.sonames().contains("libX11-xcb.so.1")), platform);
      for (final LinuxLibraries.Pin pin : pins) {
        assertTrue(pin.pool().startsWith("pool/main/"), pin.pool());
        assertFalse(pin.files().isEmpty());
      }
      final LinuxLibraries.Pin nss = pins.stream().filter(pin -> pin.name().equals("libnss3")).findFirst().orElseThrow();
      assertEquals("libnss3.so", nss.sonames().getFirst(), "NSS is linked as a whole, decided by its main library");
      assertTrue(nss.sonames().contains("libsoftokn3.so"));
      assertTrue(LinuxLibraries.fingerprint(pins).matches("[0-9a-f]{12}"));
    }
    assertEquals(List.of("https://archive.debian.org/debian/", "https://deb.debian.org/debian/"), LinuxLibraries.MIRRORS);
  }

  @Test
  void aResourceThatIsMissingOrUnreadableIsABrokenBuild() {
    final ClassLoader empty = new ClassLoader(null) {};
    final IllegalStateException missing = assertThrows(IllegalStateException.class, () -> LinuxLibraries.readResource(empty));
    assertEquals("The resource me/brandonli/mcav/browser/linux-libraries.txt is missing", missing.getMessage());
    final ClassLoader broken = new ClassLoader(null) {
      @Override
      public InputStream getResourceAsStream(final String name) {
        return new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("disk gone");
          }

          @Override
          public int read(final byte[] buffer, final int offset, final int length) throws IOException {
            throw new IOException("disk gone");
          }
        };
      }
    };
    final IllegalStateException unreadable = assertThrows(IllegalStateException.class, () -> LinuxLibraries.readResource(broken));
    assertInstanceOf(UncheckedIOException.class, unreadable.getCause());
  }

  @Test
  void invalidPinsAreRefused() {
    final String hash = "0".repeat(64);
    for (final String line : List.of(
      "linux-amd64 libx 1 " + hash + " 1 pool/main/x/x/x.deb",
      "Linux_amd64 libx 1 " + hash + " 1 pool/main/x/x/x.deb libx.so.1=usr/lib/x/libx.so.1",
      "linux-amd64 libx 1 " + hash.substring(1) + " 1 pool/main/x/x/x.deb libx.so.1=usr/lib/x/libx.so.1",
      "linux-amd64 libx 1 " + hash + " 1 ../x.deb libx.so.1=usr/lib/x/libx.so.1",
      "linux-amd64 libx 1 " + hash + " 1 pool/main/x/x/x.deb x=usr/lib/x/libx.so.1",
      "linux-amd64 libx 1 " + hash + " 1 pool/main/x/x/x.deb libx.so.1=etc/passwd",
      "linux-amd64 libx 1 " + hash + " 1 pool/main/x/x/x.deb usr/lib/x/libx.so.1",
      "linux-amd64 libx 1 " + hash + " many pool/main/x/x/x.deb libx.so.1=usr/lib/x/libx.so.1",
      "host",
      "host linux-amd64 libc"
    )) {
      assertThrows(
        IllegalArgumentException.class,
        () -> new LinuxLibraries(this.folder, (uri, destination, sha256) -> {}, List.of(), List.of(line)),
        line
      );
    }
  }

  @Test
  void theLibrariesAreDownloadedVerifiedAndKeptAsFilesNamedBySoname() throws IOException {
    final byte[] deb = testPackage();
    final LinuxLibraries libraries = this.installer(deb, List.of("https://mirror.test/debian/"), List.of("# a comment", "", pin(deb)));
    final Path installation = libraries.install("linux-amd64");
    assertEquals(List.of(URI.create("https://mirror.test/debian/" + POOL)), this.downloads);
    try (final Stream<Path> files = Files.list(installation)) {
      assertEquals(
        List.of("install.lock", "libmcavtest.so.1"),
        files.map(file -> file.getFileName().toString()).sorted().toList(),
        "neither the link nor the documentation of the package"
      );
    }
    final Path library = installation.resolve("libmcavtest.so.1");
    assertArrayEquals("the library".getBytes(StandardCharsets.US_ASCII), Files.readAllBytes(library));
    if (library.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), Files.getPosixFilePermissions(library));
    }
    // installed once: another start finds the installation and downloads nothing
    assertEquals(installation, libraries.install("linux-amd64"));
    assertEquals(1, this.downloads.size());
    try (final Stream<Path> left = Files.list(this.folder.resolve("cache"))) {
      assertEquals(2, left.count(), "the installation and its lock file, nothing staged");
    }
  }

  @Test
  void aMirrorThatFailsIsFollowedByTheNextAndAllFailingIsReported() throws IOException {
    final byte[] deb = testPackage();
    final LinuxLibraries fallback =
      this.installer(deb, List.of("https://broken.test/debian/", "https://mirror.test/debian/"), List.of(pin(deb)));
    fallback.install("linux-amd64");
    assertEquals(2, this.downloads.size());
    this.downloads.clear();
    final LinuxLibraries none = new LinuxLibraries(
      this.folder.resolve("other-cache"),
      (uri, destination, sha256) -> {
        this.downloads.add(uri);
        Files.write(destination, new byte[1]);
        throw new IOException("down " + uri.getHost());
      },
      List.of("https://first.test/debian/", "https://second.test/debian/"),
      List.of(pin(deb))
    );
    final IOException failure = assertThrows(IOException.class, () -> none.install("linux-amd64"));
    assertEquals("The package libmcavtest1 could not be downloaded from any mirror", failure.getMessage());
    assertEquals("down first.test", failure.getCause().getMessage());
    assertEquals("down second.test", failure.getCause().getSuppressed()[0].getMessage());
    try (final Stream<Path> left = Files.list(this.folder.resolve("other-cache"))) {
      assertEquals(
        List.of("debian-11-linux-amd64-" + LinuxLibraries.fingerprint(none.getPins("linux-amd64")) + ".lock"),
        left.map(file -> file.getFileName().toString()).toList()
      );
    }
  }

  @Test
  void aPlatformWithoutLibrariesIsRefused() {
    final LinuxLibraries libraries = new LinuxLibraries(this.folder, (uri, destination, sha256) -> {}, List.of(), List.of());
    final IOException failure = assertThrows(IOException.class, () -> libraries.install("linux-riscv64"));
    assertEquals("mcav has no libraries for linux-riscv64", failure.getMessage());
  }

  @Test
  void aPackageMustHoldItsDataAndEveryPinnedLibraryAsARegularFile() throws IOException {
    final Map<String, String> wanted = Map.of(LIBRARY, "libmcavtest.so.1");
    final Path noData = Files.write(this.folder.resolve("no-data.deb"), deb(Map.of(LIBRARY, new byte[1]), Map.of(), "data.tar.zst"));
    final IOException member = assertThrows(IOException.class, () ->
      LinuxLibraries.extract(noData, wanted, Files.createDirectory(this.folder.resolve("a")))
    );
    assertEquals("The package no-data.deb holds no data.tar.xz", member.getMessage());
    final Path linkOnly = Files.write(this.folder.resolve("link.deb"), deb(Map.of(), Map.of(LIBRARY, "elsewhere.so"), "data.tar.xz"));
    final IOException link = assertThrows(IOException.class, () ->
      LinuxLibraries.extract(linkOnly, wanted, Files.createDirectory(this.folder.resolve("b")))
    );
    assertEquals("The package link.deb holds no [" + LIBRARY + "]", link.getMessage());
    final Path hardLink = Files.write(this.folder.resolve("hard.deb"), deb(Map.of(), Map.of(LIBRARY, "=elsewhere.so"), "data.tar.xz"));
    final IOException hard = assertThrows(IOException.class, () ->
      LinuxLibraries.extract(hardLink, wanted, Files.createDirectory(this.folder.resolve("hard")))
    );
    assertEquals("The package hard.deb holds no [" + LIBRARY + "]", hard.getMessage());
    final Path large = Files.write(this.folder.resolve("large.deb"), deb(Map.of(LIBRARY, new byte[11]), Map.of(), "data.tar.xz"));
    final IOException size = assertThrows(IOException.class, () ->
      LinuxLibraries.extract(large, wanted, Files.createDirectory(this.folder.resolve("c")), 10, 100)
    );
    assertEquals("The library " + LIBRARY + " is larger than 10 bytes", size.getMessage());
    final IOException entries = assertThrows(IOException.class, () ->
      LinuxLibraries.extract(
        Files.write(this.folder.resolve("full.deb"), testPackage()),
        wanted,
        Files.createDirectory(this.folder.resolve("d")),
        100,
        1
      )
    );
    assertEquals("The package full.deb has more than 1 entries", entries.getMessage());
    // exactly at the limits is fine
    LinuxLibraries.extract(large, wanted, Files.createDirectory(this.folder.resolve("e")), 11, 1);
  }

  @Test
  void librariesAreWrittenOnFileSystemsWithoutPosixPermissions() throws IOException {
    final Path deb = Files.write(this.folder.resolve("package.deb"), testPackage());
    try (final FileSystem zip = FileSystems.newFileSystem(this.folder.resolve("plain.zip"), Map.of("create", "true"))) {
      final Path target = zip.getPath("/");
      LinuxLibraries.extract(deb, Map.of(LIBRARY, "libmcavtest.so.1"), target);
      assertArrayEquals("the library".getBytes(StandardCharsets.US_ASCII), Files.readAllBytes(target.resolve("libmcavtest.so.1")));
    }
  }

  @Test
  void onlyThePackagesTheServerLacksAreLinkedIntoTheSession() throws IOException {
    final Path host = Files.createDirectories(this.folder.resolve("host"));
    Files.createFile(host.resolve("libpresent.so.1"));
    Files.createFile(host.resolve("libz.so.1"));
    final String hash = "0".repeat(64);
    final LinuxLibraries libraries = new LinuxLibraries(
      this.folder,
      (uri, destination, sha256) -> {},
      List.of(),
      List.of(
        "host linux-amd64 libz.so.1",
        "linux-amd64 libpresent1 1 " + hash + " 1 pool/main/p/p/p.deb libpresent.so.1=usr/lib/x86_64-linux-gnu/libpresent.so.1",
        "linux-amd64 libmissing1 1 " +
        hash +
        " 1 pool/main/m/m/m.deb libmissing.so.1=usr/lib/x86_64-linux-gnu/libmissing.so.1 " +
        "libmissingmodule.so=usr/lib/x86_64-linux-gnu/nss/libmissingmodule.so"
      )
    );
    final Path installation = this.folder.resolve("installation");
    final Path session = Files.createDirectory(this.folder.resolve("session"));
    final Path linked = libraries.link("linux-amd64", installation, session, List.of(host));
    assertEquals(session.resolve(LinuxLibraries.SESSION_FOLDER), linked);
    try (final Stream<Path> links = Files.list(linked)) {
      assertEquals(List.of("libmissing.so.1", "libmissingmodule.so"), links.map(link -> link.getFileName().toString()).sorted().toList());
    }
    assertEquals(installation.resolve("libmissing.so.1"), Files.readSymbolicLink(linked.resolve("libmissing.so.1")));
    final Path bare = Files.createDirectory(this.folder.resolve("bare-host"));
    final PlayerException lacking = assertThrows(PlayerException.class, () ->
      libraries.link("linux-amd64", installation, Files.createDirectory(this.folder.resolve("other-session")), List.of(bare))
    );
    assertEquals("The browser needs libz.so.1, which this server lacks and mcav does not bring", lacking.getMessage());
  }

  @Test
  void theFoldersOfTheLoaderComeFromItsConfigurationAndTheDefaults() throws IOException {
    final Path root = this.folder.resolve("root");
    final Path configuration = Files.createDirectories(root.resolve("etc/ld.so.conf.d"));
    Files.writeString(
      root.resolve("etc/ld.so.conf"),
      "# the loader\ninclude /etc/ld.so.conf.d/*.conf\n/opt/game/lib # after\nhwcap 0 nosegneg\n\ninclude /missing/*.conf\n"
    );
    Files.writeString(configuration.resolve("a.conf"), "/usr/local/lib\n");
    // a file that includes itself ends at the depth limit
    Files.writeString(configuration.resolve("b.conf"), "include /etc/ld.so.conf.d/b.conf\n/opt/deep\n");
    Files.writeString(configuration.resolve("c.txt"), "/not/included\n");
    final List<Path> folders = LinuxLibraries.hostFolders(root, "linux-amd64");
    final List<Path> expected = new ArrayList<>();
    for (final String path : List.of(
      "usr/local/lib",
      "opt/deep",
      "opt/game/lib",
      "lib/x86_64-linux-gnu",
      "usr/lib/x86_64-linux-gnu",
      "lib64",
      "usr/lib64",
      "lib",
      "usr/lib"
    )) {
      expected.add(root.resolve(path));
    }
    assertEquals(expected, folders);
    final List<Path> arm = LinuxLibraries.hostFolders(this.folder.resolve("empty-root"), "linux-arm64");
    assertEquals(this.folder.resolve("empty-root/lib/aarch64-linux-gnu"), arm.getFirst());
    assertEquals(6, arm.size(), "a system without a configuration has the default folders only");
    assertEquals(List.of(), LinuxLibraries.glob(Path.of("/"), "/"), "the root names no files");
    assertFalse(LinuxLibraries.isPresent("libmcav-nothing.so", List.of()));
  }

  @Test
  void theFingerprintFollowsThePinnedHashes() throws IOException {
    final byte[] deb = testPackage();
    final LinuxLibraries libraries = this.installer(deb, List.of(), List.of(pin(deb)));
    final String fingerprint = LinuxLibraries.fingerprint(libraries.getPins("linux-amd64"));
    assertEquals(sha256(sha256(deb).getBytes(StandardCharsets.US_ASCII)).substring(0, 12), fingerprint);
  }

  @Test
  @EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  void theDefaultSessionsOfLinuxLinkTheLibrariesTheServerLacks() throws IOException {
    final byte[] deb = testPackage();
    final String platform = JcefNatives.detectCurrent().getIdentifier();
    final LinuxLibraries libraries =
      this.installer(deb, List.of("https://mirror.test/debian/"), List.of(pin(deb).replace("linux-amd64", platform)));
    final HelperLauncher linux = HelperSessionTest.launcher(ScriptedEngine.class.getName(), 1_000L, OS.LINUX);
    final HelperLauncher withLibraries = CefBrowserPlayer.DefaultSessionFactory.withLibraries(linux, libraries);
    final Path session = Files.createDirectory(this.folder.resolve("session"));
    final Path linked = withLibraries.linkLibraries(session);
    assertTrue(Files.isSymbolicLink(linked.resolve("libmcavtest.so.1")), "this machine lacks the test library");
    final HelperLauncher windows = HelperSessionTest.launcher(ScriptedEngine.class.getName(), 1_000L, OS.WINDOWS);
    assertSame(windows, CefBrowserPlayer.DefaultSessionFactory.withLibraries(windows, libraries));
  }

  @Test
  void aPackageWhoseNamesHaveNoPrefixAndThatHoldsFoldersIsRead() throws IOException {
    final Path plain = Files.write(
      this.folder.resolve("plain.deb"),
      deb(Map.of(LIBRARY, new byte[] { 7 }), Map.of(), "data.tar.xz", "", true)
    );
    final Path target = Files.createDirectories(this.folder.resolve("plain"));
    LinuxLibraries.extract(plain, Map.of(LIBRARY, "libmcavtest.so.1"), target);
    assertArrayEquals(new byte[] { 7 }, Files.readAllBytes(target.resolve("libmcavtest.so.1")));
  }

  @Test
  void librariesAnotherProcessInstalledWhileThisOneWaitedAreNotInstalledAgain() throws Exception {
    final byte[] deb = testPackage();
    final LinuxLibraries libraries = this.installer(deb, List.of("https://deb.test/debian/"), List.of(pin(deb)));
    final Path installation =
      this.folder.resolve("cache").resolve("debian-11-linux-amd64-" + LinuxLibraries.fingerprint(libraries.getPins("linux-amd64")));
    final java.util.concurrent.atomic.AtomicReference<Thread> installer = new java.util.concurrent.atomic.AtomicReference<>();
    final java.util.concurrent.CompletableFuture<Path> installed;
    synchronized (LinuxLibraries.INSTALL_LOCK) {
      installed = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
        installer.set(Thread.currentThread());
        try {
          return libraries.install("linux-amd64");
        } catch (final IOException exception) {
          throw new java.io.UncheckedIOException(exception);
        }
      });
      // the installer found nothing installed and waits for the lock, while another process finishes the installation
      Await.until("the installer waits for the lock", () -> installer.get() != null && installer.get().getState() == Thread.State.BLOCKED);
      Files.createDirectories(installation);
      Files.writeString(installation.resolve(LinuxLibraries.INSTALL_MARKER), "done");
    }
    assertEquals(installation, installed.get(30, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(List.of(), this.downloads, "nothing was downloaded again");
  }

  @Test
  void aPatternWithoutARootIsReadBelowTheRoot() throws IOException {
    final Path conf = Files.createDirectories(this.folder.resolve("etc/ld.so.conf.d"));
    Files.writeString(conf.resolve("a.conf"), "/opt/a");
    Files.writeString(conf.resolve("b.txt"), "/opt/b");
    assertEquals(List.of(conf.resolve("a.conf")), LinuxLibraries.glob(this.folder, "etc/ld.so.conf.d/*.conf"));
  }
}
