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
package me.brandonli.mcav.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.InvalidPathException;
import me.brandonli.mcav.MCAVException;
import org.junit.jupiter.api.Test;

final class ZipEntryIntegrityExceptionTest {

  @Test
  void shouldKeepTheMessage() {
    final ZipEntryIntegrityException exception = new ZipEntryIntegrityException("Total extracted size exceeds the limit!");
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("Total extracted size exceeds the limit!", message);
    assertNull(cause);
  }

  @Test
  void shouldKeepTheMessageAndTheCause() {
    final InvalidPathException invalidName = new InvalidPathException("entry", "Nul character not allowed");
    final ZipEntryIntegrityException exception = new ZipEntryIntegrityException("Entry is not a path of this system!", invalidName);
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("Entry is not a path of this system!", message);
    assertSame(invalidName, cause);
  }

  @Test
  void shouldExtendMCAVExceptionDirectlyRatherThanAnIoFailure() {
    final Class<ZipEntryIntegrityException> type = ZipEntryIntegrityException.class;
    final Class<? super ZipEntryIntegrityException> superclass = type.getSuperclass();
    assertEquals(MCAVException.class, superclass);
  }
}
