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
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * Tests the notices of the built installer jar, which bundles the Maven resolver: Apache-2.0 asks that a redistribution
 * keeps the NOTICE file of every component, and the jar names everything it bundles with its licence.
 */
final class ThirdPartyNoticesTest {

  private static ZipFile builtJar() throws IOException {
    final String path = System.getProperty("mcav.installer.jar");
    assertNotNull(path, "the build passes the jar it built");
    return new ZipFile(path);
  }

  private static String read(final ZipFile jar, final String name) throws IOException {
    final ZipEntry entry = jar.getEntry(name);
    assertNotNull(entry, name + " is in the jar");
    try (final InputStream input = jar.getInputStream(entry)) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void keepsTheNoticesOfTheBundledApacheComponents() throws IOException {
    try (final ZipFile jar = builtJar()) {
      final String notice = read(jar, "META-INF/NOTICE");
      for (final String component : List.of("Apache HttpCore", "Apache Commons Codec", "Maven Artifact Resolver API", "Maven Model")) {
        assertTrue(notice.contains(component), component + " in " + notice);
      }
    }
  }

  @Test
  void namesEveryBundledComponentInItsThirdPartyNotices() throws IOException {
    try (final ZipFile jar = builtJar()) {
      final String notices = read(jar, "META-INF/THIRD-PARTY-NOTICES.md");
      final List<String> unnamed = new ArrayList<>();
      final List<String> bundled = jar
        .stream()
        .map(ZipEntry::getName)
        .filter(name -> name.endsWith("/pom.properties"))
        .toList();
      for (final String name : bundled) {
        final Properties component = new Properties();
        try (final InputStream input = jar.getInputStream(jar.getEntry(name))) {
          component.load(input);
        }
        final String coordinates = component.getProperty("groupId") + ":" + component.getProperty("artifactId");
        if (!notices.contains("| " + coordinates + " |")) {
          unnamed.add(coordinates);
        }
      }
      assertTrue(bundled.size() > 20, "the jar bundles the resolver: " + bundled);
      assertEquals(List.of(), unnamed, "bundled, but not in THIRD-PARTY-NOTICES.md");
    }
  }
}
