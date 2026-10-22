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
package me.brandonli.mcav.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * Tests the notices of the built mcav-http jar, whose audio web page bundles npm packages: MIT, BSD, ISC and Apache-2.0
 * ask that their copyright and licence notices go with the copies.
 */
final class ThirdPartyNoticesTest {

  private static final String WEBSITE_NOTICES_PATH = "mcav/http/website/THIRD-PARTY-NOTICES.txt";
  private static final String JAR_NOTICES_PATH = "META-INF/THIRD-PARTY-NOTICES.md";

  @Test
  void shouldShipTheNoticesOfItsNpmPackagesInTheWebPage() throws IOException {
    final String notices = this.read(WEBSITE_NOTICES_PATH);
    for (final String component : new String[] { "react@", "react-dom@", "next@", "scheduler@", "styled-jsx@" }) {
      final String entry = "\n%s".formatted(component);
      final boolean named = notices.contains(entry);
      assertTrue(named, "%s is named".formatted(component));
    }

    for (final String unused : new String[] { "@mui/material@", "@emotion/react@", "howler@", "@fontsource/roboto@" }) {
      final String entry = "\n%s".formatted(unused);
      final boolean named = notices.contains(entry);
      assertFalse(named, "%s is not named".formatted(unused));
    }

    final boolean includesTheNotice = notices.contains("Permission is hereby granted");
    assertTrue(includesTheNotice, "the MIT notice itself, not only the name of the licence");
  }

  private String read(final String name) throws IOException {
    final String path = System.getProperty("mcav.http.jar");
    assertNotNull(path, "the build passes the jar it built");

    try (final ZipFile jar = new ZipFile(path)) {
      final ZipEntry entry = jar.getEntry(name);
      assertNotNull(entry, "%s is in the jar".formatted(name));

      try (final InputStream input = jar.getInputStream(entry)) {
        return new String(input.readAllBytes(), StandardCharsets.UTF_8);
      }
    }
  }

  @Test
  void shouldNameWhatTheJarBundles() throws IOException {
    final String notices = this.read(JAR_NOTICES_PATH);
    final boolean namesTheJar = notices.contains("## mcav-http.jar");
    assertTrue(namesTheJar, notices);
  }
}
