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
package me.brandonli.mcav.capability.installer;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;
import me.brandonli.mcav.utils.FileOperationException;
import me.brandonli.mcav.utils.IoUtils;
import me.brandonli.mcav.utils.http.DownloadException;
import me.brandonli.mcav.utils.http.HttpDownloader;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.OsUtils;
import me.brandonli.mcav.utils.os.Platform;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The base class of all installers.
 *
 * <p>An installer is described by a name and a list of downloads. The download for the current platform is
 * resolved lazily the first time it is needed, so an installer that has to ask a web service for its download
 * links costs nothing until it is used. The downloaded file is stored in the installer folder under
 * {@link #getFileName()}, and the resulting path is remembered in {@code config.properties} inside the installer
 * folder, so later runs find the installation without downloading it again. Downloads are verified against their
 * SHA-256 hash when one is known.
 *
 * <p>Subclasses that download archives override {@link #isArchive()} and extract the archive in
 * {@link #install(Path)}.
 *
 * <p>Downloads are serialized per installer, but a running download never blocks the other methods:
 * {@link #getPath()} reads the last installation without locking, and the download links are resolved under a lock of
 * their own. Different installer objects are not serialized with each other, so callers must coordinate
 * instances targeting the same program and folder. The installer owns no persistent thread or native resource
 * that needs closing. It keeps downloaded files and the configuration for reuse after process shutdown.
 *
 * <p>This class is thread-safe.
 *
 * @since 1.0.0
 */
public abstract class AbstractInstaller implements Installer {

  private static final Logger LOGGER = LoggerFactory.getLogger(AbstractInstaller.class);

  private static final String CONFIGURATION_FILE_NAME = "config.properties";
  private static final String CONFIGURATION_TEMPORARY_SUFFIX = ".tmp";
  private static final String CONFIGURATION_COMMENT = "Paths of programs installed by MCAV";
  private static final String WINDOWS_EXECUTABLE_SUFFIX = ".exe";
  private static final String ARCHIVE_SUFFIX = ".archive";
  private static final String CURRENT_DIRECTORY = ".";
  private static final String PARENT_DIRECTORY = "..";
  private static final Object CONFIGURATION_LOCK = new Object();

  private final Path folder;
  private final String name;
  private final Supplier<Download[]> downloadSupplier;
  // Hold this throughout installation to prevent duplicate downloads.
  private final Object installLock;
  // Lock only download resolution so installation does not block getUrl() or isSupported().
  private final Object downloadLock;

  private boolean downloadResolved;
  private @Nullable Download resolvedDownload;
  private volatile @Nullable Path installedPath;

  /**
   * Creates a new installer with lazily resolved downloads.
   *
   * @param folder    the folder the program is installed into
   * @param name      the non-null, nonblank program name, used as the file name and configuration key
   * @param downloads the non-null supplier of a non-null array of non-null downloads; called lazily for
   *                  platform resolution, whose successful result (including no match) is cached; a failure
   *                  before resolution completes can cause the supplier to be called again
   * @throws IllegalArgumentException if {@code name} is blank
   * @throws NullPointerException     if {@code folder}, {@code name} or {@code downloads} is null
   * @since 1.0.0
   */
  protected AbstractInstaller(final Path folder, final String name, final Supplier<Download[]> downloads) {
    Preconditions.checkNotNull(folder, "Folder must not be null!");
    Preconditions.checkNotNull(name, "Name must not be null!");
    Preconditions.checkNotNull(downloads, "Downloads must not be null!");
    Preconditions.checkArgument(!name.isBlank(), "Name must not be blank!");

    this.folder = folder;
    this.name = name;
    this.downloadSupplier = downloads;
    this.installLock = new Object();
    this.downloadLock = new Object();
  }

  /**
   * Creates a new installer with a fixed list of downloads.
   *
   * @param folder    the folder the program is installed into
   * @param name      the non-null, nonblank program name, used as the file name and configuration key
   * @param downloads the non-null array of non-null downloads, retained without copying; do not mutate it
   * @throws IllegalArgumentException if {@code name} is blank
   * @throws NullPointerException     if {@code folder}, {@code name} or {@code downloads} is null
   * @since 1.0.0
   */
  protected AbstractInstaller(final Path folder, final String name, final Download[] downloads) {
    Preconditions.checkNotNull(downloads, "Downloads must not be null!");

    final Supplier<Download[]> fixedDownloads = () -> downloads;
    this(folder, name, fixedDownloads);
  }

  /**
   * Creates a new installer that installs into the cache folder of the library (see
   * {@link #getDefaultExecutableFolderPath()}).
   *
   * @param name      the non-null, nonblank program name, used as the file name and configuration key
   * @param downloads the non-null array of non-null downloads, retained without copying; do not mutate it
   * @throws IllegalArgumentException if {@code name} is blank
   * @throws NullPointerException     if {@code name} or {@code downloads} is null
   * @throws FileOperationException   if neither the cache folder nor its fallback in the temporary directory can be
   *                                 created
   * @since 1.0.0
   */
  protected AbstractInstaller(final String name, final Download[] downloads) {
    final Path defaultFolder = getDefaultExecutableFolderPath();
    this(defaultFolder, name, downloads);
  }

  /**
   * Returns the folder programs are installed into by default, which is the cache folder of the library,
   * {@code ~/.mcav/cache}, or {@code mcav/cache} in the temporary directory of the JVM when that folder cannot be
   * written or cannot run programs (see {@link IoUtils#getCachedFolder()}). The folder is looked up and created on
   * every call rather than when the class is loaded, so merely loading an installer class never touches the home
   * directory.
   *
   * @return the default installation folder
   * @throws FileOperationException if neither folder can be created
   * @since 1.0.0
   */
  public static Path getDefaultExecutableFolderPath() {
    return IoUtils.getCachedFolder();
  }

  @VisibleForTesting
  static String getExecutableFileName(final String name, final Os operatingSystem) {
    if (operatingSystem == Os.WINDOWS) {
      return name + WINDOWS_EXECUTABLE_SUFFIX;
    }

    return name;
  }

  /**
   * Installs the program, or returns the existing installation if it was installed before. Concurrent calls on the
   * same installer are serialized, so the program is downloaded at most once.
   *
   * @param mode whether to mark the downloaded file as executable
   * @return the path of the installation
   * @throws InstallationException    if the platform has no download, the folder or the configuration file cannot be
   *                                  written, the file cannot be marked as executable, or the installation fails
   * @throws DownloadException        if the download fails or its hash does not match
   * @throws FileOperationException   if the downloaded file cannot be moved into place
   * @throws IllegalArgumentException if a configured download URL is not a valid URI
   * @since 1.0.0
   */
  @Override
  public Path download(final FileMode mode) {
    synchronized (this.installLock) {
      final Optional<Path> existing = this.findExistingInstallation();
      final @Nullable Path existingPath = existing.orElse(null);
      if (existingPath != null) {
        this.installedPath = existingPath;

        return existingPath;
      }

      final Path installed = this.downloadAndInstall(mode);
      this.installedPath = installed;

      return installed;
    }
  }

  private Path downloadAndInstall(final FileMode mode) {
    final Download download = this.requireDownload();
    final String fileName = this.getFileName();
    final Path destination = this.folder.resolve(fileName);
    final String url = download.getUrl();
    final URI uri = URI.create(url);
    final Optional<String> hash = download.getHash();
    final @Nullable String expectedHash = hash.orElse(null);
    this.createFolder();
    HttpDownloader.download(uri, destination, expectedHash);

    if (mode == FileMode.EXECUTABLE) {
      this.markExecutableOrDelete(destination);
    }

    final Path installed = this.install(destination);
    this.writePathToConfiguration(installed);
    return installed;
  }

  private void createFolder() {
    try {
      Files.createDirectories(this.folder);
    } catch (final IOException exception) {
      final String message = "Installation folder %s could not be created!".formatted(this.folder);
      throw new InstallationException(message, exception);
    }
  }

  private void markExecutableOrDelete(final Path destination) {
    try {
      markExecutable(destination);
    } catch (final InstallationException exception) {
      this.deleteAfterFailure(destination, exception);
      throw exception;
    }
  }

  private void deleteAfterFailure(final Path destination, final InstallationException failure) {
    try {
      Files.deleteIfExists(destination);
    } catch (final IOException exception) {
      failure.addSuppressed(exception);
    }
  }

  /**
   * Marks a downloaded or extracted file as executable. Unlike {@link IoUtils#markExecutable(Path)}, a failure is
   * reported as an {@link InstallationException}, like every other installation failure.
   *
   * @param file the file to mark
   * @throws InstallationException if the permissions of the file cannot be changed
   * @throws NullPointerException  if {@code file} is null
   * @since 1.0.0
   */
  protected static void markExecutable(final Path file) {
    Preconditions.checkNotNull(file, "File must not be null!");

    try {
      IoUtils.markExecutable(file);
    } catch (final FileOperationException exception) {
      final String reason = exception.getMessage();
      final String message = "Failed with \"%s\" while marking %s as executable!".formatted(reason, file);
      throw new InstallationException(message, exception);
    }
  }

  /**
   * Looks for an earlier installation without downloading anything. A found installation becomes the result of
   * {@link #getPath()}.
   *
   * @return the path of the existing installation, or empty if the program has not been installed
   * @throws InstallationException if the configuration file cannot be read
   * @since 1.0.0
   */
  @Override
  public Optional<Path> findInstallation() {
    final Optional<Path> existing = this.findExistingInstallation();
    final @Nullable Path existingPath = existing.orElse(null);
    if (existingPath != null) {
      this.installedPath = existingPath;
    }

    return existing;
  }

  /**
   * Turns the downloaded file into the final installation. The default implementation returns the downloaded file
   * unchanged, which is right for programs that are shipped as a single executable. Installers of archives extract
   * the archive here and return the extracted installation.
   *
   * @param downloaded the downloaded file
   * @return the path of the installation
   * @throws InstallationException if the installation fails
   * @since 1.0.0
   */
  protected Path install(final Path downloaded) {
    return downloaded;
  }

  /**
   * Returns whether a download exists for the current platform. The downloads are resolved on the first call.
   *
   * @return true if the program can be installed on this platform
   * @since 1.0.0
   */
  @Override
  public boolean isSupported() {
    final Optional<Download> download = this.resolveDownload();
    return download.isPresent();
  }

  /**
   * Returns the path of the installation: the path found or created by the last call to {@link #download(FileMode)},
   * or {@link #getDefaultPath()} before the program has been installed.
   *
   * @return the installation path
   * @since 1.0.0
   */
  @Override
  public Path getPath() {
    final Path installed = this.installedPath;
    if (installed != null) {
      return installed;
    }

    return this.getDefaultPath();
  }

  /**
   * Returns the path this installer installs the program to. The default implementation is the executable file inside
   * the installation folder, named after the program with an {@code .exe} suffix on Windows.
   *
   * @return the default installation path
   * @since 1.0.0
   */
  protected Path getDefaultPath() {
    final Os operatingSystem = OsUtils.getOs();
    final String executableName = getExecutableFileName(this.name, operatingSystem);
    return this.folder.resolve(executableName);
  }

  /**
   * Returns the URL of the download for the current platform.
   *
   * @return the download URL, or an empty string if the platform is not supported
   * @since 1.0.0
   */
  public String getUrl() {
    final Optional<Download> download = this.resolveDownload();
    final @Nullable Download resolved = download.orElse(null);
    if (resolved == null) {
      return "";
    }

    return resolved.getUrl();
  }

  /**
   * Returns the expected SHA-256 hash of the download for the current platform.
   *
   * @return the hash in hexadecimal, or empty if the platform is not supported or the download is not verified
   * @since 1.0.0
   */
  public Optional<String> getHash() {
    final Optional<Download> download = this.resolveDownload();
    return download.flatMap(Download::getHash);
  }

  /**
   * Returns the file name the download is stored under inside the installation folder. Programs are stored under
   * their name, with an {@code .exe} suffix on Windows. Archives are stored under the file name of their URL, or
   * under the name of the program with an {@code .archive} suffix if the URL does not end in a usable file name.
   *
   * @return the file name of the download
   * @since 1.0.0
   */
  protected String getFileName() {
    final boolean isArchive = this.isArchive();
    if (!isArchive) {
      final Os operatingSystem = OsUtils.getOs();
      return getExecutableFileName(this.name, operatingSystem);
    }

    final String url = this.getUrl();
    final String urlFileName = IoUtils.getFileNameFromUrl(url);
    final boolean usable = isPlainFileName(urlFileName);
    if (usable) {
      return urlFileName;
    }

    return this.name + ARCHIVE_SUFFIX;
  }

  private static boolean isPlainFileName(final String fileName) {
    final boolean empty = fileName.isEmpty();
    final boolean relativeDirectory = fileName.equals(CURRENT_DIRECTORY) || fileName.equals(PARENT_DIRECTORY);
    return !empty && !relativeDirectory;
  }

  /**
   * Returns the download for the current platform.
   *
   * @return the download
   * @throws InstallationException if the platform is not supported
   * @since 1.0.0
   */
  protected Download requireDownload() {
    final Optional<Download> download = this.resolveDownload();
    final @Nullable Download resolved = download.orElse(null);
    if (resolved == null) {
      final Platform platform = Platform.getCurrentPlatform();
      final String message = "Platform %s has no %s download available!".formatted(platform, this.name);
      throw new InstallationException(message);
    }

    return resolved;
  }

  private Optional<Download> resolveDownload() {
    synchronized (this.downloadLock) {
      if (!this.downloadResolved) {
        final Platform current = Platform.getCurrentPlatform();
        final Optional<Download> found = this.findDownload(current);
        this.resolvedDownload = found.orElse(null);
        this.downloadResolved = true;
      }

      return Optional.ofNullable(this.resolvedDownload);
    }
  }

  // Finds the download for a platform, falling back to the downloads of getFallbackPlatforms(Platform). Unknown
  // platforms, whose operating system or architecture is OTHER, never get a download.
  @VisibleForTesting
  Optional<Download> findDownload(final Platform platform) {
    final boolean known = platform.isKnown();
    if (!known) {
      return Optional.empty();
    }

    final Download[] downloads = this.downloadSupplier.get();
    final Optional<Download> exact = findDownload(downloads, platform);
    if (exact.isPresent()) {
      return exact;
    }

    final List<Platform> fallbacks = this.getFallbackPlatforms(platform);
    for (final Platform fallback : fallbacks) {
      final Optional<Download> compatible = findDownload(downloads, fallback);
      if (compatible.isPresent()) {
        return compatible;
      }
    }

    return Optional.empty();
  }

  private static Optional<Download> findDownload(final Download[] downloads, final Platform platform) {
    for (final Download download : downloads) {
      final Platform downloadPlatform = download.getPlatform();
      final boolean matches = downloadPlatform.equals(platform);
      if (matches) {
        return Optional.of(download);
      }
    }

    return Optional.empty();
  }

  /**
   * Returns the platforms whose downloads also run on the specified platform, in order of preference. The default
   * implementation returns no fallbacks. Installers of standalone executables can allow, for example, 64-bit x86
   * builds on ARM machines that emulate x86, which is not safe for native libraries loaded into the JVM.
   *
   * @param platform the platform to find fallbacks for
   * @return the compatible platforms, which may be empty
   * @since 1.0.0
   */
  protected List<Platform> getFallbackPlatforms(final Platform platform) {
    return List.of();
  }

  /**
   * Looks for an existing installation, first at {@link #getDefaultPath()} and then at the path remembered in the
   * configuration file.
   *
   * @return the existing installation, or empty if the program has to be downloaded
   * @throws InstallationException if the configuration file cannot be read
   * @since 1.0.0
   */
  protected Optional<Path> findExistingInstallation() {
    final Path defaultPath = this.getDefaultPath();
    final boolean exists = Files.exists(defaultPath);
    if (exists) {
      return Optional.of(defaultPath);
    }

    return this.findConfiguredInstallation();
  }

  private Optional<Path> findConfiguredInstallation() {
    final Properties properties = this.readConfiguration();
    final String savedPath = properties.getProperty(this.name);
    if (savedPath == null) {
      return Optional.empty();
    }

    final FileSystem fileSystem = this.folder.getFileSystem();
    try {
      final Path configuredPath = fileSystem.getPath(savedPath);
      final boolean configuredExists = Files.exists(configuredPath);
      return configuredExists ? Optional.of(configuredPath) : Optional.empty();
    } catch (final InvalidPathException exception) {
      return Optional.empty();
    }
  }

  /**
   * Remembers the installation path in the configuration file of the installation folder, so later runs find it
   * immediately. The file is replaced atomically, so a crash never leaves a half-written configuration behind.
   *
   * @param installedPath the path of the installation
   * @throws InstallationException if the configuration file cannot be read or written
   * @throws NullPointerException  if {@code installedPath} is null
   * @since 1.0.0
   */
  public void writePathToConfiguration(final Path installedPath) {
    Preconditions.checkNotNull(installedPath, "Installed path must not be null!");

    synchronized (CONFIGURATION_LOCK) {
      final Properties properties = this.readConfiguration();
      final Path absolute = installedPath.toAbsolutePath();
      final String raw = absolute.toString();
      properties.setProperty(this.name, raw);
      this.storeConfiguration(properties);
    }
  }

  private void storeConfiguration(final Properties properties) {
    final Path configurationFile = this.getConfigurationFile();
    try {
      this.writeConfiguration(properties, configurationFile);
    } catch (final IOException | FileOperationException exception) {
      final String message = "Configuration %s could not be written!".formatted(configurationFile);
      throw new InstallationException(message, exception);
    }
  }

  private void writeConfiguration(final Properties properties, final Path configurationFile) throws IOException {
    Files.createDirectories(this.folder);
    final Path temporary = Files.createTempFile(this.folder, CONFIGURATION_FILE_NAME, CONFIGURATION_TEMPORARY_SUFFIX);
    try {
      try (final OutputStream configurationOutput = Files.newOutputStream(temporary)) {
        properties.store(configurationOutput, CONFIGURATION_COMMENT);
      }

      IoUtils.moveReplacing(temporary, configurationFile);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private Properties readConfiguration() {
    final Properties properties = new Properties();
    final Path configurationFile = this.getConfigurationFile();
    final boolean exists = Files.isRegularFile(configurationFile);
    if (!exists) {
      return properties;
    }

    try (final InputStream configurationInput = Files.newInputStream(configurationFile)) {
      properties.load(configurationInput);
    } catch (final IllegalArgumentException exception) {
      // Properties refuses a malformed unicode escape this way; only remembered paths are lost, so they are forgotten
      LOGGER.warn("Configuration {} is malformed and is ignored.", configurationFile, exception);
      return new Properties();
    } catch (final IOException exception) {
      final String message = "Configuration %s could not be read!".formatted(configurationFile);
      throw new InstallationException(message, exception);
    }

    return properties;
  }

  /**
   * Returns the name of the program, which is also the key of the program in the configuration file.
   *
   * @return the name
   * @since 1.0.0
   */
  public String getName() {
    return this.name;
  }

  /**
   * Returns the folder the program is installed into.
   *
   * @return the installation folder
   * @since 1.0.0
   */
  public Path getFolder() {
    return this.folder;
  }

  /**
   * Returns whether the download is an archive that has to be extracted, rather than a program that can be run
   * directly. Archives are stored under their original file name and are never renamed to {@code .exe}.
   *
   * @return true if the download is an archive
   * @since 1.0.0
   */
  public boolean isArchive() {
    return false;
  }

  private Path getConfigurationFile() {
    return this.folder.resolve(CONFIGURATION_FILE_NAME);
  }
}
