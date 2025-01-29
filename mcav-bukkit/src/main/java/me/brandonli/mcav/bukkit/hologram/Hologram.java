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

import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import org.bukkit.Location;
import org.bukkit.entity.TextDisplay;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Represents a hologram for video playback information.
 */
public interface Hologram {
  /**
   * Creates a basic hologram instance.
   *
   * @return A new instance of a standard video hologram.
   */
  static Hologram basic() {
    return new StandardVideoHologram();
  }

  /**
   * Handles a request to create or update the hologram at the specified location with the provided video metadata.
   *
   * @param location The location where the hologram should be displayed.
   * @param dump The video metadata containing information such as title, uploader, and upload date.
   */
  void handleRequest(final Location location, final URLParseDump dump);

  /**
   * Starts the hologram, making it visible in the game world.
   */
  void start();

  /**
   * Kills the hologram, removing it from the game world.
   */
  void kill();

  /**
   * Gets the display entity associated with this hologram.
   *
   * @return The TextDisplay entity, or null if it has not been set.
   */
  @Nullable TextDisplay getDisplay();

  /**
   * Sets the display entity for this hologram.
   *
   * @param display The TextDisplay entity to set, or null to remove the current display.
   */
  void setDisplay(@Nullable TextDisplay display);
}
