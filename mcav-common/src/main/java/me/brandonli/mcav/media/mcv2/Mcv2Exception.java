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
package me.brandonli.mcav.media.mcv2;

import java.io.Serial;

/**
 * Thrown when bytes are not a valid MCV2 frame, transport page or archive, or when a frame cannot be decoded against
 * the reference it names.
 *
 * <p>This is the only exception the MCV2 parsers and decoders throw for bad input. They treat every byte as hostile:
 * whatever the bytes are, parsing either succeeds or throws this exception, never an unchecked exception or an
 * out-of-bounds read.
 */
public class Mcv2Exception extends Exception {

  @Serial
  private static final long serialVersionUID = 4270539862718624891L;

  /**
   * Constructs a new exception.
   *
   * @param message what is wrong with the input
   */
  public Mcv2Exception(final String message) {
    super(message);
  }
}
