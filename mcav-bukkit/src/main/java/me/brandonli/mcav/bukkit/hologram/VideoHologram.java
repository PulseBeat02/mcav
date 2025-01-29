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
 * Represents a video hologram that can be displayed in the game.
 */
public abstract class VideoHologram implements Hologram {

  private @Nullable TextDisplay display;

  VideoHologram() {
    // no-op
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void kill() {
    if (this.display != null) {
      this.display.remove();
      this.display = null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public @Nullable TextDisplay getDisplay() {
    return this.display;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setDisplay(final @Nullable TextDisplay display) {
    this.display = display;
  }
}
