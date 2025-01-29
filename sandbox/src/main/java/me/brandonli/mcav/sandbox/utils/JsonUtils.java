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
package me.brandonli.mcav.sandbox.utils;

/*

MIT License

Copyright (c) 2024 Brandon Li

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

*/

import com.google.gson.Gson;
import java.io.IOException;
import java.io.Reader;
import java.util.List;
import java.util.Map;
import me.brandonli.mcav.json.GsonProvider;

public final class JsonUtils {

  private static final Gson GSON;

  static {
    GSON = GsonProvider.getSimple();
  }

  private JsonUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  @SuppressWarnings("unchecked")
  public static <T> List<T> toListFromResource(final String resource) {
    try (final Reader reader = IOUtils.getResourceAsStreamReader(resource)) {
      return GSON.fromJson(reader, List.class);
    } catch (final IOException e) {
      throw new AssertionError(e);
    }
  }

  @SuppressWarnings("unchecked")
  public static <T, V> Map<T, V> toMapFromResource(final String resource) throws IOException {
    try (final Reader reader = IOUtils.getResourceAsStreamReader(resource)) {
      return GSON.fromJson(reader, Map.class);
    }
  }
}
