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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.TextDisplay;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Tag;
import org.mockito.ArgumentMatchers;
import org.mockito.invocation.InvocationOnMock;

/**
 * Fuzzes the text a video hologram shows about a video, which comes from the site of the video through yt-dlp: the
 * title and the uploader are whatever the site names them, tags, escapes and backslashes included. Whatever they are,
 * the hologram shows the title on the first line and the uploader on the second, each exactly as the site named it, and
 * a placeholder for a missing one; no part of them is read as markup.
 *
 * <p>The first byte of an input says which of the title (bit 0), the uploader (bit 1) and the upload time (bit 2) the
 * site reported; the next four bytes are the upload time in seconds, and the rest is the title and the uploader in
 * UTF-8, separated by the first zero byte. The seeds are titles and uploaders of real videos, tags, escaped tags and
 * backslashes.
 */
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
  void showsTheTitleAndTheUploaderAsTheSiteNamedThem(final byte[] input) {
    if (input.length < HEADER_BYTES) {
      return;
    }
    final int flags = input[0];
    final int timestamp = ByteBuffer.wrap(input, 1, Integer.BYTES).getInt();
    final String text = new String(input, HEADER_BYTES, input.length - HEADER_BYTES, StandardCharsets.UTF_8);
    final int separator = text.indexOf('\0');
    final URLParseDump dump = new URLParseDump();
    dump.title = (flags & 1) != 0 ? (separator < 0 ? text : text.substring(0, separator)) : null;
    dump.uploader = (flags & 2) != 0 ? (separator < 0 ? "" : text.substring(separator + 1)) : null;
    dump.timestamp = (flags & 4) != 0 ? timestamp : 0;
    dump.duration = 1;

    SHOWN.set(null);
    new StandardVideoHologram().handleRequest(LOCATION, dump);
    final String plain = PLAIN.serialize(SHOWN.get());

    final String title = dump.title == null ? "Unknown title" : dump.title;
    final String uploader = dump.uploader == null ? "Unknown uploader" : dump.uploader;
    final String start = title + "\n" + uploader + " (";
    assertTrue(plain.startsWith(start) && plain.endsWith(")"), () -> "shown as named: [" + start + "...)] but was [" + plain + "]");
    if (dump.timestamp <= 0) {
      assertEquals(start + "Unknown date)", plain, "a video without a date says so");
    }
  }
}
