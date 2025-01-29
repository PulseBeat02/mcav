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
 * Represents a collection of characters that can be used in scoreboard, chat, or
 * entity displays.
 */
public interface Characters {
  /**
   * Unicode character representing a full block
   */
  String FULL_CHARACTER = "█";
  /**
   * Unicode character representing a white square
   */
  String WHITE_SQUARE = "□";
  /**
   * Unicode character representing a black square
   */
  String BLACK_SQUARE = "■";
  /**
   * Unicode character representing a white circle
   */
  String WHITE_CIRCLE = "○";
  /**
   * Unicode character representing a black circle
   */
  String BLACK_CIRCLE = "●";
  /**
   * Unicode character representing a small white square
   */
  String SMALL_WHITE_SQUARE = "▫";
  /**
   * Unicode character representing a small black square
   */
  String SMALL_BLACK_SQUARE = "▪";
}
