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
package me.brandonli.mcav.plugin.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import java.util.ArrayList;
import java.util.List;

final class PaperServerAssertions {

  private PaperServerAssertions() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  static void assertRanCleanly(final int exitCode, final String mediaInformation, final List<String> output) {
    assertEquals(0, exitCode, "the server stops cleanly");

    final Gson gson = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    final JsonElement actual = gson.fromJson(mediaInformation, JsonElement.class);
    final JsonObject expected = new JsonObject();
    expected.addProperty("duration", 0);
    expected.addProperty("view_count", 0);
    expected.addProperty("like_count", 0);
    assertEquals(expected, actual, "the idle media response contains the complete empty snapshot");

    assertLogged(output, "MCAV loaded in");
    assertLogged(output, "JavaCV natives loaded in");
    assertLogged(output, "Simple Voice Chat audio is ready");
    assertNoErrors(output);
  }

  static void assertLogged(final List<String> output, final String text) {
    for (final String line : output) {
      if (line.contains(text)) {
        return;
      }
    }
    final String message = "Line \"%s\" is missing from the server log!".formatted(text);
    fail(message);
  }

  static void assertNoErrors(final List<String> output) {
    final List<String> errors = new ArrayList<>();
    for (final String line : output) {
      final boolean startFailure =
        (line.contains("Playback of ") || line.contains("VLC playback of ")) && line.contains(" could not be started.");
      final boolean playbackFailure =
        line.contains("Failed to decode media") ||
        line.contains("Video filter failed") ||
        line.contains("Audio filter failed") ||
        line.contains("Failed to render a frame") ||
        startFailure;
      final boolean error =
        line.contains(" ERROR]") ||
        line.contains("/ERROR]") ||
        line.contains("Error occurred while enabling") ||
        line.contains("Exception in thread ") ||
        ((line.contains(" WARN]") || line.contains("/WARN]")) && playbackFailure);
      if (error) {
        errors.add(line);
      }
    }
    final String separator = System.lineSeparator();
    final String report = String.join(separator, errors);
    final boolean clean = errors.isEmpty();
    assertTrue(clean, "the server logged errors:%s%s".formatted(separator, report));
  }
}
