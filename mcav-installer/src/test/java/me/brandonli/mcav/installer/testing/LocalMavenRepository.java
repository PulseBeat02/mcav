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

/**
 * A Maven repository on the file system with hand-written POMs and small jars, so the resolver can be tested
 * without the internet. Every jar contains the resource returned by {@link #resourceName(String)}, whose content
 * is the coordinates of the artifact. Every file gets a SHA-1 checksum file next to it, as real repositories
 * publish them, because the installer rejects files with missing or wrong checksums.
 */
public final class LocalMavenRepository {

  private final Path root;

  /**
   * Creates a repository in a directory.
   *
   * @param root the directory, created when the first artifact is published
   */
  public LocalMavenRepository(final Path root) {
    this.root = root;
  }

  /**
   * Gets the URL of the repository for the resolver.
   *
   * @return the {@code file:} URL of the directory
   */
  public String getUrl() {
    final Path absolute = this.root.toAbsolutePath();
    final URI uri = absolute.toUri();
    return uri.toString();
  }

  /**
   * Gets the name of the resource every jar of an artifact contains.
   *
   * @param artifactId the artifact id
   * @return the resource name
   */
  public static String resourceName(final String artifactId) {
    return "mcav-test/" + artifactId + ".txt";
  }

  /**
   * Creates the XML of a dependency.
   *
   * @param groupId    the group id
   * @param artifactId the artifact id
   * @param version    the version
   * @param scope      the Maven scope
   * @param optional   whether the dependency is optional
   * @return the XML element
   */
  public static String dependency(
    final String groupId,
    final String artifactId,
    final String version,
    final String scope,
    final boolean optional
  ) {
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

  /**
   * Creates the XML of a compile dependency on a jar with a classifier, such as a native jar of a library.
   *
   * @param groupId    the group id
   * @param artifactId the artifact id
   * @param version    the version
   * @param classifier the classifier, such as {@code linux-x86_64}
   * @return the XML element
   */
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

  /**
   * Gets the directory of one version of an artifact.
   *
   * @param groupId    the group id
   * @param artifactId the artifact id
   * @param version    the version
   * @return the directory in the Maven layout
   */
  public Path directoryOf(final String groupId, final String artifactId, final String version) {
    final String groupPath = groupId.replace('.', '/');
    final Path groupDirectory = this.root.resolve(groupPath);
    final Path artifactDirectory = groupDirectory.resolve(artifactId);
    return artifactDirectory.resolve(version);
  }

  /**
   * Publishes an artifact with a POM and a jar.
   *
   * @param groupId      the group id
   * @param artifactId   the artifact id
   * @param version      the version
   * @param dependencies the dependency elements of the POM
   * @return the published jar
   * @throws IOException if the files cannot be written
   */
  public Path publish(final String groupId, final String artifactId, final String version, final List<String> dependencies)
    throws IOException {
    final Path directory = this.directoryOf(groupId, artifactId, version);
    Files.createDirectories(directory);
    final String baseName = artifactId + "-" + version;
    final Path pom = directory.resolve(baseName + ".pom");
    final String content = pomOf(groupId, artifactId, version, dependencies);
    Files.writeString(pom, content, StandardCharsets.UTF_8);
    writeChecksum(pom);

    final Path jar = directory.resolve(baseName + ".jar");
    final String coordinates = groupId + ":" + artifactId + ":" + version;
    final String resource = resourceName(artifactId);
    writeJar(jar, resource, coordinates);
    writeChecksum(jar);
    return jar;
  }

  private static String pomOf(final String groupId, final String artifactId, final String version, final List<String> dependencies) {
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

  /**
   * Publishes a jar with a classifier next to an artifact published with {@link #publish}. Its resource contains
   * the coordinates followed by a colon and the classifier.
   *
   * @param groupId    the group id
   * @param artifactId the artifact id
   * @param version    the version
   * @param classifier the classifier, such as {@code linux-x86_64}
   * @return the published jar
   * @throws IOException if the files cannot be written
   */
  public Path publishClassified(final String groupId, final String artifactId, final String version, final String classifier)
    throws IOException {
    final Path directory = this.directoryOf(groupId, artifactId, version);
    Files.createDirectories(directory);
    final Path jar = directory.resolve(artifactId + "-" + version + "-" + classifier + ".jar");
    final String coordinates = groupId + ":" + artifactId + ":" + version + ":" + classifier;
    final String resource = resourceName(artifactId);
    writeJar(jar, resource, coordinates);
    writeChecksum(jar);
    return jar;
  }

  /**
   * Gets the SHA-1 checksum file of a published file.
   *
   * @param file the published file
   * @return the checksum file next to it
   */
  public static Path checksumFileOf(final Path file) {
    final Path name = file.getFileName();
    return file.resolveSibling(name + ".sha1");
  }

  /**
   * Writes the SHA-1 checksum file of a file, as Maven repositories publish it.
   *
   * @param file the file
   * @throws IOException if the file cannot be read or the checksum cannot be written
   */
  public static void writeChecksum(final Path file) throws IOException {
    final byte[] content = Files.readAllBytes(file);
    final MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-1");
    } catch (final NoSuchAlgorithmException exception) {
      throw new IOException(exception);
    }
    final byte[] hash = digest.digest(content);
    final HexFormat hex = HexFormat.of();
    final String text = hex.formatHex(hash);
    final Path checksum = checksumFileOf(file);
    Files.writeString(checksum, text, StandardCharsets.US_ASCII);
  }

  /**
   * Writes a jar with one text resource.
   *
   * @param jar      the jar file
   * @param resource the name of the resource
   * @param text     the content of the resource
   * @throws IOException if the jar cannot be written
   */
  public static void writeJar(final Path jar, final String resource, final String text) throws IOException {
    try (final OutputStream output = Files.newOutputStream(jar); final JarOutputStream jarOutput = new JarOutputStream(output)) {
      final JarEntry entry = new JarEntry(resource);
      jarOutput.putNextEntry(entry);
      final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
      jarOutput.write(bytes);
      jarOutput.closeEntry();
    }
  }
}
