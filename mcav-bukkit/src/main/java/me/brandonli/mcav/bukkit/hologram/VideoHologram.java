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

import org.bukkit.entity.TextDisplay;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The base class of holograms that are backed by a single text display entity. It owns the entity and removes it
 * when the hologram is killed.
 */
public abstract class VideoHologram implements Hologram {

  private @Nullable TextDisplay display;

  VideoHologram() {
    // only subclassed inside this package
  }

  /**
   * Removes the display entity from the world.
   */
  @Override
  public void kill() {
    final TextDisplay current = this.display;
    if (current == null) {
      return;
    }
    current.remove();
    this.display = null;
  }

  /**
   * Gets the display entity of this hologram.
   *
   * @return the display entity, or null if the hologram is not spawned
   */
  @Override
  public @Nullable TextDisplay getDisplay() {
    return this.display;
  }

  /**
   * Replaces the display entity of this hologram. The previous entity is not removed.
   *
   * @param display the display entity, or null to detach the hologram from its entity
   */
  @Override
  public void setDisplay(final @Nullable TextDisplay display) {
    this.display = display;
  }
}
