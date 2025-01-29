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
package me.brandonli.mcav.installer;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Properties;
import java.util.function.Consumer;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

public class MCAVInstaller {

  private static final String REPO_BASE_URL = "https://repo.brandonli.me/snapshots";
  private static final String GROUP_ID = "me.brandonli";
  private static final String ARTIFACT_ID = "mcav-minecraft-all";
  private static final String VERSION = "1.0.0-SNAPSHOT";
  private static final String METADATA_FILE = "mcav-metadata.properties";
  private static final int CONNECTION_TIMEOUT = 20000;
  private static final int READ_TIMEOUT = 60000;
  private static final int BUFFER_SIZE = 65536;

  private final Path folder;
  private final URLClassLoaderInjector urlClassLoaderAccess;

  MCAVInstaller(final Path folder, final ClassLoader classLoader) {
    this.folder = folder;
    this.urlClassLoaderAccess = URLClassLoaderInjector.create((URLClassLoader) classLoader);
  }

  public static MCAVInstaller urlClassLoaderInjector(final Path folder, final ClassLoader classLoader) {
    return new MCAVInstaller(folder, classLoader);
  }

  public void loadMCAVDependencies(final Consumer<String> progressLogger) {
    try {
      progressLogger.accept("Fetching maven metadata...");
      final String metadataUrl = this.buildMetadataUrl();
      final Document metadataDoc = this.fetchMetadataDocument(metadataUrl);
      final String timestamp = this.getXmlNodeContent(metadataDoc, "timestamp");
      final String buildNumber = this.getXmlNodeContent(metadataDoc, "buildNumber");
      final String jarName = this.buildJarName(timestamp, buildNumber);
      final String jarUrl = this.buildJarUrl(jarName);
      final Path jarPath = this.prepareJarPath(jarName);
      if (this.isJarInstalled(jarPath, timestamp, buildNumber)) {
        progressLogger.accept("Using existing jar: " + jarName);
      } else {
        progressLogger.accept("Downloading " + jarName + " from repository...");
        this.downloadFile(jarUrl, jarPath, progressLogger);
        progressLogger.accept("Successfully downloaded " + jarName);
        final String fileHash = this.calculateFileHash(jarPath);
        this.storeMetadata(jarPath, timestamp, buildNumber, fileHash);
        progressLogger.accept("Verified JAR integrity (SHA-256: " + fileHash.substring(0, 10) + "...)");
      }
      this.loadJar(jarPath);
    } catch (final Exception e) {
      throw new AssertionError(e);
    }
  }

  private boolean isJarInstalled(final Path jarPath, final String timestamp, final String buildNumber) throws IOException {
    if (!Files.exists(jarPath)) {
      return false;
    }
    final Path metadataPath = this.getMetadataPath();
    if (!Files.exists(metadataPath)) {
      return false;
    }
    final Properties metadata = new Properties();
    try (final InputStream in = Files.newInputStream(metadataPath)) {
      metadata.load(in);
    }
    final Path name = requireNonNull(jarPath.getFileName());
    final String jarFileName = name.toString();
    final String storedTimestamp = metadata.getProperty(jarFileName + ".timestamp");
    final String storedBuildNumber = metadata.getProperty(jarFileName + ".buildNumber");
    final String storedHash = metadata.getProperty(jarFileName + ".hash");
    if (storedTimestamp == null || storedBuildNumber == null || storedHash == null) {
      return false;
    }
    if (!storedTimestamp.equals(timestamp) || !storedBuildNumber.equals(buildNumber)) {
      return false;
    }
    final String currentHash = this.calculateFileHash(jarPath);
    return currentHash.equals(storedHash);
  }

  private String calculateFileHash(final Path file) throws IOException {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      final byte[] buffer = new byte[BUFFER_SIZE];
      int bytesRead;
      try (final InputStream fis = Files.newInputStream(file)) {
        while ((bytesRead = fis.read(buffer)) != -1) {
          digest.update(buffer, 0, bytesRead);
        }
      }
      final byte[] hashBytes = digest.digest();
      final StringBuilder hexString = new StringBuilder();
      for (final byte hashByte : hashBytes) {
        final String hex = Integer.toHexString(0xff & hashByte);
        if (hex.length() == 1) {
          hexString.append('0');
        }
        hexString.append(hex);
      }
      return hexString.toString();
    } catch (final NoSuchAlgorithmException e) {
      throw new IOException("Failed to calculate file hash", e);
    }
  }

  private void storeMetadata(final Path jarPath, final String timestamp, final String buildNumber, final String hash) throws IOException {
    final Path metadataPath = this.getMetadataPath();
    final Properties metadata = new Properties();
    if (Files.exists(metadataPath)) {
      try (final InputStream in = Files.newInputStream(metadataPath)) {
        metadata.load(in);
      }
    }
    final Path name = requireNonNull(jarPath.getFileName());
    final String jarFileName = name.toString();
    metadata.setProperty(jarFileName + ".timestamp", timestamp);
    metadata.setProperty(jarFileName + ".buildNumber", buildNumber);
    metadata.setProperty(jarFileName + ".hash", hash);
    metadata.setProperty(jarFileName + ".installedAt", String.valueOf(System.currentTimeMillis()));
    try (final OutputStream out = Files.newOutputStream(metadataPath)) {
      metadata.store(out, "MCAV Installer Metadata");
    }
  }

  private Path getMetadataPath() throws IOException {
    final Path dependencyDir = this.folder.resolve("dependencies");
    Files.createDirectories(dependencyDir);
    return dependencyDir.resolve(METADATA_FILE);
  }

  private String buildMetadataUrl() {
    final String groupPath = GROUP_ID.replace(".", "/");
    return String.format("%s/%s/%s/%s/maven-metadata.xml", REPO_BASE_URL, groupPath, ARTIFACT_ID, VERSION);
  }

  private Document fetchMetadataDocument(final String url) throws Exception {
    final HttpURLConnection connection = this.createConnection(url);
    connection.setRequestMethod("GET");
    try (final InputStream inputStream = connection.getInputStream()) {
      final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      final DocumentBuilder builder = factory.newDocumentBuilder();
      final Document document = builder.parse(inputStream);
      document.getDocumentElement().normalize();
      return document;
    } finally {
      connection.disconnect();
    }
  }

  private String getXmlNodeContent(final Document document, final String tagName) {
    final NodeList nodes = requireNonNull(document.getElementsByTagName(tagName));
    if (nodes.getLength() > 0) {
      return requireNonNull(requireNonNull(nodes.item(0)).getTextContent());
    }
    throw new IllegalArgumentException("Tag " + tagName + " not found in metadata");
  }

  private String buildJarName(final String timestamp, final String buildNumber) {
    final String versionWithTimestamp = VERSION.replace("SNAPSHOT", timestamp + "-" + buildNumber);
    return ARTIFACT_ID + "-" + versionWithTimestamp + "-all.jar";
  }

  private String buildJarUrl(final String jarName) {
    final String groupPath = GROUP_ID.replace(".", "/");
    return REPO_BASE_URL + "/" + groupPath + "/" + ARTIFACT_ID + "/" + VERSION + "/" + jarName;
  }

  private Path prepareJarPath(final String jarName) throws IOException {
    final Path dependencyDir = this.folder.resolve("dependencies");
    Files.createDirectories(dependencyDir);
    return dependencyDir.resolve(jarName);
  }

  private void downloadFile(final String fileUrl, final Path destination, final Consumer<String> progressLogger) throws IOException {
    try {
      final long fileSize = this.getFileSize(fileUrl);
      if (fileSize <= 0) {
        progressLogger.accept("Content length unknown, downloading...");
      } else {
        progressLogger.accept("File size: " + this.formatSize(fileSize));
      }
      final Path parent = requireNonNull(destination.getParent());
      Files.createDirectories(parent);
      final HttpURLConnection connection = this.createConnection(fileUrl);
      connection.setRequestMethod("GET");
      final long startTime = System.currentTimeMillis();
      try (final InputStream in = connection.getInputStream()) {
        if (fileSize > 0) {
          this.downloadWithProgress(in, destination, fileSize, startTime, progressLogger);
        } else {
          this.downloadWithoutProgress(in, destination, startTime, progressLogger);
        }
      } finally {
        connection.disconnect();
      }
    } catch (final Exception e) {
      throw new IOException("Download failed: " + e.getMessage(), e);
    }
  }

  private long getFileSize(final String fileUrl) throws IOException {
    final HttpURLConnection connection = this.createConnection(fileUrl);
    connection.setRequestMethod("HEAD");
    try {
      return connection.getContentLengthLong();
    } finally {
      connection.disconnect();
    }
  }

  private HttpURLConnection createConnection(final String url) throws IOException {
    final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    connection.setConnectTimeout(CONNECTION_TIMEOUT);
    connection.setReadTimeout(READ_TIMEOUT);
    connection.setInstanceFollowRedirects(true);
    return connection;
  }

  private void downloadWithProgress(
    final InputStream in,
    final Path destination,
    final long fileSize,
    final long startTime,
    final Consumer<String> progressLogger
  ) throws IOException {
    final byte[] buffer = new byte[BUFFER_SIZE];
    long totalBytesRead = 0;
    long lastUpdateTime = System.currentTimeMillis();
    int lastPercent = 0;
    try (final OutputStream out = Files.newOutputStream(destination)) {
      int bytesRead;
      while ((bytesRead = in.read(buffer)) != -1) {
        out.write(buffer, 0, bytesRead);
        totalBytesRead += bytesRead;
        final int currentPercent = (int) ((totalBytesRead * 100) / fileSize);
        final long currentTime = System.currentTimeMillis();
        if (currentPercent - lastPercent >= 5 || currentTime - lastUpdateTime >= 5_000) {
          final double speed = this.calculateSpeed(totalBytesRead, currentTime, startTime);
          final String progressMessage = this.formatProgressMessage(currentPercent, totalBytesRead, fileSize, speed);
          progressLogger.accept(progressMessage);
          lastUpdateTime = currentTime;
          lastPercent = currentPercent;
        }
      }
    }
    final long timeElapsed = System.currentTimeMillis() - startTime;
    final double averageSpeed = this.calculateSpeed(totalBytesRead, System.currentTimeMillis(), startTime);
    final String completionMessage = this.formatCompletionMessage(timeElapsed, averageSpeed);
    progressLogger.accept(completionMessage);
  }

  private boolean shouldUpdateProgress(
    final long totalBytesRead,
    final long lastProgressUpdate,
    final long fileSize,
    final long currentTime,
    final long startTime
  ) {
    return (int) ((totalBytesRead * 100) / fileSize) > (int) ((lastProgressUpdate * 100) / fileSize) || (currentTime - startTime > 500);
  }

  private void downloadWithoutProgress(
    final InputStream in,
    final Path destination,
    final long startTime,
    final Consumer<String> progressLogger
  ) throws IOException {
    Files.copy(in, destination);
    final long timeElapsed = System.currentTimeMillis() - startTime;
    final String message = String.format("Download complete in %.1f seconds", timeElapsed / 1000.0);
    progressLogger.accept(message);
  }

  private double calculateSpeed(final long bytesRead, final long currentTime, final long startTime) {
    final long elapsedMillis = currentTime - startTime;
    if (elapsedMillis == 0) {
      return 0;
    }
    return ((double) bytesRead / 1024 / 1024) / (elapsedMillis / 1000.0);
  }

  private String formatProgressMessage(final int percent, final long bytesRead, final long fileSize, final double speed) {
    return String.format("Downloaded %d%% (%s/%s) - %.2f MB/s", percent, this.formatSize(bytesRead), this.formatSize(fileSize), speed);
  }

  private String formatCompletionMessage(final long timeElapsed, final double speed) {
    return String.format("Download complete in %.1f seconds (%.2f MB/s)", timeElapsed / 1000.0, speed);
  }

  private String formatSize(final long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    }
    final int exp = (int) (Math.log(bytes) / Math.log(1024));
    final String pre = "KMGTPE".charAt(exp - 1) + "";
    return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
  }

  private void loadJar(final Path path) {
    try {
      final URL jarUrl = path.toUri().toURL();
      this.urlClassLoaderAccess.addURL(jarUrl);
    } catch (final MalformedURLException e) {
      throw new AssertionError(e);
    }
  }
}
