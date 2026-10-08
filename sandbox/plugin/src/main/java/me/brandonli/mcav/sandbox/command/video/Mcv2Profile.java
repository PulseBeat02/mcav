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
package me.brandonli.mcav.sandbox.command.video;

import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Settings;

/** The version 3 encoder presets offered by the sandbox. */
public enum Mcv2Profile {
  /** Normal live thresholds. */
  DEFAULT,
  /** Faster thresholds for screens that cannot keep up. */
  FAST;

  public Settings getSettings() {
    return switch (this) {
      case DEFAULT -> Settings.DEFAULT;
      case FAST -> Settings.FAST;
    };
  }
}
