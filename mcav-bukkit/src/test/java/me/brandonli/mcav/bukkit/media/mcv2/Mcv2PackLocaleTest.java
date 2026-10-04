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
package me.brandonli.mcav.bukkit.media.mcv2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ResourceLock(Resources.LOCALE)
final class Mcv2PackLocaleTest {

  @TempDir
  private Path directory;

  @ParameterizedTest
  @ValueSource(strings = { "ar-EG", "fa-IR", "bn-BD" })
  void generatedShaderNumbersDoNotDependOnTheServerLocale(final String languageTag) throws IOException {
    final Locale previous = Locale.getDefault(Locale.Category.FORMAT);
    final Mcv2Configuration configuration = Mcv2ConfigurationTest.complete().video(320, 180).pageSlots(2).streamId(9).build();
    try {
      Locale.setDefault(Locale.Category.FORMAT, Locale.ROOT);
      final Map<String, String> expected = this.shaders(configuration, "root.zip");
      Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag(languageTag));
      final Map<String, String> actual = this.shaders(configuration, "localized.zip");
      assertFalse(expected.isEmpty());
      assertEquals(expected.keySet(), actual.keySet());
      for (final Map.Entry<String, String> shader : expected.entrySet()) {
        assertEquals(shader.getValue(), actual.get(shader.getKey()), shader.getKey() + " must keep locale-independent GLSL numbers");
      }
    } finally {
      Locale.setDefault(Locale.Category.FORMAT, previous);
    }
  }

  private Map<String, String> shaders(final Mcv2Configuration configuration, final String name) throws IOException {
    final Path zip = this.directory.resolve(name);
    Mcv2Pack.write(configuration, true, zip);
    final Map<String, String> shaders = new HashMap<>();
    try (final ZipFile archive = new ZipFile(zip.toFile())) {
      for (final ZipEntry entry : archive.stream().toList()) {
        if (entry.getName().endsWith(".glsl")) {
          try (final InputStream input = archive.getInputStream(entry)) {
            shaders.put(entry.getName(), new String(input.readAllBytes(), StandardCharsets.UTF_8));
          }
        }
      }
    }
    return shaders;
  }
}
