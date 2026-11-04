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

import java.util.Optional;
import org.bukkit.entity.TextDisplay;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The base class of holograms that are backed by a single text display entity. It owns the entity and removes it
 * when the hologram is killed.
 *
 * <p>This class is not thread-safe: use it on the server main thread.
 *
 * @since 1.0.0
 */
public abstract class VideoHologram implements Hologram {

  private @Nullable TextDisplay display;

  VideoHologram() {}

  /**
   * Removes the display entity from the world.
   *
   * @since 1.0.0
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
   * Returns the display entity of this hologram.
   *
   * @return the display entity, or empty if the hologram is not spawned
   * @since 1.0.0
   */
  @Override
  public Optional<TextDisplay> getDisplay() {
    return Optional.ofNullable(this.display);
  }

  /**
   * Replaces the display entity of this hologram. The previous entity is not removed. The caller must remove any
   * replaced or detached entity. A non-null replacement becomes owned by this hologram and is removed by
   * {@link #kill()}.
   *
   * @param display the display entity, or null to detach the hologram from its entity
   * @since 1.0.0
   */
  @Override
  public void setDisplay(final @Nullable TextDisplay display) {
    this.display = display;
  }
}
