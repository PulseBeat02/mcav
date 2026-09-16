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
package me.brandonli.mcav.bukkit.media.result;

/**
 * Characters that work well as pixels in chat, scoreboard, and entity displays. {@link #FULL_CHARACTER} gives the
 * most solid picture, while the other characters leave gaps between pixels for a stylized look.
 */
public final class Characters {

  /**
   * A full block, which fills the entire character cell.
   */
  public static final String FULL_CHARACTER = "█";

  /**
   * An outlined square.
   */
  public static final String WHITE_SQUARE = "□";

  /**
   * A filled square.
   */
  public static final String BLACK_SQUARE = "■";

  /**
   * An outlined circle.
   */
  public static final String WHITE_CIRCLE = "○";

  /**
   * A filled circle.
   */
  public static final String BLACK_CIRCLE = "●";

  /**
   * A small outlined square.
   */
  public static final String SMALL_WHITE_SQUARE = "▫";

  /**
   * A small filled square.
   */
  public static final String SMALL_BLACK_SQUARE = "▪";

  private Characters() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }
}
