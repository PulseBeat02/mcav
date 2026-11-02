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

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class ResourceUtils {

  private ResourceUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  public static String readResource(final ClassLoader loader, final String name) throws IOException {
    try (final InputStream stream = loader.getResourceAsStream(name)) {
      assertNotNull(stream, "missing resource %s".formatted(name));

      final byte[] bytes = stream.readAllBytes();
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }
}
