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
package me.brandonli.mcav.sandbox.locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import me.brandonli.mcav.bukkit.media.config.ScoreboardConfiguration;
import me.brandonli.mcav.sandbox.testing.Components;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link Message}.
 */
final class MessageTest {

  private static List<Field> messageFields() {
    final Field[] fields = Message.class.getDeclaredFields();
    final List<Field> messages = new ArrayList<>();
    for (final Field field : fields) {
      final boolean synthetic = field.isSynthetic();
      if (!synthetic) {
        messages.add(field);
      }
    }
    return messages;
  }

  @Test
  void everyMessageHasATextInTheBundledLanguage() throws ReflectiveOperationException {
    final List<Field> fields = messageFields();
    final int fieldCount = fields.size();
    assertEquals(58, fieldCount);
    for (final Field field : fields) {
      final String name = field.getName();
      final Object message = field.get(null);
      final Component component =
        switch (message) {
          case final LocaleTools.NullComponent nullComponent -> nullComponent.build();
          case final LocaleTools.UniComponent<?> uniComponent -> buildWithUrl(uniComponent);
          default -> throw new AssertionError("Unexpected message type of " + name);
        };
      final String text = Components.plain(component);
      final boolean blank = text.isBlank();
      assertFalse(blank, name);
      final boolean unrendered = text.contains("<") || text.contains("$URL$");
      assertFalse(unrendered, name + " was not rendered: " + text);
    }
  }

  private static Component buildWithUrl(final LocaleTools.UniComponent<?> message) throws ReflectiveOperationException {
    // the type of the argument is not known here, so the message is built through its erased method
    final Method build = LocaleTools.UniComponent.class.getMethod("build", Object.class);
    final Object built = build.invoke(message, "https://example.com/");
    final Component component = assertInstanceOf(Component.class, built);
    final String text = Components.plain(component);
    final boolean containsUrl = text.endsWith("https://example.com/");
    assertTrue(containsUrl, text);
    return component;
  }

  @Test
  void rendersTheDumpLink() {
    final Component component = Message.SEND_DUMP.build("https://paste.helpch.at/abc");
    final String text = Components.plain(component);
    assertEquals("Created a new dump! Copy and paste this link for support: https://paste.helpch.at/abc", text);
  }

  @Test
  void rendersTheDiscordLink() {
    final Component component = Message.AUDIO_DISCORD.build("https://discord.com/channels/1/2");
    final String text = Components.plain(component);
    assertEquals("Click on the URL to join the Discord voice channel! https://discord.com/channels/1/2", text);
  }

  @Test
  void tellsThatVlcAndYtdlpAreStillBeingPrepared() {
    final Component vlc = Message.VLC_PREPARING.build();
    final Component ytdlp = Message.YTDLP_PREPARING.build();
    final Component unsupported = Message.UNSUPPORTED_PLAYER.build();
    final String vlcText = Components.plain(vlc);
    final String ytdlpText = Components.plain(ytdlp);
    final String unsupportedText = Components.plain(unsupported);
    assertEquals("VLC is still being prepared, try again shortly or use FFMPEG", vlcText);
    assertEquals("yt-dlp is still being prepared, try again shortly or use a direct link to the media", ytdlpText);
    assertEquals("VLC Media Player is not supported! Please use a different player", unsupportedText);
  }

  @Test
  void namesTheLineLimitOfScoreboards() {
    final Component component = Message.SCOREBOARD_LINES.build();
    final String text = Components.plain(component);
    final String limit = String.valueOf(ScoreboardConfiguration.MAX_LINES);
    assertEquals("A scoreboard shows at most " + limit + " lines! Use a height from 1 to " + limit + ", like 24x" + limit, text);
  }
}
