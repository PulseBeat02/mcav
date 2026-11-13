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
package me.brandonli.mcav.plugin.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import me.brandonli.mcav.plugin.PluginException;
import org.junit.jupiter.api.Test;

final class MissingVoiceChatExceptionTest {

  private static final String MESSAGE = "Simple Voice Chat audio is enabled, but the Simple Voice Chat plugin is not installed!";

  @Test
  void shouldKeepTheMessageAndTheCause() {
    final IllegalStateException cause = new IllegalStateException("Plugin voicechat is disabled!");
    final MissingVoiceChatException exception = new MissingVoiceChatException(MESSAGE, cause);

    final String message = exception.getMessage();
    final Throwable actualCause = exception.getCause();
    assertEquals(MESSAGE, message);
    assertSame(cause, actualCause);
  }

  @Test
  void shouldKeepTheMessage() {
    final MissingVoiceChatException exception = new MissingVoiceChatException(MESSAGE);
    final String message = exception.getMessage();
    assertEquals(MESSAGE, message);
  }

  @Test
  void shouldBeAPluginException() {
    final MissingVoiceChatException exception = new MissingVoiceChatException(MESSAGE);
    assertInstanceOf(PluginException.class, exception, "a caller handles a missing Simple Voice Chat like every failure of the plugin");
  }
}
