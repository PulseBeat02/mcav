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
 * Thrown when a download is larger than the caller allows. The download is stopped as soon as the limit is passed, so
 * a server that never stops sending cannot fill the disk of its client. Retrying would download the same bytes again,
 * so this failure is not retried.
 */
public class DownloadTooLargeException extends IOException {

  @Serial
  private static final long serialVersionUID = 4_268_314_709_812_553_311L;

  /**
   * Constructs the exception.
   *
   * @param message the detail message, which names the limit
   */
  public DownloadTooLargeException(final String message) {
    super(message);
  }
}
