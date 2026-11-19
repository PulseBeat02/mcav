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
package me.brandonli.mcav.plugin.data;

import java.io.Serial;
import me.brandonli.mcav.plugin.PluginException;

/**
 * Thrown when {@code config.yml} cannot be read or is not valid YAML.
 *
 * <p>This is a {@link PluginException}: the configuration file on the server is broken, a failure outside the caller's
 * control that the server's owner fixes by correcting the file or by deleting it so that MCAV writes the default one.
 * It has a type of its own, so {@link me.brandonli.mcav.plugin.MCAVSandbox} can tell it apart from unexpected failures,
 * log what to do, and disable itself cleanly instead of failing with a stack trace.
 *
 * @since 1.0.0
 */
public final class UnreadableConfigurationException extends PluginException {

  @Serial
  private static final long serialVersionUID = 2952473813257472207L;

  UnreadableConfigurationException(final String message) {
    super(message);
  }

  UnreadableConfigurationException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
