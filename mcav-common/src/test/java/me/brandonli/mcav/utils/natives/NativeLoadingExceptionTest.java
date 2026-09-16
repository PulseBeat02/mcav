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
package me.brandonli.mcav.utils.natives;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link NativeLoadingException}.
 */
final class NativeLoadingExceptionTest {

  @Test
  void keepsTheMessage() {
    final NativeLoadingException exception = new NativeLoadingException("no natives for this platform");
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("no natives for this platform", message);
    assertNull(cause);
  }

  @Test
  void keepsTheMessageAndTheCause() {
    final UnsatisfiedLinkError linkError = new UnsatisfiedLinkError("no jniavcodec");
    final NativeLoadingException exception = new NativeLoadingException("FFmpeg could not be loaded", linkError);
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("FFmpeg could not be loaded", message);
    assertSame(linkError, cause);
  }

  @Test
  void isAnIllegalStateException() {
    final NativeLoadingException exception = new NativeLoadingException("failure");
    assertInstanceOf(IllegalStateException.class, exception, "missing natives are an environment state, not an Error");
  }
}
