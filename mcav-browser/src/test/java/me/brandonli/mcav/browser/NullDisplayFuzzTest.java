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
package me.brandonli.mcav.browser;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Tag;

/**
 * Fuzzes the null display, which answers X11 connections on the loopback interface: a client with the cookie, such as
 * a renderer of the page, is untrusted. Whatever the bytes after a valid setup, the display answers or ends the
 * connection with an {@link IOException}, and it never answers with more than a bounded number of bytes per request.
 */
@Tag("fuzz")
final class NullDisplayFuzzTest {

  private static final byte[] COOKIE = { 9, 8, 7, 6, 5, 4, 3, 2, 1, 0, 1, 2, 3, 4, 5, 6 };

  @FuzzTest(maxDuration = "30s")
  void everyRequestIsAnsweredOrEndsTheConnection(final byte[] input) {
    final byte[] setup = NullDisplayTest.setup(ByteOrder.LITTLE_ENDIAN, 11, "MIT-MAGIC-COOKIE-1", COOKIE);
    final ByteArrayOutputStream stream = new ByteArrayOutputStream();
    stream.writeBytes(setup);
    stream.writeBytes(input);
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    try {
      NullDisplay.serve(new ByteArrayInputStream(stream.toByteArray()), output, COOKIE, new NullDisplay.Atoms());
    } catch (final IOException ended) {
      // a request the display does not accept ends the connection
    }
    // the largest answer is a keyboard mapping of 255 key codes, 32 + 4 * 255 bytes, for a request of four bytes
    final long bound = 1_000L + (input.length / 4 + 1) * (32L + 4 * 255);
    assertTrue(output.size() <= bound, output.size() + " bytes answered to " + input.length);
  }

  @FuzzTest(maxDuration = "30s")
  void everySetupIsAcceptedOrRefused(final byte[] input) {
    try {
      NullDisplay.serve(new ByteArrayInputStream(input), new ByteArrayOutputStream(), COOKIE, new NullDisplay.Atoms());
    } catch (final IOException refused) {
      // not a setup with the cookie
    }
  }
}
