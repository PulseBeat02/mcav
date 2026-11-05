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
package me.brandonli.mcav.bukkit.hologram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.json.ytdlp.format.UrlParseDump;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.TextDisplay;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Tag;
import org.mockito.ArgumentMatchers;
import org.mockito.invocation.InvocationOnMock;

@Tag("fuzz")
final class StandardVideoHologramFuzzTest {

  private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
  private static final int HEADER_BYTES = 1 + Integer.BYTES;
  private static final AtomicReference<Component> SHOWN = new AtomicReference<>();
  // a location refers to its world only weakly, so the test keeps the world itself
  private static final World WORLD = createWorld();
  private static final Location LOCATION = new Location(WORLD, 1, 2, 3);

  // the mocks live as long as the fuzzer runs, so they keep no record of their calls
  private static World createWorld() {
    final TextDisplay display = mock(TextDisplay.class, withSettings().stubOnly());
    when(display.isValid()).thenReturn(true);
    doAnswer(StandardVideoHologramFuzzTest::show).when(display).text(any());
    final World world = mock(World.class, withSettings().stubOnly());
    when(world.spawn(any(Location.class), eq(TextDisplay.class), ArgumentMatchers.<Consumer<? super TextDisplay>>any())).thenAnswer(
      invocation -> configure(invocation, display)
    );
    return world;
  }

  private static @Nullable Void show(final InvocationOnMock invocation) {
    final Component text = invocation.getArgument(0);
    SHOWN.set(text);
    return null;
  }

  private static TextDisplay configure(final InvocationOnMock invocation, final TextDisplay display) {
    final Consumer<TextDisplay> configurator = invocation.getArgument(2);
    configurator.accept(display);
    return display;
  }

  @FuzzTest(maxDuration = "30s")
  void shouldShowTheTitleAndTheUploaderAsTheSiteNamedThem(final byte[] input) {
    if (input.length < HEADER_BYTES) {
      return;
    }

    final int flags = input[0];
    final int timestamp = ByteBuffer.wrap(input, 1, Integer.BYTES).getInt();
    final String text = new String(input, HEADER_BYTES, input.length - HEADER_BYTES, StandardCharsets.UTF_8);
    final int separator = text.indexOf('\0');
    final @Nullable String reportedTitle = (flags & 1) != 0 ? (separator < 0 ? text : text.substring(0, separator)) : null;
    final @Nullable String reportedUploader = (flags & 2) != 0 ? (separator < 0 ? "" : text.substring(separator + 1)) : null;
    final int reportedTimestamp = (flags & 4) != 0 ? timestamp : 0;
    final JsonObject json = new JsonObject();
    json.addProperty("title", reportedTitle);
    json.addProperty("uploader", reportedUploader);
    json.addProperty("timestamp", reportedTimestamp);
    json.addProperty("duration", 1);
    final Gson gson = GsonProvider.getSimple();
    final UrlParseDump dump = gson.fromJson(json, UrlParseDump.class);

    SHOWN.set(null);
    new StandardVideoHologram().spawn(LOCATION, dump);
    final String plain = PLAIN.serialize(SHOWN.get());

    final String title = reportedTitle == null ? "Unknown title" : reportedTitle;
    final String uploader = reportedUploader == null ? "Unknown uploader" : reportedUploader;
    final String start = "%s\n%s (".formatted(title, uploader);
    final boolean shownAsNamed = plain.startsWith(start) && plain.endsWith(")");
    assertTrue(shownAsNamed, () -> "shown as named: [%s...)] but was [%s]".formatted(start, plain));

    if (reportedTimestamp <= 0) {
      final String expected = "%sUnknown date)".formatted(start);
      assertEquals(expected, plain, "a video without a date says so");
    }
  }
}
