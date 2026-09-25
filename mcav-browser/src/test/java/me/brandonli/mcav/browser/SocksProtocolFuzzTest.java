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

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import org.junit.jupiter.api.Tag;

/**
 * Fuzzes the SOCKS 5 reader of the network guard, which reads what Chromium sends on behalf of a page: the host name
 * comes from the page. Whatever the bytes, the reader either refuses them with an {@link IOException} or returns a
 * request whose host holds only host name characters or an address and whose port is valid; nothing else escapes.
 */
@Tag("fuzz")
final class SocksProtocolFuzzTest {

  @FuzzTest(maxDuration = "30s")
  void readsAGreetingAndARequestOrRefusesThem(final FuzzedDataProvider data) {
    final byte[] bytes = data.consumeRemainingAsBytes();
    final DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
    try {
      SocksProtocol.readGreeting(in);
      final SocksProtocol.Request request = SocksProtocol.readRequest(in);
      final String host = request.getHost();
      final int port = request.getPort();
      assertTrue(port >= 1 && port <= 65_535, () -> "port " + port);
      assertTrue(!host.isEmpty() && host.length() <= 255, () -> "host of " + host.length() + " characters");
      for (int index = 0; index < host.length(); index++) {
        final char character = host.charAt(index);
        final boolean allowed = character < 128 && (SocksProtocol.isHostNameCharacter((byte) character) || character == ':');
        assertTrue(allowed, () -> "host " + host);
      }
    } catch (final IOException refused) {
      // malformed, cut short, or not served: the guard answers or drops the client
    }
  }
}
