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

import com.google.common.base.Preconditions;
import java.io.Serial;
import java.net.URI;

/**
 * Thrown when a server answers a request with a status code outside of the {@code 2xx} range.
 *
 * @since 1.0.0
 */
public final class HttpStatusException extends DownloadException {

  @Serial
  private static final long serialVersionUID = 4_319_027_551_202_837_465L;

  // documented because the serialized form of the exception publishes the field
  /**
   * The status code the server answered with, such as {@code 404}.
   */
  private final int statusCode;

  /**
   * Constructs a new exception.
   *
   * @param statusCode the status code the server answered with
   * @param uri        the URI that was requested
   * @throws NullPointerException if {@code uri} is null
   * @since 1.0.0
   */
  public HttpStatusException(final int statusCode, final URI uri) {
    Preconditions.checkNotNull(uri, "URI must not be null!");

    final String message = "Request for %s got status %s, which is not a success!".formatted(uri, statusCode);
    super(message);
    this.statusCode = statusCode;
  }

  /**
   * Returns the status code the server answered with.
   *
   * @return the status code
   * @since 1.0.0
   */
  public int getStatusCode() {
    return this.statusCode;
  }
}
