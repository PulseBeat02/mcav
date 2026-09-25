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
 * Fuzzes the reader of the helper protocol, which the server runs on everything a helper sends. A helper runs web
 * content, so the server treats it as untrusted: whatever the bytes, every message read is one the protocol allows,
 * with its region inside its page and its texts within their limit, and anything else ends in an {@link IOException}.
 * The fuzz target never hands out more pixel bytes than the input still holds, so a claimed size cannot make it
 * allocate more than the input is long.
 */
@Tag("fuzz")
final class HelperProtocolFuzzTest {

  @FuzzTest(maxDuration = "30s")
  void readsOnlyMessagesTheProtocolAllows(final FuzzedDataProvider data) {
    final byte[] bytes = data.consumeRemainingAsBytes();
    final DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
    try {
      while (true) {
        final HelperMessage message = HelperProtocol.read(in, size -> {
          if (size > bytes.length) {
            throw new CutShort();
          }
          return new byte[size];
        });
        check(message);
      }
    } catch (final IOException | CutShort refused) {
      // malformed or cut short: the server ends the session
    }
  }

  private static void check(final HelperMessage message) {
    final int type = message.getType();
    switch (type) {
      case HelperProtocol.FRAME -> {
        final FrameRegion region = message.getRegion();
        assertTrue(region.getX() + region.getWidth() <= region.getPageWidth(), "the region fits the page horizontally");
        assertTrue(region.getY() + region.getHeight() <= region.getPageHeight(), "the region fits the page vertically");
        assertTrue(region.getWidth() >= 1 && region.getPageWidth() <= HelperProtocol.MAX_SIDE, "the sizes are in range");
      }
      case HelperProtocol.READY, HelperProtocol.NOTICE, HelperProtocol.FAILURE, HelperProtocol.KEY -> {
        final int length = message.getText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue(length <= HelperProtocol.MAX_TEXT_BYTES, "the text is within its limit");
      }
      default -> assertTrue(type >= HelperProtocol.HELLO && type <= HelperProtocol.CLOSE, () -> "type " + type);
    }
  }

  /**
   * Ends a read that claims more pixels than the input holds.
   */
  private static final class CutShort extends RuntimeException {

    private static final long serialVersionUID = 1L;

    CutShort() {
      super(null, null, false, false);
    }
  }
}
