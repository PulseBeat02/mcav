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
package me.brandonli.mcav.client;

/** What Iris says of its shader pack, as a report carries it. */
enum ShaderPack {
  /** No shader pack is in use, or Iris is not installed. */
  NONE(0),
  /** A shader pack is in use. */
  IN_USE(1),
  /** Iris lacks the API the mod asks, so it cannot be known. */
  UNKNOWN(2);

  private final byte code;

  ShaderPack(final int code) {
    this.code = (byte) code;
  }

  /** The byte of a report that says this. */
  byte code() {
    return this.code;
  }
}
