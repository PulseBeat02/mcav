/*
 * This file is part of mcav, a media playback library for Minecraft
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

import java.util.Map;
import org.bukkit.Bukkit;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Utility class to determine the server environment and version.
 */
public final class ServerEnvironment {

  private ServerEnvironment() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  private static final Map<ServerVersion, String> VERSION_MAP = Map.of(ServerVersion.V_1_21_6, "v1_21_R5");

  private static final @Nullable String MINECRAFT_PACKAGE = VERSION_MAP.get(getVersion());

  private static ServerVersion getVersion() {
    final String bukkitVersion = Bukkit.getBukkitVersion();
    final ServerVersion fallbackVersion = ServerVersion.V_1_8_8;
    if (bukkitVersion.contains("Unknown")) {
      return fallbackVersion;
    }

    final ServerVersion[] reversed = ServerVersion.getReversed();
    for (final ServerVersion version : reversed) {
      final String versionName = version.getReleaseName();
      if (bukkitVersion.contains(versionName)) {
        return version;
      }
    }

    return fallbackVersion;
  }

  /**
   * Gets the server NMS version.
   *
   * @return the server version
   */
  public static String getNMSRevision() {
    return MINECRAFT_PACKAGE != null ? MINECRAFT_PACKAGE : "v1_8_R3";
  }
}
