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
package me.brandonli.mcav.utils;

import static java.util.Objects.requireNonNull;

import com.google.common.base.Preconditions;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.io.*;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.media.source.UriSource;

/**
 * Utility class providing various input/output operations and utilities, primarily focusing
 * on file, networking, and compression tasks.
 */
public final class IOUtils {

  private static final String IP_URL = "https://ipv4.icanhazip.com/";
  private static final long MAX_FILE_SIZE = 100 * 1024 * 1024L;
  private static final long MAX_TOTAL_SIZE = 500 * 1024 * 1024L;

  private IOUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Creates a new file at the specified path if it does not already exist.
   *
   * @param path the path of the file to create if it does not exist
   * @return true if the file was successfully created, false if the file already exists
   * @throws AssertionError if an I/O error occurs while creating the file
   */
  public static boolean createFileIfNotExists(final Path path) {
    try {
      if (Files.notExists(path)) {
        Files.createFile(path);
        return true;
      }
    } catch (final IOException e) {
      throw new AssertionError(e);
    }
    return false;
  }

  /**
   * Generates the SHA-256 hash of the file located at the specified path.
   * The method reads the entire content of the file and computes its SHA-1 hash.
   *
   * @param path the {@link Path} of the file for which the SHA-256 hash needs to be computed
   * @return a hexadecimal {@link String} representation of the SHA-256 hash of the file
   * @throws AssertionError if an {@link IOException} occurs while reading the file
   */
  public static String getSHA256Hash(final Path path) {
    try {
      final HashFunction function = Hashing.sha256();
      try (final InputStream stream = Files.newInputStream(path)) {
        final byte[] bytes = stream.readAllBytes();
        final HashCode code = function.hashBytes(bytes);
        final byte[] hash = code.asBytes();
        return bytesToHex(hash);
      }
    } catch (final IOException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * Generates the SHA-1 hash of the file located at the specified path.
   * The method reads the entire content of the file and computes its SHA-1 hash.
   *
   * @param path the {@link Path} of the file for which the SHA-1 hash needs to be computed
   * @return a hexadecimal {@link String} representation of the SHA-1 hash of the file
   * @throws AssertionError if an {@link IOException} occurs while reading the file
   */
  public static String getSHA1Hash(final Path path) {
    try {
      @SuppressWarnings("deprecation")
      final HashFunction function = Hashing.sha1();
      try (final InputStream stream = Files.newInputStream(path)) {
        final byte[] bytes = stream.readAllBytes();
        final HashCode code = function.hashBytes(bytes);
        final byte[] hash = code.asBytes();
        return bytesToHex(hash);
      }
    } catch (final IOException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * Converts an array of bytes into a hexadecimal string representation.
   * Each byte in the array is represented as a two-character hexadecimal string,
   * with leading zeros added if necessary to ensure two characters per byte.
   *
   * @param bytes the byte array to be converted to a hexadecimal string. Must not be null.
   * @return a string representing the hexadecimal encoding of the input byte array.
   * Returns an empty string if the input array is empty.
   */
  public static String bytesToHex(final byte[] bytes) {
    final int size = bytes.length;
    final StringBuilder hexString = new StringBuilder(2 * size);
    for (final byte b : bytes) {
      final String hex = Integer.toHexString(0xff & b);
      if (hex.length() == 1) {
        hexString.append('0');
      }
      hexString.append(hex);
    }
    return hexString.toString();
  }

  /**
   * Retrieves the public IP address of the machine making the request.
   * This method sends an HTTP request to a predefined URL to obtain
   * the IP address, validates the response, and returns the IP address
   * or "localhost" if the validation fails.
   * <p>
   * If an error occurs during the process, such as an I/O exception
   * or interruption, the current thread is interrupted and an
   * {@link AssertionError} is thrown.
   *
   * @return the public IP address as a {@code String}, or "localhost"
   * if the response is deemed invalid
   */
  public static String getPublicIPAddress() {
    try {
      final URI uri = URI.create(IP_URL);
      final HttpClient client = HttpClient.newHttpClient();
      final HttpRequest request = HttpRequest.newBuilder().uri(uri).build();
      final HttpResponse.BodyHandler<String> handler = HttpResponse.BodyHandlers.ofString();
      final HttpResponse<String> response = client.send(request, handler);
      final String address = response.body();
      final String encodedAddress = address.trim();
      final URI check = URI.create(encodedAddress);
      final boolean valid = checkValidUrl(check);
      return valid ? address : "localhost";
    } catch (final IOException | InterruptedException e) {
      final Thread current = Thread.currentThread();
      current.interrupt();
      throw new AssertionError(e);
    }
  }

  /**
   * Checks if a given URI points to a valid, reachable URL.
   * This method sends a HEAD request to the specified URI
   * and verifies that the response code is HTTP OK (200).
   *
   * @param uri the URI to validate as a reachable URL
   * @return {@code true} if the URI points to a valid and reachable URL, {@code false} otherwise
   */
  public static boolean checkValidUrl(final URI uri) {
    try {
      final URL url = uri.toURL();
      final HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
      urlConnection.setRequestMethod("HEAD");
      urlConnection.setConnectTimeout(5000);
      urlConnection.setReadTimeout(5000);
      final int code = urlConnection.getResponseCode();
      return (code == HttpURLConnection.HTTP_OK);
    } catch (final Exception e) {
      return false;
    }
  }

  /**
   * Retrieves the name of the file or directory referenced by the specified path.
   *
   * @param path the {@code Path} object representing the file or directory
   *             for which the name is to be retrieved. Must not be {@code null}.
   * @return the name of the file or directory as a {@code String}.
   * @throws NullPointerException if the provided path is {@code null}.
   */
  public static String getName(final Path path) {
    final Path fileName = requireNonNull(path.getFileName());
    return fileName.toString();
  }

  /**
   * Downloads an image from the specified URI source and stores it in the cache folder.
   * If the image already exists in the cache folder, it returns the existing cached file.
   * A default filename is generated if the resource URL does not contain a valid file name.
   *
   * @param source the URI source containing the resource URL from which the image will be downloaded.
   *               Must not be null and should produce a valid URI.
   * @return the {@link Path} to the downloaded image file in the cache folder.
   * @throws IOException if an I/O error occurs during the download or file operations.
   */
  public static Path downloadImage(final UriSource source) throws IOException {
    final String url = source.getResource();
    String filename = url.substring(url.lastIndexOf('/') + 1);
    if (!filename.contains(".")) {
      filename = "image_" + Math.abs(url.hashCode()) + ".jpg";
    }
    final Path cacheDir = getCachedFolder();
    final Path destination = cacheDir.resolve(filename);
    if (Files.exists(destination)) {
      return destination;
    }
    try (final java.io.InputStream in = new URL(url).openStream()) {
      Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
    }
    return destination;
  }

  /**
   * Retrieves the path to the cached folder used by the application.
   * If the required directory does not exist, it will be created.
   *
   * @return the {@code Path} object representing the cache folder
   * @throws AssertionError if the directory cannot be created due to an I/O error
   */
  public static Path getCachedFolder() {
    final String home = System.getProperty("user.home");
    final Path cacheDir = Path.of(home, ".mcav", "cache");
    if (Files.notExists(cacheDir)) {
      try {
        Files.createDirectories(cacheDir);
      } catch (final IOException e) {
        throw new AssertionError(e);
      }
    }
    return cacheDir;
  }

  /**
   * Reads Download array from a JSON resource in the classpath.
   *
   * @param resourcePath path to the JSON resource
   * @return array of Download objects
   * @throws IOException         if an I/O error occurs
   * @throws JsonSyntaxException if the JSON is invalid
   */
  public static Download[] readDownloadsFromJsonResource(final String resourcePath) {
    final String installerJson = String.format("/installers/%s", resourcePath);
    try (final Reader reader = getResourceAsStream(installerJson)) {
      final Gson gson = new Gson();
      final Type downloadArrayType = new TypeToken<Download[]>() {}.getType();
      return gson.fromJson(reader, downloadArrayType);
    } catch (final IOException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * Retrieves a resource as an InputStreamReader from the classpath.
   *
   * @param resource the path to the resource to be loaded; must not be null
   * @return an InputStreamReader for the specified resource
   * @throws NullPointerException if the resource path is null or if the resource cannot be found
   */
  public static Reader getResourceAsStream(final String resource) {
    Preconditions.checkNotNull(resource);
    return new InputStreamReader(requireNonNull(IOUtils.class.getResourceAsStream(resource)));
  }

  /**
   * Extracts the contents of a ZIP file from the specified source path to the destination directory.
   * Ensures the security and integrity of the extraction process by validating paths and checking size limits.
   *
   * @param src  the path to the source ZIP file to be extracted
   * @param dest the destination directory where the contents of the ZIP file will be extracted
   * @throws IOException    if an I/O error occurs during file extraction
   * @throws AssertionError if there are security issues such as invalid entry paths, or if individual file or total size exceeds the allowed limit
   */
  public static void unzip(final Path src, final Path dest) throws IOException {
    long totalSize = 0;
    try (final InputStream fis = Files.newInputStream(src); final ZipInputStream zis = new ZipInputStream(fis)) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        final String name = entry.getName();
        final Path path = dest.resolve(name);
        final Path resolvedPath = path.normalize();
        if (!resolvedPath.startsWith(dest)) {
          final String msg = String.format("Invalid Entry %s", name);
          throw new AssertionError(msg);
        }
        if (entry.isDirectory()) {
          Files.createDirectories(resolvedPath);
        } else {
          final Path parent = resolvedPath.getParent();
          if (parent != null && Files.notExists(parent)) {
            Files.createDirectories(parent);
          }
          try (final OutputStream os = Files.newOutputStream(resolvedPath)) {
            final byte[] buffer = new byte[8192];
            int bytesRead;
            long fileSize = 0;
            while ((bytesRead = zis.read(buffer)) != -1) {
              fileSize += bytesRead;
              totalSize += bytesRead;
              if (fileSize > MAX_FILE_SIZE) {
                final String msg = String.format("File exceeds maximum size: %s", name);
                throw new AssertionError(msg);
              }
              if (totalSize > MAX_TOTAL_SIZE) {
                final String msg = "Total extracted size exceeds limit";
                throw new AssertionError(msg);
              }
              os.write(buffer, 0, bytesRead);
            }
          }
        }
        zis.closeEntry();
      }
    }
  }

  /**
   * Creates a ZIP archive from the files and directories contained in the specified source path.
   * The ZIP file will be written to the specified destination path.
   *
   * @param src  the source path which contains files and directories to be included in the ZIP archive
   * @param dest the destination path where the ZIP archive will be created
   * @throws IOException if an I/O error occurs during the zipping process
   */
  public static void zip(final Path src, final Path dest) throws IOException {
    try (final OutputStream fis = Files.newOutputStream(src); final ZipOutputStream zip = new ZipOutputStream(fis)) {
      {
        Files.walkFileTree(
          src,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
              final Path rel = src.relativize(dir);
              final String relName = rel.toString();
              if (!relName.isEmpty()) {
                final String replaced = relName.replace("\\", "/");
                final String entryName = replaced + "/";
                final ZipEntry entry = new ZipEntry(entryName);
                zip.putNextEntry(entry);
                zip.closeEntry();
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
              final Path rel = src.relativize(file);
              final String relName = rel.toString();
              final String replaced = relName.replace("\\", "/");
              final ZipEntry entry = new ZipEntry(replaced);
              zip.putNextEntry(entry);
              Files.copy(file, zip);
              zip.closeEntry();
              return FileVisitResult.CONTINUE;
            }
          }
        );
      }
    }
  }
}
