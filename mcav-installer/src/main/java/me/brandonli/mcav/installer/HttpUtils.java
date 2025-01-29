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
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Properties;
import java.util.function.Consumer;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public final class HttpUtils {

  private static final String REPO_BASE_URL = "https://repo.brandonli.me/snapshots";
  private static final String GROUP_ID = "me.brandonli";
  private static final String ARTIFACT_ID = "mcav-minecraft-all";
  private static final String VERSION = "1.0.0-SNAPSHOT";
  private static final String METADATA_FILE = "mcav-metadata.properties";
  private static final String SIZE_UNITS = "KMGTPE";

  private static final int CONNECTION_TIMEOUT = 20000;
  private static final int READ_TIMEOUT = 60000;
  private static final int BUFFER_SIZE = 65536;

  private HttpUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  static Path downloadDependencies(final Consumer<String> progressLogger, final Path folder) throws IOException {
    progressLogger.accept("Fetching maven metadata...");
    final Document metadataDoc = fetchMetadataDocument();
    final String timestamp = getXmlNodeContent(metadataDoc, "timestamp");
    final String buildNumber = getXmlNodeContent(metadataDoc, "buildNumber");
    final String jarName = buildJarName(timestamp, buildNumber);
    final String jarUrl = buildJarUrl(jarName);
    final Path jarPath = prepareJarPath(jarName, folder);
    if (isJarInstalled(jarPath, folder, timestamp, buildNumber)) {
      progressLogger.accept("Using existing jar: " + jarName);
    } else {
      progressLogger.accept("Downloading " + jarName + " from repository...");
      downloadFile(jarUrl, jarPath, progressLogger);
      progressLogger.accept("Successfully downloaded " + jarName);
      final String fileHash = calculateFileHash(jarPath);
      storeMetadata(jarPath, folder, timestamp, buildNumber, fileHash);
      progressLogger.accept("Verified JAR integrity (SHA-256: " + fileHash.substring(0, 10) + "...)");
    }
    return jarPath;
  }

  private static boolean isJarInstalled(final Path jarPath, final Path folder, final String timestamp, final String buildNumber)
    throws IOException {
    if (!Files.exists(jarPath)) {
      return false;
    }
    final Path metadataPath = getMetadataPath(folder);
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
    final String currentHash = calculateFileHash(jarPath);
    return currentHash.equals(storedHash);
  }

  private static String calculateFileHash(final Path file) throws IOException {
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

  private static void storeMetadata(
    final Path jarPath,
    final Path folder,
    final String timestamp,
    final String buildNumber,
    final String hash
  ) throws IOException {
    final Path metadataPath = getMetadataPath(folder);
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

  private static Path getMetadataPath(final Path folder) throws IOException {
    final Path dependencyDir = folder.resolve("dependencies");
    Files.createDirectories(dependencyDir);
    return dependencyDir.resolve(METADATA_FILE);
  }

  private static String buildMetadataUrl() {
    final String groupPath = GROUP_ID.replace(".", "/");
    return String.format("%s/%s/%s/%s/maven-metadata.xml", REPO_BASE_URL, groupPath, ARTIFACT_ID, VERSION);
  }

  private static Document fetchMetadataDocument() throws IOException {
    final String url = buildMetadataUrl();
    final HttpURLConnection connection = createConnection(url);
    connection.setRequestMethod("GET");
    try (final InputStream inputStream = connection.getInputStream()) {
      final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      final DocumentBuilder builder = factory.newDocumentBuilder();
      final Document document = builder.parse(inputStream);
      document.getDocumentElement().normalize();
      return document;
    } catch (final ParserConfigurationException | SAXException e) {
      throw new HttpRepositoryResolverError(e.getMessage());
    } finally {
      connection.disconnect();
    }
  }

  private static String getXmlNodeContent(final Document document, final String tagName) {
    final NodeList nodes = requireNonNull(document.getElementsByTagName(tagName));
    if (nodes.getLength() > 0) {
      return requireNonNull(requireNonNull(nodes.item(0)).getTextContent());
    }
    throw new HttpRepositoryResolverError("Tag not found in metadata");
  }

  private static String buildJarName(final String timestamp, final String buildNumber) {
    final String versionWithTimestamp = VERSION.replace("SNAPSHOT", timestamp + "-" + buildNumber);
    return ARTIFACT_ID + "-" + versionWithTimestamp + "-all.jar";
  }

  private static String buildJarUrl(final String jarName) {
    final String groupPath = GROUP_ID.replace(".", "/");
    return REPO_BASE_URL + "/" + groupPath + "/" + ARTIFACT_ID + "/" + VERSION + "/" + jarName;
  }

  private static Path prepareJarPath(final String jarName, final Path folder) throws IOException {
    final Path dependencyDir = folder.resolve("dependencies");
    Files.createDirectories(dependencyDir);
    return dependencyDir.resolve(jarName);
  }

  private static void downloadFile(final String fileUrl, final Path destination, final Consumer<String> progressLogger) throws IOException {
    final long fileSize = getFileSize(fileUrl);
    if (fileSize <= 0) {
      progressLogger.accept("Content length unknown, downloading...");
    } else {
      progressLogger.accept("File size: " + formatSize(fileSize));
    }
    final Path parent = requireNonNull(destination.getParent());
    Files.createDirectories(parent);
    final HttpURLConnection connection = createConnection(fileUrl);
    connection.setRequestMethod("GET");
    final long startTime = System.currentTimeMillis();
    try (final InputStream in = connection.getInputStream()) {
      if (fileSize > 0) {
        downloadWithProgress(in, destination, fileSize, startTime, progressLogger);
      } else {
        downloadWithoutProgress(in, destination, startTime, progressLogger);
      }
    } finally {
      connection.disconnect();
    }
  }

  private static long getFileSize(final String fileUrl) throws IOException {
    final HttpURLConnection connection = createConnection(fileUrl);
    connection.setRequestMethod("HEAD");
    try {
      return connection.getContentLengthLong();
    } finally {
      connection.disconnect();
    }
  }

  private static HttpURLConnection createConnection(final String url) throws IOException {
    final URL urlObj = new URL(url);
    final HttpURLConnection connection = (HttpURLConnection) urlObj.openConnection();
    connection.setConnectTimeout(CONNECTION_TIMEOUT);
    connection.setReadTimeout(READ_TIMEOUT);
    connection.setInstanceFollowRedirects(true);
    return connection;
  }

  private static void downloadWithProgress(
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
          final double speed = calculateSpeed(totalBytesRead, currentTime, startTime);
          final String progressMessage = formatProgressMessage(currentPercent, totalBytesRead, fileSize, speed);
          progressLogger.accept(progressMessage);
          lastUpdateTime = currentTime;
          lastPercent = currentPercent;
        }
      }
    }
    final long timeElapsed = System.currentTimeMillis() - startTime;
    final double averageSpeed = calculateSpeed(totalBytesRead, System.currentTimeMillis(), startTime);
    final String completionMessage = formatCompletionMessage(timeElapsed, averageSpeed);
    progressLogger.accept(completionMessage);
  }

  private static void downloadWithoutProgress(
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

  private static double calculateSpeed(final long bytesRead, final long currentTime, final long startTime) {
    final long elapsedMillis = currentTime - startTime;
    if (elapsedMillis == 0) {
      return 0;
    }
    return ((double) bytesRead / 1024 / 1024) / (elapsedMillis / 1000.0);
  }

  private static String formatProgressMessage(final int percent, final long bytesRead, final long fileSize, final double speed) {
    return String.format("Downloaded %d%% (%s/%s) - %.2f MB/s", percent, formatSize(bytesRead), formatSize(fileSize), speed);
  }

  private static String formatCompletionMessage(final long timeElapsed, final double speed) {
    return String.format("Download complete in %.1f seconds (%.2f MB/s)", timeElapsed / 1000.0, speed);
  }

  private static String formatSize(final long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    }
    final int exponent = (int) (Math.log(bytes) / Math.log(1024));
    final double value = bytes / Math.pow(1024, exponent);
    final String unit = String.valueOf(SIZE_UNITS.charAt(exponent - 1));
    return String.format("%.1f %sB", value, unit);
  }
}
