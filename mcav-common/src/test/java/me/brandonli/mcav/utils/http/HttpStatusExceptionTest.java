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

/**
 * Tests {@link HttpStatusException} and {@link ChecksumMismatchException}.
 */
final class HttpStatusExceptionTest {

  @Test
  void describesTheStatusAndTheUri() {
    final URI uri = URI.create("https://example.com/file");
    final HttpStatusException exception = new HttpStatusException(503, uri);
    final int statusCode = exception.getStatusCode();
    final String message = exception.getMessage();
    assertEquals(503, statusCode);
    assertEquals("Server answered with status 503 for https://example.com/file", message);
    assertThrows(NullPointerException.class, () -> {
      throw new HttpStatusException(500, null);
    });
  }

  @Test
  void checksumMismatchKeepsItsMessage() {
    final ChecksumMismatchException exception = new ChecksumMismatchException("expected a but got b");
    final String message = exception.getMessage();
    assertEquals("expected a but got b", message);
  }
}
