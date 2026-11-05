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
package me.brandonli.mcav.media.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import org.junit.jupiter.api.Test;

final class ImageDecodingExceptionTest {

  @Test
  void shouldKeepTheMessage() {
    final ImageDecodingException exception = new ImageDecodingException("Image cat.gif could not be decoded!");
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("Image cat.gif could not be decoded!", message);
    assertNull(cause);
  }

  @Test
  void shouldKeepTheMessageAndTheCause() {
    final IOException decodeFailure = new IOException("Unexpected end of file!");
    final ImageDecodingException exception = new ImageDecodingException("Image dog.webp could not be decoded!", decodeFailure);
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("Image dog.webp could not be decoded!", message);
    assertSame(decodeFailure, cause);
  }
}
