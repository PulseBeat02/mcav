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
package me.brandonli.mcav.capability.installer.ytdlp;

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import me.brandonli.mcav.capability.installer.AbstractInstaller;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.ZipEntryIntegrityException;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.Platform;

/**
 * Installs <a href="https://github.com/yt-dlp/yt-dlp">yt-dlp</a>, which the library uses to resolve stream URLs
 * of websites such as YouTube. The download links are read from the {@code installers/yt-dlp.json} resource, which
 * pins one release of yt-dlp and the SHA-256 hash of every file, so a download that does not match is rejected.
 *
 * <p>yt-dlp ships most platforms as one standalone executable, which is used as downloaded. 32-bit ARM Linux is only
 * shipped as a zip of the executable and the libraries it loads; a zip download is unpacked into the
 * {@code yt-dlp-unpacked} folder of the installation folder, whose top level must hold the executable under the name
 * of the zip without {@code .zip}, as {@code yt-dlp_linux_armv7l.zip} holds {@code yt-dlp_linux_armv7l}.
 *
 * <p>A platform without a build of its own falls back to a build its operating system emulates: 64-bit ARM Windows
 * and macOS can run the 64-bit x86 executable through the x64 emulation built into the operating system. The bundled
 * list has a native build for every platform it names, including 64-bit ARM Windows and the universal macOS build for
 * both Apple silicon and Intel Macs, so the fallback only matters for custom download lists.
 *
 * <p>Use {@link #shared()} inside the library, so every user of yt-dlp goes through one installer and the program is
 * downloaded at most once, even when several threads need it at the same time.
 */
public final class YTDLPInstaller extends AbstractInstaller {

  private static final String NAME = "yt-dlp";
  private static final String DOWNLOADS_RESOURCE = "yt-dlp.json";
  private static final Download[] DOWNLOADS = IOUtils.readDownloadsFromJsonResource(DOWNLOADS_RESOURCE);
  private static final String ZIP_SUFFIX = ".zip";
  private static final String UNPACKED_FOLDER = "yt-dlp-unpacked";
  private static final String UNPACKING_FOLDER = "yt-dlp-unpacking";

  YTDLPInstaller(final Path folder, final Download[] downloads) {
    super(folder, NAME, downloads);
  }

  YTDLPInstaller(final Path folder) {
    this(folder, DOWNLOADS);
  }

  YTDLPInstaller() {
    super(NAME, DOWNLOADS);
  }

  /**
   * Creates an installer that installs yt-dlp into the specified folder.
   *
   * @param folder the folder to install into
   * @return the installer
   */
  public static YTDLPInstaller create(final Path folder) {
    Preconditions.checkNotNull(folder, "Folder must not be null");
    return new YTDLPInstaller(folder);
  }

  /**
   * Creates an installer that installs yt-dlp into the cache folder of the library, {@code ~/.mcav/cache}.
   *
   * @return the installer
   */
  public static YTDLPInstaller create() {
    return new YTDLPInstaller();
  }

  /**
   * Gets the installer of the cache folder that the whole library shares. Concurrent downloads through one installer
   * are serialized, so yt-dlp is downloaded at most once.
   *
   * @return the shared installer, which installs into {@code ~/.mcav/cache}
   */
  public static YTDLPInstaller shared() {
    return SharedInstaller.INSTANCE;
  }

  /**
   * Checks whether the download of the current platform is a zip archive that has to be unpacked, rather than a
   * standalone executable.
   *
   * @return true if the file name of the download of the current platform ends in {@code .zip}, false for a
   *     standalone executable or an unsupported platform
   */
  @Override
  public boolean isArchive() {
    final String url = this.getUrl();
    final String fileName = IOUtils.getFileNameFromUrl(url);
    final String lowerCaseFileName = fileName.toLowerCase(Locale.ROOT);
    return lowerCaseFileName.endsWith(ZIP_SUFFIX);
  }

  /**
   * Gets the path of the yt-dlp executable. A standalone executable lies in the installation folder; the executable of
   * a zip lies in the {@code yt-dlp-unpacked} folder, named like the zip without {@code .zip}.
   *
   * @return the path of the yt-dlp executable
   */
  @Override
  protected Path getDefaultPath() {
    final boolean archive = this.isArchive();
    if (!archive) {
      return super.getDefaultPath();
    }
    final Path unpackedFolder = this.getUnpackedFolder();
    final String executableName = this.getArchiveExecutableName();
    return unpackedFolder.resolve(executableName);
  }

  /**
   * Installs the download. A standalone executable is used as downloaded. A zip is unpacked into a temporary folder
   * first, which then replaces the {@code yt-dlp-unpacked} folder, so a failed installation never leaves a half-unpacked
   * yt-dlp behind; the zip is deleted once it has been unpacked.
   *
   * <p>Replacing the unpacked folder is not atomic: the previous installation is deleted before the new one is moved
   * into place, so a crash in between leaves no installation at all. The next start simply downloads yt-dlp again.
   *
   * @param downloaded the downloaded executable or zip
   * @return the path of the yt-dlp executable
   * @throws IOException if the zip cannot be unpacked, or does not hold the executable at its top level
   */
  @Override
  protected Path install(final Path downloaded) throws IOException {
    final boolean archive = this.isArchive();
    if (!archive) {
      return downloaded;
    }
    final Path folder = this.getFolder();
    final Path unpacking = folder.resolve(UNPACKING_FOLDER);
    final Path unpacked = this.getUnpackedFolder();
    IOUtils.deleteRecursively(unpacking);
    try {
      this.unpack(downloaded, unpacking);
      IOUtils.deleteRecursively(unpacked);
      Files.move(unpacking, unpacked);
    } finally {
      IOUtils.deleteRecursively(unpacking);
    }
    Files.deleteIfExists(downloaded);
    return this.getDefaultPath();
  }

  private void unpack(final Path archive, final Path destination) throws IOException {
    extract(archive, destination);
    final String executableName = this.getArchiveExecutableName();
    final Path executable = destination.resolve(executableName);
    final boolean present = Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS);
    if (!present) {
      final String message = "The yt-dlp zip does not contain %s at its top level".formatted(executableName);
      throw new IOException(message);
    }
    markExecutable(executable);
  }

  /**
   * Extracts the zip. {@link IOUtils#unzip(Path, Path)} reports failures unchecked, as an {@link UncheckedIOException}
   * for an archive that cannot be read and as a {@link ZipEntryIntegrityException} for an archive that is unsafe, but an
   * installation must fail with an {@link IOException} like every other installation failure.
   */
  private static void extract(final Path archive, final Path destination) throws IOException {
    try {
      IOUtils.unzip(archive, destination);
    } catch (final UncheckedIOException | ZipEntryIntegrityException exception) {
      final String reason = exception.getMessage();
      throw new IOException("The yt-dlp zip cannot be extracted: " + reason, exception);
    }
  }

  private Path getUnpackedFolder() {
    final Path folder = this.getFolder();
    return folder.resolve(UNPACKED_FOLDER);
  }

  /**
   * Gets the name of the executable inside the zip of the current platform: the file name of the zip without
   * {@code .zip}, or the name of the program if nothing is left.
   */
  private String getArchiveExecutableName() {
    final String url = this.getUrl();
    final String fileName = IOUtils.getFileNameFromUrl(url);
    final int suffixStart = fileName.length() - ZIP_SUFFIX.length();
    final String executableName = fileName.substring(0, suffixStart);
    final boolean nameless = executableName.isEmpty();
    return nameless ? NAME : executableName;
  }

  /**
   * Gets the platforms whose yt-dlp build also runs on the specified platform. 64-bit ARM Windows and macOS run the
   * 64-bit x86 build through the x86 emulation of the operating system; every other platform has no fallback,
   * because it either has a native build or cannot emulate x86.
   *
   * @param platform the platform to find fallbacks for
   * @return the 64-bit x86 platform of the same operating system on 64-bit ARM Windows and macOS, otherwise nothing
   */
  @Override
  protected List<Platform> getFallbackPlatforms(final Platform platform) {
    final OS operatingSystem = platform.getOS();
    final Arch architecture = platform.getArch();
    final Bits bits = platform.getBits();
    final boolean arm64 = architecture == Arch.ARM && bits == Bits.BITS_64;
    if (!arm64) {
      return List.of();
    }
    if (operatingSystem == OS.WINDOWS || operatingSystem == OS.MAC) {
      final Platform x86 = Platform.ofPlatform(operatingSystem, Arch.X86, Bits.BITS_64);
      return List.of(x86);
    }
    return List.of();
  }

  /**
   * Holds the shared installer, which is only created when it is first needed.
   */
  private static final class SharedInstaller {

    private static final YTDLPInstaller INSTANCE = new YTDLPInstaller();
  }
}
