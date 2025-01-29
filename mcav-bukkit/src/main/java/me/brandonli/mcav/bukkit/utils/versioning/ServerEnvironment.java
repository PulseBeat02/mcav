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

import static java.util.Objects.requireNonNull;

import java.util.Map;
import org.bukkit.Bukkit;

public final class ServerEnvironment {

  private ServerEnvironment() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  private static final Map<ServerVersion, String> VERSION_MAP = Map.of(
    ServerVersion.V_1_21_4,
    "v1_21_R3",
    ServerVersion.V_1_21_5,
    "v1_21_R4"
  );
  private static final String MINECRAFT_PACKAGE;

  static {
    final ServerVersion version = getVersion();
    MINECRAFT_PACKAGE = requireNonNull(VERSION_MAP.get(version));
  }

  private static ServerVersion getVersion() {
    final String bukkitVersion = Bukkit.getBukkitVersion();
    final ServerVersion fallbackVersion = ServerVersion.V_1_8_8;
    if (bukkitVersion.contains("Unknown")) {
      return fallbackVersion;
    } else {
      final ServerVersion[] reversed = ServerVersion.getReversed();
      for (final ServerVersion val : reversed) {
        final String name = val.getReleaseName();
        if (bukkitVersion.contains(name)) {
          return val;
        }
      }
    }
    return fallbackVersion;
  }

  public static String getNMSRevision() {
    return MINECRAFT_PACKAGE;
  }
}
