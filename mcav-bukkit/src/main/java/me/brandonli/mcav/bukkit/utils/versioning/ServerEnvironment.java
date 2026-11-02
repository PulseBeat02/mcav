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
 *
 * <p>This class is stateless and thread-safe.
 *
 * @since 1.0.0
 */
public final class ServerEnvironment {

  /**
   * The only Minecraft version supported by the Bukkit module.
   *
   * @since 1.0.0
   */
  public static final String SUPPORTED_MINECRAFT_VERSION = "26.3";

  private static final String PATCH_PREFIX = "%s.".formatted(SUPPORTED_MINECRAFT_VERSION);

  private ServerEnvironment() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  /**
   * Returns the Minecraft version of the running server, such as {@code 26.3}.
   *
   * @return the Minecraft version of the server
   * @since 1.0.0
   */
  public static String getMinecraftVersion() {
    return Bukkit.getMinecraftVersion();
  }

  /**
   * Returns whether the running server is supported. Patch releases of the supported version, such as {@code 26.3.1},
   * are supported as well.
   *
   * @return true if the server version is supported, false otherwise
   * @since 1.0.0
   */
  public static boolean isSupported() {
    final String version = getMinecraftVersion();
    return isSupported(version);
  }

  /**
   * Returns whether the given Minecraft version is supported by the Bukkit module.
   *
   * <p>This is an exact-name or dotted-prefix check, not a runtime probe of server internals. The suffix
   * after the supported version and a dot is not parsed or validated.
   *
   * @param version the Minecraft version to check, such as {@code 26.3.1}
   * @return true if the version is supported, false otherwise
   * @throws NullPointerException if {@code version} is null
   * @since 1.0.0
   */
  public static boolean isSupported(final String version) {
    Preconditions.checkNotNull(version, "Version must not be null!");

    final boolean exactMatch = version.equals(SUPPORTED_MINECRAFT_VERSION);
    final boolean patchRelease = version.startsWith(PATCH_PREFIX);
    return exactMatch || patchRelease;
  }

  /**
   * Ensures that the running server is supported.
   *
   * @throws UnsupportedServerVersionException if the server version is not supported
   * @since 1.0.0
   */
  public static void checkSupported() {
    final String version = getMinecraftVersion();
    final boolean supported = isSupported(version);
    if (supported) {
      return;
    }

    final String message = "Minecraft %s is not supported, MCAV only runs on Minecraft %s and its patch releases!".formatted(
      version,
      SUPPORTED_MINECRAFT_VERSION
    );
    throw new UnsupportedServerVersionException(message);
  }
}
