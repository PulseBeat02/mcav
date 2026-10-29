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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;

final class HttpStatusExceptionTest {

  @Test
  void shouldDescribeTheStatusAndTheUri() {
    final URI uri = URI.create("https://example.com/file");
    final HttpStatusException exception = new HttpStatusException(503, uri);
    final int statusCode = exception.getStatusCode();
    final String message = exception.getMessage();
    assertEquals(503, statusCode);
    assertEquals("Request for https://example.com/file got status 503, which is not a success!", message);
    assertThrows(NullPointerException.class, this::throwStatusExceptionWithoutUri);
  }

  private void throwStatusExceptionWithoutUri() throws HttpStatusException {
    throw new HttpStatusException(500, null);
  }
}
