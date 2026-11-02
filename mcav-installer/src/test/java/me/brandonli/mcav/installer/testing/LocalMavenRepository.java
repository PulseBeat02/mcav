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
package me.brandonli.mcav.installer.testing;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class LocalMavenRepository {

  private static final String RESOURCE_FOLDER = "mcav-test/";
  private static final String RESOURCE_EXTENSION = ".txt";

  private static final String VERSION_SEPARATOR = "-";
  private static final String POM_EXTENSION = ".pom";
  private static final String JAR_EXTENSION = ".jar";
  private static final String CHECKSUM_EXTENSION = ".sha1";

  private static final String COORDINATE_SEPARATOR = ":";

  private static final String CHECKSUM_ALGORITHM = "SHA-1";

  private final Path root;

  public LocalMavenRepository(final Path root) {
    this.root = root;
  }

  public static String resourceName(final String artifactId) {
    return RESOURCE_FOLDER + artifactId + RESOURCE_EXTENSION;
  }

  public static String dependency(
    final String groupId,
    final String artifactId,
    final String version,
    final String scope,
    final Optionality optionality
  ) {
    final boolean optional = optionality == Optionality.OPTIONAL;
    return (
      "<dependency><groupId>" +
      groupId +
      "</groupId><artifactId>" +
      artifactId +
      "</artifactId><version>" +
      version +
      "</version><scope>" +
      scope +
      "</scope><optional>" +
      optional +
      "</optional></dependency>"
    );
  }

  public static String classifiedDependency(final String groupId, final String artifactId, final String version, final String classifier) {
    return (
      "<dependency><groupId>" +
      groupId +
      "</groupId><artifactId>" +
      artifactId +
      "</artifactId><version>" +
      version +
      "</version><classifier>" +
      classifier +
      "</classifier></dependency>"
    );
  }

  public Path directoryOf(final String groupId, final String artifactId, final String version) {
    final String groupPath = groupId.replace('.', '/');
    final Path groupDirectory = this.root.resolve(groupPath);
    final Path artifactDirectory = groupDirectory.resolve(artifactId);
    return artifactDirectory.resolve(version);
  }

  public Path publish(final String groupId, final String artifactId, final String version, final List<String> dependencies)
    throws IOException {
    final Path directory = this.directoryOf(groupId, artifactId, version);
    Files.createDirectories(directory);

    final String baseName = artifactId + VERSION_SEPARATOR + version;
    final Path pom = directory.resolve(baseName + POM_EXTENSION);
    final String content = this.pomOf(groupId, artifactId, version, dependencies);
    Files.writeString(pom, content, StandardCharsets.UTF_8);
    writeChecksum(pom);

    final Path jar = directory.resolve(baseName + JAR_EXTENSION);
    final String coordinates = groupId + COORDINATE_SEPARATOR + artifactId + COORDINATE_SEPARATOR + version;
    this.writeArtifactJar(jar, artifactId, coordinates);

    return jar;
  }

  private String pomOf(final String groupId, final String artifactId, final String version, final List<String> dependencies) {
    final String joined = String.join("", dependencies);
    return (
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
      "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">" +
      "<modelVersion>4.0.0</modelVersion>" +
      "<groupId>" +
      groupId +
      "</groupId><artifactId>" +
      artifactId +
      "</artifactId><version>" +
      version +
      "</version><dependencies>" +
      joined +
      "</dependencies></project>\n"
    );
  }

  public Path publishClassified(final String groupId, final String artifactId, final String version, final String classifier)
    throws IOException {
    final Path directory = this.directoryOf(groupId, artifactId, version);
    Files.createDirectories(directory);

    final Path jar = directory.resolve(artifactId + VERSION_SEPARATOR + version + VERSION_SEPARATOR + classifier + JAR_EXTENSION);
    final String coordinates =
      groupId + COORDINATE_SEPARATOR + artifactId + COORDINATE_SEPARATOR + version + COORDINATE_SEPARATOR + classifier;
    this.writeArtifactJar(jar, artifactId, coordinates);

    return jar;
  }

  private void writeArtifactJar(final Path jar, final String artifactId, final String coordinates) throws IOException {
    final String resource = resourceName(artifactId);
    writeJar(jar, resource, coordinates);
    writeChecksum(jar);
  }

  public static Path checksumFileOf(final Path file) {
    final Path name = file.getFileName();
    return file.resolveSibling(name + CHECKSUM_EXTENSION);
  }

  public static void writeChecksum(final Path file) throws IOException {
    final byte[] content = Files.readAllBytes(file);
    final MessageDigest digest;
    try {
      digest = MessageDigest.getInstance(CHECKSUM_ALGORITHM);
    } catch (final NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }

    final byte[] hash = digest.digest(content);
    final HexFormat hexadecimal = HexFormat.of();
    final String text = hexadecimal.formatHex(hash);
    final Path checksum = checksumFileOf(file);
    Files.writeString(checksum, text, StandardCharsets.US_ASCII);
  }

  public static void writeJar(final Path jar, final String resource, final String text) throws IOException {
    try (final OutputStream output = Files.newOutputStream(jar); final JarOutputStream jarOutput = new JarOutputStream(output)) {
      final JarEntry entry = new JarEntry(resource);
      jarOutput.putNextEntry(entry);
      final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
      jarOutput.write(bytes);
      jarOutput.closeEntry();
    }
  }

  public String getUrl() {
    final Path absolute = this.root.toAbsolutePath();
    final URI uri = absolute.toUri();
    return uri.toString();
  }
}
