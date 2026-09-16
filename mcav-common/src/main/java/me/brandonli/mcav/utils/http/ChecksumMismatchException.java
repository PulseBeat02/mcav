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
package me.brandonli.mcav.utils.http;

import java.io.IOException;
import java.io.Serial;

/**
 * Thrown when a downloaded file does not have the expected SHA-256 hash. Downloading the same file again would
 * give the same result, so a mismatch is never retried.
 */
public class ChecksumMismatchException extends IOException {

  @Serial
  private static final long serialVersionUID = -6_142_310_774_902_315_786L;

  /**
   * Constructs a new exception.
   *
   * @param message the detail message, naming the file and both hashes
   */
  public ChecksumMismatchException(final String message) {
    super(message);
  }
}
