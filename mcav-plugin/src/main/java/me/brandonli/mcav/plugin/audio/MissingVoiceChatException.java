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

import java.io.Serial;
import me.brandonli.mcav.plugin.PluginException;

/**
 * Thrown when Simple Voice Chat audio is enabled in {@code config.yml} but the Simple Voice Chat plugin
 * ({@code voicechat}) is not installed on the server.
 *
 * <p>This is a {@link PluginException}: the configuration asks for an audio output that the server cannot provide, a
 * failure outside the caller's control that the server's owner fixes by installing the plugin or by turning the option
 * off. It has a type of its own, so {@link me.brandonli.mcav.plugin.MCAVSandbox} can tell it apart from unexpected
 * failures, log what to do, and disable itself cleanly instead of failing with a stack trace.
 *
 * @since 1.0.0
 */
public final class MissingVoiceChatException extends PluginException {

  @Serial
  private static final long serialVersionUID = 4127518399460184733L;

  MissingVoiceChatException(final String message) {
    super(message);
  }
}
