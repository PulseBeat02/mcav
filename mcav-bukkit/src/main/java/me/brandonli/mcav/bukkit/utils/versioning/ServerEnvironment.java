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

import com.google.common.base.Preconditions;
import org.bukkit.Bukkit;

/**
 * Describes the server environment the Bukkit module runs in.
 *
 * <p>The module is compiled against the internals of exactly one Minecraft version, so running it on any other
 * version would fail in unpredictable ways, often long after startup. This class lets the module detect that
 * situation up front and fail with a clear message instead.
 */
public final class ServerEnvironment {

  /**
   * The only Minecraft version supported by the Bukkit module.
   */
  public static final String SUPPORTED_MINECRAFT_VERSION = "26.2";

  private static final String PATCH_PREFIX = SUPPORTED_MINECRAFT_VERSION + ".";

  private ServerEnvironment() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Gets the Minecraft version of the running server, such as {@code 26.2}.
   *
   * @return the Minecraft version of the server
   */
  public static String getMinecraftVersion() {
    return Bukkit.getMinecraftVersion();
  }

  /**
   * Checks whether the running server is supported. Patch releases of the supported version, such as
   * {@code 26.2.1}, are supported as well.
   *
   * @return true if the server version is supported, false otherwise
   */
  public static boolean isSupported() {
    final String version = getMinecraftVersion();
    return isSupported(version);
  }

  /**
   * Checks whether the given Minecraft version is supported by the Bukkit module.
   *
   * @param version the Minecraft version to check, such as {@code 26.2.1}
   * @return true if the version is supported, false otherwise
   */
  public static boolean isSupported(final String version) {
    Preconditions.checkNotNull(version, "Version must not be null");
    final boolean exactMatch = version.equals(SUPPORTED_MINECRAFT_VERSION);
    final boolean patchRelease = version.startsWith(PATCH_PREFIX);
    return exactMatch || patchRelease;
  }

  /**
   * Ensures that the running server is supported.
   *
   * @throws UnsupportedServerVersionException if the server version is not supported
   */
  public static void checkSupported() {
    final String version = getMinecraftVersion();
    final boolean supported = isSupported(version);
    if (supported) {
      return;
    }
    final String message = "MCAV only supports Minecraft %s, but the server is running %s!".formatted(SUPPORTED_MINECRAFT_VERSION, version);
    throw new UnsupportedServerVersionException(message);
  }
}
