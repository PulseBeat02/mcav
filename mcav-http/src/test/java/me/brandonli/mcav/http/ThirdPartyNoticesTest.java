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

  private static String read(final String name) throws IOException {
    final String path = System.getProperty("mcav.http.jar");
    assertNotNull(path, "the build passes the jar it built");
    try (final ZipFile jar = new ZipFile(path)) {
      final ZipEntry entry = jar.getEntry(name);
      assertNotNull(entry, name + " is in the jar");
      try (final InputStream input = jar.getInputStream(entry)) {
        return new String(input.readAllBytes(), StandardCharsets.UTF_8);
      }
    }
  }

  @Test
  void theWebPageShipsTheNoticesOfItsNpmPackages() throws IOException {
    final String notices = read("mcav/http/website/THIRD-PARTY-NOTICES.txt");
    for (final String component : new String[] { "react@", "react-dom@", "next@", "scheduler@", "styled-jsx@" }) {
      assertTrue(notices.contains("\n" + component), component + " is named");
    }
    // packages the page never imports ship nothing, so the notices do not name them
    for (final String unused : new String[] { "@mui/material@", "@emotion/react@", "howler@", "@fontsource/roboto@" }) {
      assertFalse(notices.contains("\n" + unused), unused + " is not named");
    }
    assertTrue(notices.contains("Permission is hereby granted"), "the MIT notice itself, not only the name of the licence");
  }

  @Test
  void theJarNamesWhatItBundles() throws IOException {
    final String notices = read("META-INF/THIRD-PARTY-NOTICES.md");
    assertTrue(notices.contains("## mcav-http.jar"), notices);
  }
}
