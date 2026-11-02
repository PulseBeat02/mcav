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
package me.brandonli.mcav.installer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

final class ThirdPartyNoticesTest {

  private static final String INSTALLER_JAR_PROPERTY = "mcav.installer.jar";

  private static final String NOTICE_FILE = "META-INF/NOTICE";
  private static final String THIRD_PARTY_NOTICES_FILE = "META-INF/THIRD-PARTY-NOTICES.md";
  private static final String ASM_LICENSE_FILE = "META-INF/LICENSE-ASM";
  private static final String POM_PROPERTIES_SUFFIX = "/pom.properties";

  private static final String GSON_CLASS = "com/google/gson/Gson.class";
  private static final String ASM_CLASS_READER_CLASS = "org/objectweb/asm/ClassReader.class";
  private static final String RELOCATED_PREFIX = "me/brandonli/mcav/libs/";

  @Test
  void shouldKeepTheNoticesOfTheBundledApacheComponents() throws IOException {
    try (final ZipFile jar = this.builtJar()) {
      final String notice = this.read(jar, NOTICE_FILE);
      for (final String component : List.of("Apache HttpCore", "Apache Commons Codec", "Maven Artifact Resolver API", "Maven Model")) {
        final boolean kept = notice.contains(component);
        assertTrue(kept, component + " in " + notice);
      }
    }
  }

  private ZipFile builtJar() throws IOException {
    final String path = System.getProperty(INSTALLER_JAR_PROPERTY);
    assertNotNull(path, "the build passes the jar it built");

    return new ZipFile(path);
  }

  private String read(final ZipFile jar, final String name) throws IOException {
    final ZipEntry entry = jar.getEntry(name);
    assertNotNull(entry, name + " is in the jar");

    try (final InputStream input = jar.getInputStream(entry)) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void shouldNameEveryBundledComponentInItsThirdPartyNotices() throws IOException {
    try (final ZipFile jar = this.builtJar()) {
      final String notices = this.read(jar, THIRD_PARTY_NOTICES_FILE);
      final List<String> bundled = jar
        .stream()
        .map(ZipEntry::getName)
        .filter(name -> name.endsWith(POM_PROPERTIES_SUFFIX))
        .toList();
      final int bundledCount = bundled.size();
      final boolean bundlesTheResolver = bundledCount > 20;
      assertTrue(bundlesTheResolver, "the jar bundles the resolver: " + bundled);

      final List<String> unnamed = this.findUnnamedComponents(jar, bundled, notices);
      assertEquals(List.of(), unnamed, "bundled, but not in THIRD-PARTY-NOTICES.md");
    }
  }

  private List<String> findUnnamedComponents(final ZipFile jar, final List<String> bundled, final String notices) throws IOException {
    final List<String> unnamed = new ArrayList<>();
    for (final String name : bundled) {
      final String coordinates = this.coordinatesOf(jar, name);
      final String row = "| " + coordinates + " |";
      if (!notices.contains(row)) {
        unnamed.add(coordinates);
      }
    }

    return unnamed;
  }

  private String coordinatesOf(final ZipFile jar, final String name) throws IOException {
    final ZipEntry entry = jar.getEntry(name);
    final Properties component = new Properties();
    try (final InputStream input = jar.getInputStream(entry)) {
      component.load(input);
    }

    final String groupId = component.getProperty("groupId");
    final String artifactId = component.getProperty("artifactId");
    return groupId + ":" + artifactId;
  }

  @Test
  void shouldIsolateBundledLibrariesFromTheHostClassPath() throws IOException {
    try (final ZipFile jar = this.builtJar()) {
      final List<String> classes = List.of(GSON_CLASS, ASM_CLASS_READER_CLASS);
      final List<String> unrelocated = classes
        .stream()
        .filter(name -> jar.getEntry(name) != null)
        .toList();
      assertEquals(List.of(), unrelocated, "bundled libraries must not use host namespaces");

      for (final String name : classes) {
        final ZipEntry privateCopy = jar.getEntry(RELOCATED_PREFIX + name);
        assertNotNull(privateCopy, "the private copy is still bundled: " + name);
      }
    }
  }

  @Test
  void shouldKeepTheBundledAsmCopyrightAndLicense() throws IOException, NoSuchAlgorithmException {
    try (final ZipFile jar = this.builtJar()) {
      final String notices = this.read(jar, THIRD_PARTY_NOTICES_FILE);
      final boolean asmNamed = notices.contains("| org.ow2.asm:asm | BSD-3-Clause |");
      assertTrue(asmNamed, "the bundled ASM component must be named in the notices");

      final String license = this.read(jar, ASM_LICENSE_FILE);
      final byte[] bytes = license.getBytes(StandardCharsets.UTF_8);
      final byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      final String actual = HexFormat.of().formatHex(digest);
      assertEquals("337e92fd361effe495d03978a34a3cd44681a63b8329adfd99c8996b306b2a8c", actual);
    }
  }
}
