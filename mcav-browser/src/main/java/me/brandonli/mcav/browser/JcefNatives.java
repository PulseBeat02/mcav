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
import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.http.HttpDownloader;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.OSUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Installs the CEF natives of jcefmaven for this machine into mcav's cache folder ({@code ~/.mcav/cache/jcef}), the
 * folder mcav's other runtime downloads use, the first time a browser starts.
 *
 * <p>The natives come from Maven Central ({@code me.friwi:jcef-natives-<platform>}) and are verified against the
 * SHA-256 hash pinned here, which was checked against Central's SHA-1 and the release's PGP signature when it was
 * pinned. jcefmaven's own downloader is never used: it verifies nothing and its extraction does not keep archive
 * entries inside the target folder. The archive is extracted by {@link ArchiveExtractor} into a staging folder, which
 * is moved into place with the {@code install.lock} marker jcefmaven's {@code CefAppBuilder} looks for, so a folder
 * without that marker is never used. An installation lock file keeps two servers on one machine from installing at
 * the same time.
 *
 * <p>Platforms without natives, such as 32-bit systems, FreeBSD or RISC-V, cannot run the browser at all:
 * {@link #install()} fails with a message naming the platform.
 */
final class JcefNatives {

  /**
   * The version of jcefmaven the natives belong to.
   */
  static final String JCEFMAVEN_VERSION = "146.0.10";

  /**
   * The release of JCEF, CEF and Chromium the natives were built from.
   */
  static final String RELEASE_TAG = "jcef-d3de827+cef-146.0.10+g8219561+chromium-146.0.7680.179";

  /**
   * The marker file of a complete installation, the one jcefmaven checks.
   */
  static final String INSTALL_MARKER = "install.lock";

  private static final Logger LOGGER = LoggerFactory.getLogger(JcefNatives.class);
  private static final String REPOSITORY = "https://repo.maven.apache.org/maven2/me/friwi/";
  private static final String ARCHIVE_SUFFIX = ".tar.gz";
  // every copy of mcav in this JVM shares this monitor, whatever class loader loaded it: equal string literals are one
  // object in the whole JVM, and a text without a package name is not renamed when mcav is shaded. So a copy never
  // opens the lock file while another one holds it, which would release the lock of the process on some systems.
  private static final Object INSTALL_LOCK = "the installation of the CEF natives of mcav";

  private final Path folder;
  private final Downloader downloader;
  private final String repository;
  private final ArchiveExtractor extractor;

  /**
   * Constructs an installer that uses mcav's cache folder and Maven Central.
   */
  JcefNatives() {
    this(defaultFolder(), HttpDownloader::download, REPOSITORY, new ArchiveExtractor());
  }

  /**
   * Constructs an installer with another folder, download and repository, so tests can install from a local copy.
   *
   * @param folder     the folder the natives are installed in
   * @param downloader downloads a file and verifies its hash
   * @param repository the base address of the {@code me.friwi} group of the repository, ending with a slash
   * @param extractor  extracts the archive
   */
  @VisibleForTesting
  JcefNatives(final Path folder, final Downloader downloader, final String repository, final ArchiveExtractor extractor) {
    this.folder = folder;
    this.downloader = downloader;
    this.repository = repository;
    this.extractor = extractor;
  }

  /**
   * Gets the folder the natives are installed in, so tests can check the default one.
   *
   * @return the folder
   */
  @VisibleForTesting
  Path getFolder() {
    return this.folder;
  }

  private static Path defaultFolder() {
    final Path cache = IOUtils.getCachedFolder();
    return cache.resolve("jcef");
  }

  /**
   * Finds the natives of a platform.
   *
   * @param os   the operating system
   * @param arch the processor architecture
   * @param bits the width of the processor
   * @return the natives, or empty if jcefmaven has none mcav can use there
   */
  static Optional<NativePlatform> detect(final OS os, final Arch arch, final Bits bits) {
    for (final NativePlatform platform : NativePlatform.values()) {
      final boolean matches = platform.getOs() == os && platform.getArch() == arch && bits == Bits.BITS_64;
      if (matches) {
        return Optional.of(platform);
      }
    }
    return Optional.empty();
  }

  /**
   * Installs the natives of this machine unless they are installed already.
   *
   * @return the folder of the installation
   * @throws IOException if the platform has no natives, or the download, the verification or the extraction fails
   */
  Path install() throws IOException {
    return this.install(detectCurrent());
  }

  /**
   * Checks whether jcefmaven has a CEF build for this machine.
   *
   * @return true if the natives of this machine exist
   */
  static boolean isSupported() {
    return isSupported(OSUtils.getOS(), OSUtils.getArch(), OSUtils.getBits());
  }

  /**
   * Checks whether jcefmaven has a CEF build for a machine.
   *
   * @param os   the operating system
   * @param arch the processor architecture
   * @param bits the address width
   * @return true if the natives of the machine exist
   */
  static boolean isSupported(final OS os, final Arch arch, final Bits bits) {
    try {
      detectOrFail(os, arch, bits);
      return true;
    } catch (final IOException exception) {
      return false;
    }
  }

  /**
   * Finds the natives of this machine.
   *
   * @return the platform
   * @throws IOException if jcefmaven has no CEF build for this machine
   */
  static NativePlatform detectCurrent() throws IOException {
    final OS os = OSUtils.getOS();
    final Arch arch = OSUtils.getArch();
    final Bits bits = OSUtils.getBits();
    return detectOrFail(os, arch, bits);
  }

  /**
   * Finds the natives of a machine.
   *
   * @param os   the operating system of the machine
   * @param arch the processor architecture of the machine
   * @param bits the width of its processor
   * @return the platform
   * @throws IOException if jcefmaven has no CEF build for the machine
   */
  static NativePlatform detectOrFail(final OS os, final Arch arch, final Bits bits) throws IOException {
    final Optional<NativePlatform> platform = detect(os, arch, bits);
    if (platform.isEmpty()) {
      throw new IOException("jcefmaven has no CEF build for " + os + " " + arch + " " + bits + "; the browser cannot run here");
    }
    return platform.get();
  }

  /**
   * Installs the natives of a platform unless they are installed already.
   *
   * @param platform the platform
   * @return the folder of the installation
   * @throws IOException if the download, the verification or the extraction fails
   */
  Path install(final NativePlatform platform) throws IOException {
    final String identifier = platform.getIdentifier();
    final String sha256 = platform.getSha256();
    return this.install(identifier, sha256);
  }

  /**
   * Installs the natives of a platform identifier with a pinned hash unless they are installed already.
   *
   * @param identifier the jcefmaven identifier of the platform, such as {@code linux-amd64}
   * @param sha256     the pinned SHA-256 hash of the natives jar
   * @return the folder of the installation
   * @throws IOException if the download, the verification or the extraction fails
   */
  @VisibleForTesting
  Path install(final String identifier, final String sha256) throws IOException {
    final String name = "jcef-" + JCEFMAVEN_VERSION + "-" + identifier;
    final Path installation = this.folder.resolve(name);
    final Path marker = installation.resolve(INSTALL_MARKER);
    if (Files.isRegularFile(marker)) {
      return installation;
    }
    Files.createDirectories(this.folder);
    final Path lockFile = this.folder.resolve(name + ".lock");
    synchronized (INSTALL_LOCK) {
      try (
        final FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        final FileLock lock = lock(channel)
      ) {
        // another process may have installed the natives while this one waited for the lock
        if (!Files.isRegularFile(marker)) {
          this.installLocked(identifier, sha256, installation);
        }
        LOGGER.debug("Released the installation lock {} of {}", lock, name);
      }
    }
    return installation;
  }

  /**
   * Takes the lock of an installation, waiting while another process installs.
   *
   * @param channel the lock file
   * @return the lock
   * @throws IOException if the lock cannot be taken, or another copy of mcav in this JVM holds it, such as the copy of a
   *                     plugin that was disabled while it installed the natives
   */
  @VisibleForTesting
  static FileLock lock(final FileChannel channel) throws IOException {
    try {
      return channel.lock();
    } catch (final OverlappingFileLockException exception) {
      throw new IOException("Another copy of mcav in this server is installing the CEF natives; try again once it is done", exception);
    }
  }

  private void installLocked(final String identifier, final String sha256, final Path installation) throws IOException {
    final long start = System.currentTimeMillis();
    final URI uri = this.createUri(identifier);
    LOGGER.info("Downloading the CEF natives for {} ({} MB, once)", identifier, NativePlatform.approximateMegabytes(identifier));
    final Path suffix = Path.of(UUID.randomUUID().toString());
    final Path download = this.folder.resolve(installation.getFileName() + "-" + suffix + ".jar");
    final Path staging = this.folder.resolve(installation.getFileName() + "-" + suffix + ".staging");
    try {
      this.downloader.download(uri, download, sha256);
      Files.createDirectories(staging);
      this.extractArchive(download, staging);
      final Path stagedMarker = staging.resolve(INSTALL_MARKER);
      Files.createFile(stagedMarker);
      IOUtils.deleteRecursively(installation);
      Files.move(staging, installation, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(download);
      IOUtils.deleteRecursively(staging);
    }
    final long elapsed = System.currentTimeMillis() - start;
    LOGGER.info("Installed the CEF natives for {} in {} ms", identifier, elapsed);
  }

  private URI createUri(final String identifier) {
    final String encodedTag = RELEASE_TAG.replace("+", "%2B");
    final String artifact = "jcef-natives-" + identifier;
    final String address = this.repository + artifact + "/" + encodedTag + "/" + artifact + "-" + encodedTag + ".jar";
    return URI.create(address);
  }

  private void extractArchive(final Path jar, final Path staging) throws IOException {
    boolean extracted = false;
    try (final InputStream raw = Files.newInputStream(jar); final ZipInputStream zip = new ZipInputStream(raw)) {
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
    }
    if (!extracted) {
      throw new IOException("The natives jar holds no " + ARCHIVE_SUFFIX + " archive");
    }
  }

  /**
   * Downloads a file and verifies its SHA-256 hash.
   */
  @FunctionalInterface
  interface Downloader {
    /**
     * Downloads a file.
     *
     * @param uri         the address
     * @param destination the file to write
     * @param sha256      the expected SHA-256 hash in hexadecimal
     * @throws IOException if the download fails or the hash does not match
     */
    void download(URI uri, Path destination, String sha256) throws IOException;
  }

  /**
   * The platforms jcefmaven publishes natives for that mcav can run a browser on, with the pinned SHA-256 hash and
   * size of each natives jar of {@link #RELEASE_TAG}. The 32-bit builds are left out: Java 25 has no 32-bit Windows
   * port, and Paper does not run on 32-bit ARM.
   */
  enum NativePlatform {
    /** Linux on x86-64. */
    LINUX_AMD64("linux-amd64", OS.LINUX, Arch.X86, "b089370c3bf8f853183ec822817e1b58348d90d1236bb6a26e921f513449dac2", 147_903_463L),
    /** Linux on 64-bit ARM. */
    LINUX_ARM64("linux-arm64", OS.LINUX, Arch.ARM, "ad9fa0818b43d9e11fb5622b76fd5af8eadbcd33f11ff1ba106d64c52ad63c09", 165_191_345L),
    /** Windows on x86-64. */
    WINDOWS_AMD64("windows-amd64", OS.WINDOWS, Arch.X86, "e9a2d4ef3c33b372c24655ee4cdaf9ef23fe2de5f7e57410576569977299c4b4", 162_120_546L),
    /** Windows on 64-bit ARM; mcav's browser does not need JOGL, which jcefmaven lacks there. */
    WINDOWS_ARM64("windows-arm64", OS.WINDOWS, Arch.ARM, "735b039c27fb9a79904938be6348fd0db6db9c395e79ded6a48dfb0e18e8ed22", 160_379_728L),
    /** macOS on Intel. */
    MACOS_AMD64("macosx-amd64", OS.MAC, Arch.X86, "4c05aae6c80841bf763a9d0dcdc3c3332af57abe0ab76091fb7bb9ef128e1bad", 145_034_271L),
    /** macOS on Apple silicon. */
    MACOS_ARM64("macosx-arm64", OS.MAC, Arch.ARM, "b9c8ad6809372db9b6597911b7d3f12f0f6b9b23ef1506261b5518f78332642b", 135_870_260L);

    private final String identifier;
    private final OS os;
    private final Arch arch;
    private final String sha256;
    private final long size;

    NativePlatform(final String identifier, final OS os, final Arch arch, final String sha256, final long size) {
      this.identifier = identifier;
      this.os = os;
      this.arch = arch;
      this.sha256 = sha256;
      this.size = size;
    }

    /**
     * Gets the jcefmaven identifier of the platform.
     *
     * @return the identifier, such as {@code linux-amd64}
     */
    String getIdentifier() {
      return this.identifier;
    }

    OS getOs() {
      return this.os;
    }

    Arch getArch() {
      return this.arch;
    }

    /**
     * Gets the pinned SHA-256 hash of the natives jar.
     *
     * @return the hash in hexadecimal
     */
    String getSha256() {
      return this.sha256;
    }

    /**
     * Gets the size of the natives jar.
     *
     * @return the size in bytes
     */
    long getSize() {
      return this.size;
    }

    /**
     * Gets the approximate size of the natives jar of a platform identifier, for the log.
     *
     * @param identifier the identifier
     * @return the size in megabytes, or 0 for an unknown identifier
     */
    static long approximateMegabytes(final String identifier) {
      for (final NativePlatform platform : values()) {
        if (platform.identifier.equals(identifier)) {
          return platform.size / (1024L * 1024L);
        }
      }
      return 0L;
    }
  }
}
