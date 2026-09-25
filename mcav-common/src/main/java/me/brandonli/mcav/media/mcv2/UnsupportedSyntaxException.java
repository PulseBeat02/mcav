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
 * Thrown for syntax the research codec defined but mcav deliberately does not implement: MCV1 frames, and the syntax
 * of the frontier rounds that were not kept (the coarse palette modes 21 and 22 of round 3, and the motion table flag
 * and indexed motion mode 23 of round 15).
 *
 * <p>The reference decoder accepts some of these, so this exception marks the only inputs on which the Java decoder
 * and the reference knowingly disagree.
 */
public final class UnsupportedSyntaxException extends Mcv2Exception {

  @Serial
  private static final long serialVersionUID = -2398461257093417610L;

  /**
   * Constructs a new exception.
   *
   * @param message which syntax was found
   */
  public UnsupportedSyntaxException(final String message) {
    super(message);
  }
}
