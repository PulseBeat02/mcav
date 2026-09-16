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
package me.brandonli.mcav.capability.installer.vlc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link UnsupportedOperatingSystemException}.
 */
final class UnsupportedOperatingSystemExceptionTest {

  @Test
  void keepsTheMessage() {
    final UnsupportedOperatingSystemException exception = new UnsupportedOperatingSystemException(
      "VLC cannot be installed automatically on FREEBSD"
    );
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("VLC cannot be installed automatically on FREEBSD", message);
    assertNull(cause);
  }

  @Test
  void isAnUnsupportedOperationException() {
    final UnsupportedOperatingSystemException exception = VlcInstallerFailures.unsupportedSystem("failure");
    assertInstanceOf(UnsupportedOperationException.class, exception, "an unsupported system is recoverable, so it is no Error");
  }
}
