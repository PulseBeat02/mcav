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

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import me.brandonli.mcav.utils.IoUtils;
import me.brandonli.mcav.utils.http.HttpDownloader;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.OsUtils;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Installs jcefmaven's CEF natives, checked against a pinned SHA-256: jcefmaven's own downloader verifies nothing and
// extracts entries outside its target folder.
final class JcefNatives {

  static final String JCEFMAVEN_VERSION = "152.0.6";

  static final String RELEASE_TAG = "jcef-17e805a+cef-152.0.6+g708dc14+chromium-152.0.7977.83";

  static final String INSTALL_MARKER = "install.lock";

  private static final Logger LOGGER = LoggerFactory.getLogger(JcefNatives.class);

  private static final String REPOSITORY = "https://repo.maven.apache.org/maven2/me/friwi/";
  private static final String ARCHIVE_SUFFIX = ".tar.gz";

  private static final String CACHE_FOLDER = "jcef";
  private static final String INSTALLATION_PREFIX = "jcef-";
  private static final String NAME_SEPARATOR = "-";
  private static final String LOCK_SUFFIX = ".lock";
  private static final String STAGING_SUFFIX = ".staging";
  private static final String JAR_SUFFIX = ".jar";

  private static final String ARTIFACT_PREFIX = "jcef-natives-";
  private static final String URL_SEPARATOR = "/";
  private static final String PLUS = "+";
  private static final String ENCODED_PLUS = "%2B";

  private static final long BYTES_PER_MEGABYTE = 1024L * 1024L;

  // interned, so every shaded copy of MCAV in the JVM shares it: a copy reopening a held lock file can release the lock
  private static final Object INSTALL_LOCK = "the installation of the CEF natives of mcav";

  private final Path folder;
  private final Downloader downloader;
  private final String repository;
  private final ArchiveExtractor extractor;

  JcefNatives() {
    this(defaultFolder(), HttpDownloader::download, REPOSITORY, new ArchiveExtractor());
  }

  @VisibleForTesting
  JcefNatives(final Path folder, final Downloader downloader, final String repository, final ArchiveExtractor extractor) {
    this.folder = folder;
    this.downloader = downloader;
    this.repository = repository;
    this.extractor = extractor;
  }

  private static Path defaultFolder() {
    final Path cache = IoUtils.getCachedFolder();
    return cache.resolve(CACHE_FOLDER);
  }

  static Optional<NativePlatform> detect(final Os os, final Arch architecture, final Bits bits) {
    for (final NativePlatform platform : NativePlatform.values()) {
      final boolean matches = platform.getOs() == os && platform.getArchitecture() == architecture && bits == Bits.BITS_64;
      if (matches) {
        return Optional.of(platform);
      }
    }

    return Optional.empty();
  }

  Path install() throws IOException {
    return this.install(detectCurrent());
  }

  Path install(final NativePlatform platform) throws IOException {
    final String identifier = platform.getIdentifier();
    final String sha256 = platform.getSha256();
    return this.install(identifier, sha256, platform.getSize());
  }

  @VisibleForTesting
  Path install(final String identifier, final String sha256, final long size) throws IOException {
    final String name = INSTALLATION_PREFIX + JCEFMAVEN_VERSION + NAME_SEPARATOR + identifier;
    final Path installation = this.folder.resolve(name);
    final Path marker = installation.resolve(INSTALL_MARKER);
    if (Files.isRegularFile(marker)) {
      // an older installation may still carry the permissions of its umask
      ArchiveExtractor.tighten(this.folder);
      return installation;
    }

    ArchiveExtractor.createFolders(this.folder);
    final Path lockFile = this.folder.resolve(name + LOCK_SUFFIX);
    synchronized (INSTALL_LOCK) {
      try (
        final FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        final FileLock lock = lock(channel)
      ) {
        this.installUnlessMarked(marker, identifier, sha256, size, installation);
        LOGGER.debug("Installation lock {} of {} was released.", lock, name);
      }
    }

    return installation;
  }

  private void installUnlessMarked(
    final Path marker,
    final String identifier,
    final String sha256,
    final long size,
    final Path installation
  ) throws IOException {
    if (!Files.isRegularFile(marker)) {
      this.installLocked(identifier, sha256, size, installation);
    }
  }

  static boolean isSupported() {
    return isSupported(OsUtils.getOs(), OsUtils.getArch(), OsUtils.getBits());
  }

  static boolean isSupported(final Os os, final Arch architecture, final Bits bits) {
    try {
      detectOrFail(os, architecture, bits);
      return true;
    } catch (final IOException exception) {
      return false;
    }
  }

  static NativePlatform detectCurrent() throws IOException {
    final Os os = OsUtils.getOs();
    final Arch architecture = OsUtils.getArch();
    final Bits bits = OsUtils.getBits();
    return detectOrFail(os, architecture, bits);
  }

  static NativePlatform detectOrFail(final Os os, final Arch architecture, final Bits bits) throws IOException {
    final @Nullable NativePlatform platform = detect(os, architecture, bits).orElse(null);
    if (platform == null) {
      final String message = "Platform %s %s %s has no CEF build in jcefmaven, so the browser cannot run here!".formatted(
        os,
        architecture,
        bits
      );
      throw new IOException(message);
    }

    return platform;
  }

  @VisibleForTesting
  static FileLock lock(final FileChannel channel) throws IOException {
    try {
      return channel.lock();
    } catch (final OverlappingFileLockException exception) {
      final String message = "Another copy of mcav in this server is installing the CEF natives; try again once it is done!";
      throw new IOException(message, exception);
    }
  }

  private void installLocked(final String identifier, final String sha256, final long size, final Path installation) throws IOException {
    final long start = System.currentTimeMillis();
    final URI uri = this.createUri(identifier);
    LOGGER.info("Downloading the CEF natives for {} ({} MB, once).", identifier, NativePlatform.approximateMegabytes(identifier));
    final Path suffix = Path.of(UUID.randomUUID().toString());
    final Path download = this.folder.resolve(installation.getFileName() + NAME_SEPARATOR + suffix + JAR_SUFFIX);
    final Path staging = this.folder.resolve(installation.getFileName() + NAME_SEPARATOR + suffix + STAGING_SUFFIX);
    try {
      this.downloader.download(uri, download, sha256, size);
      ArchiveExtractor.createFolders(staging);
      this.extractArchive(download, staging);
      final Path stagedMarker = staging.resolve(INSTALL_MARKER);
      Files.createFile(stagedMarker);
      IoUtils.deleteRecursively(installation);
      Files.move(staging, installation, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(download);
      IoUtils.deleteRecursively(staging);
    }

    final long elapsed = System.currentTimeMillis() - start;
    LOGGER.info("Installed the CEF natives for {} in {} ms.", identifier, elapsed);
  }

  private URI createUri(final String identifier) {
    final String encodedTag = RELEASE_TAG.replace(PLUS, ENCODED_PLUS);
    final String artifact = ARTIFACT_PREFIX + identifier;
    final String address =
      this.repository + artifact + URL_SEPARATOR + encodedTag + URL_SEPARATOR + artifact + NAME_SEPARATOR + encodedTag + JAR_SUFFIX;
    return URI.create(address);
  }

  private void extractArchive(final Path jar, final Path staging) throws IOException {
    final boolean extracted;
    try (final InputStream raw = Files.newInputStream(jar); final ZipInputStream zip = new ZipInputStream(raw)) {
      extracted = this.extractFirstArchive(zip, staging);
    }

    if (!extracted) {
      final String message = "The natives jar holds no %s archive!".formatted(ARCHIVE_SUFFIX);
      throw new IOException(message);
    }
  }

  private boolean extractFirstArchive(final ZipInputStream zip, final Path staging) throws IOException {
    boolean extracted = false;
    ZipEntry entry = zip.getNextEntry();
    while (entry != null && !extracted) {
      final String entryName = entry.getName();
      if (entryName.endsWith(ARCHIVE_SUFFIX)) {
        this.extractor.extract(zip, staging);
        extracted = true;
      } else {
        entry = zip.getNextEntry();
      }
    }

    return extracted;
  }

  @VisibleForTesting
  Path getFolder() {
    return this.folder;
  }

  @FunctionalInterface
  interface Downloader {
    void download(final URI uri, final Path destination, final String sha256, final long size) throws IOException;
  }

  // no 32-bit builds: Java 25 has no 32-bit Windows port, and Paper does not run on 32-bit ARM
  enum NativePlatform {
    LINUX_AMD64("linux-amd64", Os.LINUX, Arch.X86, "83f427992cd783fb6f3d499fdf275675187818aa7ca9c4a2a4d43af534a8bdfc", 152_141_475L),
    LINUX_ARM64("linux-arm64", Os.LINUX, Arch.ARM, "a653d2cbdac487663ac52957511c022a6c5dd7bf9c99f79c6d1d732ab4138a24", 167_953_639L),
    WINDOWS_AMD64("windows-amd64", Os.WINDOWS, Arch.X86, "0dc2ce28766d267f551398e24af7a9bec244b888e651bd97a14ac2d128550875", 171_200_308L),
    // jcefmaven has no JOGL here, which MCAV does not need
    WINDOWS_ARM64("windows-arm64", Os.WINDOWS, Arch.ARM, "ec77b5798dfb53c4804b69eda5144d31e20e0e17b6291c98f7b4a66533ce6504", 168_690_639L),
    MACOS_AMD64("macosx-amd64", Os.MAC, Arch.X86, "0a49f85ebc8f942fae81bc0120950ce83a629696352338f000856ee72580908a", 153_880_493L),
    MACOS_ARM64("macosx-arm64", Os.MAC, Arch.ARM, "685b27b3dc7f6899a7d4a3a3d58978c95c36ebd1afd571c7027a66fe6f1252a0", 142_331_980L);

    private final String identifier;
    private final Os os;
    private final Arch architecture;
    private final String sha256;
    private final long size;

    NativePlatform(final String identifier, final Os os, final Arch architecture, final String sha256, final long size) {
      this.identifier = identifier;
      this.os = os;
      this.architecture = architecture;
      this.sha256 = sha256;
      this.size = size;
    }

    String getIdentifier() {
      return this.identifier;
    }

    private Os getOs() {
      return this.os;
    }

    private Arch getArchitecture() {
      return this.architecture;
    }

    String getSha256() {
      return this.sha256;
    }

    long getSize() {
      return this.size;
    }

    static long approximateMegabytes(final String identifier) {
      for (final NativePlatform platform : values()) {
        if (platform.identifier.equals(identifier)) {
          return platform.size / BYTES_PER_MEGABYTE;
        }
      }

      return 0L;
    }
  }
}
