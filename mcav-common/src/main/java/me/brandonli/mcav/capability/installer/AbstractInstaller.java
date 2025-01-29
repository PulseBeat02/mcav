/*
 * This file is part of mcav, a media playback library for Minecraft
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

import java.io.*;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.Properties;
import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.OSUtils;
import me.brandonli.mcav.utils.os.Platform;
import org.checkerframework.checker.initialization.qual.UnderInitialization;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Base installer of all installers.
 */
public abstract class AbstractInstaller implements Installer {

  private static final Path FOLDER_PATH = IOUtils.getCachedFolder();
  private static final Path CONFIG_FILE = FOLDER_PATH.resolve("config.properties");

  public static Path getDefaultExecutableFolderPath() {
    return FOLDER_PATH;
  }

  private final String name;
  private final String url;
  private final @Nullable String hash;
  private final boolean supported;

  private Path path;

  /**
   * Creates a new BaseInstaller.
   *
   * @param folder    the folder to target
   * @param name      the name
   * @param downloads installation links
   */
  public AbstractInstaller(final Path folder, final String name, final Download[] downloads) {
    this.path = folder.resolve(name);
    this.name = name;

    final Optional<Download> optional = this.getDownload(downloads);
    if (optional.isPresent()) {
      final Download download = optional.get();
      this.url = download.getUrl();
      this.hash = download.getHash();
    } else {
      this.url = "";
      this.hash = null;
    }
    this.supported = !this.url.isEmpty();
  }

  /**
   * Creates a new BaseInstaller.
   *
   * @param name      the name
   * @param downloads installation links
   */
  public AbstractInstaller(final String name, final Download[] downloads) {
    this(FOLDER_PATH, name, downloads);
  }

  private Optional<Download> getDownload(@UnderInitialization AbstractInstaller this, final Download[] downloads) {
    final Platform current = Platform.getCurrentPlatform();
    return Arrays.stream(downloads).filter(download -> download.getPlatform().equals(current)).findFirst();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Path download(final boolean chmod) throws IOException {
    final Optional<Path> existingFile = this.checkExistingFile();
    if (existingFile.isPresent()) {
      this.path = existingFile.get();
      return this.path;
    }

    this.downloadFile();
    this.changePermissions(chmod);

    return this.path;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isSupported() {
    return this.supported;
  }

  private void writePathToConfig(final Path path) throws IOException {
    Files.createDirectories(FOLDER_PATH);
    final Properties props = new Properties();
    if (Files.exists(CONFIG_FILE)) {
      try (final InputStream in = Files.newInputStream(CONFIG_FILE)) {
        props.load(in);
      }
    }

    final Path absolute = path.toAbsolutePath();
    final String raw = absolute.toString();
    props.setProperty(this.name, raw);
    try (final OutputStream out = Files.newOutputStream(CONFIG_FILE)) {
      props.store(out, "MCAV installer paths");
    }
  }

  private void changePermissions(final boolean chmod) throws IOException {
    if (chmod) {
      this.changePermissions();
    }
  }

  private void downloadFile() throws IOException {
    final URL url = new URL(this.url);
    final File output = this.path.toFile();
    if (this.supported) {
      IOUtils.createFileIfNotExists(this.path);
    }

    int tries = 0;
    while (tries <= 3) {
      if (tries == 3) {
        throw new me.brandonli.mcav.utils.UncheckedIOException("File hash verification failed!");
      }
      try (
        final InputStream inputStream = url.openStream();
        final ReadableByteChannel readableByteChannel = Channels.newChannel(inputStream);
        final FileOutputStream stream = new FileOutputStream(output);
        final FileChannel channel = stream.getChannel()
      ) {
        channel.transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
      }
      if (this.hash != null && !this.hash.isEmpty()) {
        final String calculatedHash = IOUtils.getSHA256Hash(this.path);
        if (!calculatedHash.equalsIgnoreCase(this.hash)) {
          Files.delete(this.path);
          tries++;
          continue;
        }
      }
      break;
    }

    if (OSUtils.getOS() == OS.WINDOWS && !this.isFolder()) {
      final String raw = this.path.toString();
      final String name = String.format("%s.exe", raw);
      final Path newPath = Path.of(name);
      Files.move(this.path, newPath);
      this.path = newPath;
    }

    this.writePathToConfig(this.path);
  }

  /**
   * Checks if the current context or object represents a folder.
   *
   * @return true if it is a folder, otherwise false
   */
  public boolean isFolder() {
    return false;
  }

  private Optional<Path> checkExistingFile() throws IOException {
    if (Files.exists(this.path)) {
      return Optional.of(this.path);
    }

    if (Files.notExists(CONFIG_FILE)) {
      return Optional.empty();
    }

    final Properties props = new Properties();
    try (final InputStream in = Files.newInputStream(CONFIG_FILE)) {
      props.load(in);
    }

    final String savedPath = props.getProperty(this.name);
    if (savedPath != null) {
      final Path configPath = Path.of(savedPath);
      if (Files.exists(configPath)) {
        return Optional.of(configPath);
      }
    }

    return Optional.empty();
  }

  private void changePermissions() throws IOException {
    final OS os = OSUtils.getOS();
    if (os != OS.WINDOWS) {
      final String raw = this.path.toString();
      final ProcessBuilder builder = new ProcessBuilder("chmod", "777", raw);
      this.startProcess(builder);
    }
  }

  private void startProcess(final ProcessBuilder builder) throws IOException {
    try {
      final Process process = builder.start();
      process.waitFor();
    } catch (final InterruptedException e) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      throw new me.brandonli.mcav.utils.UncheckedIOException(e.getMessage(), e);
    }
  }

  /**
   * Returns the download URL.
   *
   * @return the download URL
   */
  public String getUrl() {
    return this.url;
  }

  /**
   * Returns the expected hash of the file.
   *
   * @return the expected hash, or null if no hash verification is required
   */
  public @Nullable String getHash() {
    return this.hash;
  }

  /**
   * Returns the Path of the binary
   *
   * @return the Path of the binary
   */
  public Path getPath() {
    return this.path;
  }
}
