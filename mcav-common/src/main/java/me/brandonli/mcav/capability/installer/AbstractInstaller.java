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
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;
import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.http.HttpDownloader;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.OSUtils;
import me.brandonli.mcav.utils.os.Platform;
import org.checkerframework.checker.nullness.qual.Nullable;

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
 * their own.
 */
public abstract class AbstractInstaller implements Installer {

  private static final String CONFIG_FILE_NAME = "config.properties";
  private static final String CONFIG_TEMPORARY_SUFFIX = ".tmp";
  private static final String CONFIG_COMMENT = "Paths of programs installed by MCAV";
  private static final String WINDOWS_EXECUTABLE_SUFFIX = ".exe";
  private static final String ARCHIVE_SUFFIX = ".archive";
  private static final Object CONFIG_LOCK = new Object();

  private final Path folder;
  private final String name;
  private final Supplier<Download[]> downloadSupplier;
  // held for the whole installation, so the program is downloaded at most once
  private final Object installLock;
  // held only while the downloads are resolved, so a running installation never blocks getUrl() or isSupported()
  private final Object downloadLock;

  private boolean downloadResolved;
  private @Nullable Download resolvedDownload;
  private volatile @Nullable Path installedPath;

  /**
   * Constructs a new installer with lazily resolved downloads.
   *
   * @param folder    the folder the program is installed into
   * @param name      the name of the program, which is used as the file name and as the key in the configuration
   * @param downloads supplies the downloads of the program, called at most once
   */
  protected AbstractInstaller(final Path folder, final String name, final Supplier<Download[]> downloads) {
    Preconditions.checkNotNull(folder, "Folder must not be null");
    Preconditions.checkNotNull(name, "Name must not be null");
    Preconditions.checkNotNull(downloads, "Downloads must not be null");
    Preconditions.checkArgument(!name.isBlank(), "Name must not be blank");
    this.folder = folder;
    this.name = name;
    this.downloadSupplier = downloads;
    this.installLock = new Object();
    this.downloadLock = new Object();
  }

  /**
   * Constructs a new installer with a fixed list of downloads.
   *
   * @param folder    the folder the program is installed into
   * @param name      the name of the program, which is used as the file name and as the key in the configuration
   * @param downloads the downloads of the program
   */
  protected AbstractInstaller(final Path folder, final String name, final Download[] downloads) {
    Preconditions.checkNotNull(downloads, "Downloads must not be null");
    final Supplier<Download[]> fixedDownloads = () -> downloads;
    this(folder, name, fixedDownloads);
  }

  /**
   * Constructs a new installer that installs into the cache folder of the library.
   *
   * @param name      the name of the program, which is used as the file name and as the key in the configuration
   * @param downloads the downloads of the program
   */
  protected AbstractInstaller(final String name, final Download[] downloads) {
    final Path defaultFolder = getDefaultExecutableFolderPath();
    this(defaultFolder, name, downloads);
  }

  /**
   * Gets the folder programs are installed into by default, which is the cache folder of the library,
   * {@code ~/.mcav/cache}. The folder is looked up and created on every call rather than when the class is loaded,
   * so merely loading an installer class never touches the home directory.
   *
   * @return the default installation folder
   * @throws UncheckedIOException if the folder cannot be created
   */
  public static Path getDefaultExecutableFolderPath() {
    return IOUtils.getCachedFolder();
  }

  /**
   * Gets the file name of an executable on an operating system, which carries the {@code .exe} suffix on Windows.
   *
   * @param name            the name of the program
   * @param operatingSystem the operating system
   * @return the file name
   */
  @VisibleForTesting
  static String getExecutableFileName(final String name, final OS operatingSystem) {
    if (operatingSystem == OS.WINDOWS) {
      return name + WINDOWS_EXECUTABLE_SUFFIX;
    }
    return name;
  }

  /**
   * Installs the program, or returns the existing installation if it was installed before. Concurrent calls on the
   * same installer are serialized, so the program is downloaded at most once.
   *
   * @param executable true to mark the downloaded file as executable
   * @return the path of the installation
   * @throws IOException if the download fails, the file cannot be written or marked as executable, or the hash does
   *                     not match
   */
  @Override
  public Path download(final boolean executable) throws IOException {
    synchronized (this.installLock) {
      final Optional<Path> existing = this.findExistingInstallation();
      if (existing.isPresent()) {
        final Path existingPath = existing.get();
        this.installedPath = existingPath;
        return existingPath;
      }
      final Path installed = this.downloadAndInstall(executable);
      this.installedPath = installed;
      return installed;
    }
  }

  private Path downloadAndInstall(final boolean executable) throws IOException {
    final Download download = this.requireDownload();
    final String fileName = this.getFileName();
    final Path destination = this.folder.resolve(fileName);
    final String url = download.getUrl();
    final URI uri = URI.create(url);
    final String hash = download.getHash();
    Files.createDirectories(this.folder);
    HttpDownloader.download(uri, destination, hash);
    if (executable) {
      markExecutable(destination);
    }
    final Path installed = this.install(destination);
    this.writePathToConfig(installed);
    return installed;
  }

  /**
   * Marks a downloaded or extracted file as executable. {@link IOUtils#markExecutable(Path)} reports failures
   * unchecked, but a failed installation must be reported as an {@link IOException}, like every other installation
   * failure.
   *
   * @param file the file to mark
   * @throws IOException if the permissions of the file cannot be changed
   */
  protected static void markExecutable(final Path file) throws IOException {
    Preconditions.checkNotNull(file, "File must not be null");
    try {
      IOUtils.markExecutable(file);
    } catch (final UncheckedIOException exception) {
      final String reason = exception.getMessage();
      throw new IOException("Cannot mark " + file + " as executable: " + reason, exception);
    }
  }

  /**
   * Looks for an earlier installation without downloading anything. A found installation becomes the result of
   * {@link #getPath()}.
   *
   * @return the path of the existing installation, or empty if the program has not been installed
   * @throws IOException if the installation folder or the configuration file cannot be read
   */
  @Override
  public Optional<Path> findInstallation() throws IOException {
    final Optional<Path> existing = this.findExistingInstallation();
    if (existing.isPresent()) {
      final Path existingPath = existing.get();
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
   * @throws IOException if the installation fails
   */
  protected Path install(final Path downloaded) throws IOException {
    return downloaded;
  }

  /**
   * Checks whether a download exists for the current platform. The downloads are resolved on the first call.
   *
   * @return true if the program can be installed on this platform
   */
  @Override
  public boolean isSupported() {
    final Optional<Download> download = this.resolveDownload();
    return download.isPresent();
  }

  /**
   * Gets the path of the installation: the path found or created by the last call to {@link #download(boolean)},
   * or {@link #getDefaultPath()} before the program has been installed.
   *
   * @return the installation path
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
   * Gets the path this installer installs the program to. The default implementation is the executable file inside
   * the installation folder, named after the program with an {@code .exe} suffix on Windows.
   *
   * @return the default installation path
   */
  protected Path getDefaultPath() {
    final OS operatingSystem = OSUtils.getOS();
    final String executableName = getExecutableFileName(this.name, operatingSystem);
    return this.folder.resolve(executableName);
  }

  /**
   * Gets the name of the program, which is also the key of the program in the configuration file.
   *
   * @return the name
   */
  public String getName() {
    return this.name;
  }

  /**
   * Gets the folder the program is installed into.
   *
   * @return the installation folder
   */
  public Path getFolder() {
    return this.folder;
  }

  /**
   * Gets the URL of the download for the current platform.
   *
   * @return the download URL, or an empty string if the platform is not supported
   */
  public String getUrl() {
    final Optional<Download> download = this.resolveDownload();
    if (download.isEmpty()) {
      return "";
    }
    final Download resolved = download.get();
    return resolved.getUrl();
  }

  /**
   * Gets the expected SHA-256 hash of the download for the current platform.
   *
   * @return the hash in hexadecimal, or null if the platform is not supported or the download is not verified
   */
  public @Nullable String getHash() {
    final Optional<Download> download = this.resolveDownload();
    if (download.isEmpty()) {
      return null;
    }
    final Download resolved = download.get();
    return resolved.getHash();
  }

  /**
   * Checks whether the download is an archive that has to be extracted, rather than a program that can be run
   * directly. Archives are stored under their original file name and are never renamed to {@code .exe}.
   *
   * @return true if the download is an archive
   */
  public boolean isArchive() {
    return false;
  }

  /**
   * Gets the file name the download is stored under inside the installation folder. Programs are stored under
   * their name, with an {@code .exe} suffix on Windows. Archives are stored under the file name of their URL, or
   * under the name of the program with an {@code .archive} suffix if the URL does not end in a usable file name.
   *
   * @return the file name of the download
   */
  protected String getFileName() {
    final boolean archive = this.isArchive();
    if (!archive) {
      final OS operatingSystem = OSUtils.getOS();
      return getExecutableFileName(this.name, operatingSystem);
    }
    final String url = this.getUrl();
    final String urlFileName = IOUtils.getFileNameFromUrl(url);
    final boolean usable = isPlainFileName(urlFileName);
    if (usable) {
      return urlFileName;
    }
    return this.name + ARCHIVE_SUFFIX;
  }

  private static boolean isPlainFileName(final String fileName) {
    final boolean empty = fileName.isEmpty();
    final boolean relativeDirectory = fileName.equals(".") || fileName.equals("..");
    return !empty && !relativeDirectory;
  }

  /**
   * Gets the download for the current platform.
   *
   * @return the download
   * @throws IOException if the platform is not supported
   */
  protected Download requireDownload() throws IOException {
    final Optional<Download> download = this.resolveDownload();
    if (download.isEmpty()) {
      final Platform platform = Platform.getCurrentPlatform();
      final String message = "No %s download is available for %s".formatted(this.name, platform);
      throw new IOException(message);
    }
    return download.get();
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

  /**
   * Finds the download for a platform, falling back to the downloads of {@link #getFallbackPlatforms(Platform)}.
   * Unknown platforms, whose operating system or architecture is {@code OTHER}, never get a download.
   *
   * @param platform the platform to find the download for
   * @return the download, or empty if neither the platform nor any of its fallbacks has one
   */
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
   * Gets the platforms whose downloads also run on the specified platform, in order of preference. The default
   * implementation returns no fallbacks. Installers of standalone executables can allow, for example, 64-bit x86
   * builds on ARM machines that emulate x86, which is not safe for native libraries loaded into the JVM.
   *
   * @param platform the platform to find fallbacks for
   * @return the compatible platforms, which may be empty
   */
  protected List<Platform> getFallbackPlatforms(final Platform platform) {
    return List.of();
  }

  /**
   * Looks for an existing installation, first at {@link #getDefaultPath()} and then at the path remembered in the
   * configuration file.
   *
   * @return the existing installation, or empty if the program has to be downloaded
   * @throws IOException if the configuration file cannot be read
   */
  protected Optional<Path> findExistingInstallation() throws IOException {
    final Path defaultPath = this.getDefaultPath();
    final boolean exists = Files.exists(defaultPath);
    if (exists) {
      return Optional.of(defaultPath);
    }
    return this.findConfiguredInstallation();
  }

  private Optional<Path> findConfiguredInstallation() throws IOException {
    final Properties properties = this.readConfig();
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
   * @throws IOException if the configuration file cannot be written
   */
  public void writePathToConfig(final Path installedPath) throws IOException {
    Preconditions.checkNotNull(installedPath, "Path must not be null");
    synchronized (CONFIG_LOCK) {
      final Properties properties = this.readConfig();
      final Path absolute = installedPath.toAbsolutePath();
      final String raw = absolute.toString();
      properties.setProperty(this.name, raw);
      this.storeConfig(properties);
    }
  }

  private void storeConfig(final Properties properties) throws IOException {
    Files.createDirectories(this.folder);
    final Path configFile = this.getConfigFile();
    final Path temporary = Files.createTempFile(this.folder, CONFIG_FILE_NAME, CONFIG_TEMPORARY_SUFFIX);
    try {
      try (final OutputStream configOutput = Files.newOutputStream(temporary)) {
        properties.store(configOutput, CONFIG_COMMENT);
      }
      IOUtils.moveReplacing(temporary, configFile);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private Properties readConfig() throws IOException {
    final Properties properties = new Properties();
    final Path configFile = this.getConfigFile();
    final boolean exists = Files.isRegularFile(configFile);
    if (!exists) {
      return properties;
    }
    try (final InputStream configInput = Files.newInputStream(configFile)) {
      properties.load(configInput);
    }
    return properties;
  }

  private Path getConfigFile() {
    return this.folder.resolve(CONFIG_FILE_NAME);
  }
}
