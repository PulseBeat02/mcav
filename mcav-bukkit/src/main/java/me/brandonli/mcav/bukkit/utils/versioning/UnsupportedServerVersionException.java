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
package me.brandonli.mcav.bukkit.utils.versioning;

import java.io.Serial;

/**
 * Thrown when the Bukkit module is loaded on a Minecraft version it does not support. See
 * {@link ServerEnvironment#SUPPORTED_MINECRAFT_VERSION} for the supported version.
 *
 * <p>The exception is unchecked and an {@link IllegalStateException}, so plugins can catch it together with other
 * exceptions to disable themselves cleanly.
 */
public class UnsupportedServerVersionException extends IllegalStateException {

  @Serial
  private static final long serialVersionUID = -2349087124389011253L;

  UnsupportedServerVersionException(final String message) {
    super(message);
  }
}
