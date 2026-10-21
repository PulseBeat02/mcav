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
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Tag;

// A helper runs web content, so the server treats what it sends as untrusted. The fuzz target never hands out more
// pixel bytes than the input still holds, so a claimed size cannot make it allocate more.
@Tag("fuzz")
final class HelperProtocolFuzzTest {

  @FuzzTest(maxDuration = "30s")
  void shouldReadOnlyMessagesTheProtocolAllows(final FuzzedDataProvider data) {
    final byte[] bytes = data.consumeRemainingAsBytes();
    final DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
    try {
      while (true) {
        final HelperMessage message = HelperProtocol.read(input, size -> this.allocatePixels(bytes, size));
        this.check(message);
      }
    } catch (final IOException | CutShort exception) {
      // malformed or cut short: the server ends the session
    }
  }

  private byte[] allocatePixels(final byte[] bytes, final int size) {
    if (size > bytes.length) {
      throw new CutShort();
    }

    return new byte[size];
  }

  private void check(final HelperMessage message) {
    final int type = message.getType();
    switch (type) {
      case HelperProtocol.FRAME -> {
        final FrameRegion region = message.getRegion();
        final boolean fitsHorizontally = region.getX() + region.getWidth() <= region.getPageWidth();
        assertTrue(fitsHorizontally, "the region fits the page horizontally");
        final boolean fitsVertically = region.getY() + region.getHeight() <= region.getPageHeight();
        assertTrue(fitsVertically, "the region fits the page vertically");
        final boolean sizesInRange = region.getWidth() >= 1 && region.getPageWidth() <= HelperProtocol.MAXIMUM_SIDE;
        assertTrue(sizesInRange, "the sizes are in range");
        final boolean regionHeightInRange = region.getHeight() >= 1 && region.getHeight() <= HelperProtocol.MAXIMUM_SIDE;
        assertTrue(regionHeightInRange, "the region height is in range");
        final boolean pageHeightInRange = region.getPageHeight() >= 1 && region.getPageHeight() <= HelperProtocol.MAXIMUM_SIDE;
        assertTrue(pageHeightInRange, "the page height is in range");
        final boolean widthsInRange = region.getPageWidth() >= 1 && region.getWidth() <= HelperProtocol.MAXIMUM_SIDE;
        assertTrue(widthsInRange, "the widths are in range");
      }
      case HelperProtocol.AUDIO -> {
        final int length = message.getSamples().length;
        final boolean soundWithinLimit = length > 0 && length <= HelperProtocol.MAXIMUM_AUDIO_BYTES;
        assertTrue(soundWithinLimit, "the sound is within its limit");
        final boolean wholeFrames = length % HelperProtocol.AUDIO_FRAME_BYTES == 0;
        assertTrue(wholeFrames, "the sound holds whole frames");
      }
      case HelperProtocol.READY, HelperProtocol.NOTICE, HelperProtocol.FAILURE, HelperProtocol.KEY -> {
        final int length = message.getText().getBytes(StandardCharsets.UTF_8).length;
        final boolean textWithinLimit = length <= HelperProtocol.MAXIMUM_TEXT_BYTES;
        assertTrue(textWithinLimit, "the text is within its limit");
      }
      default -> {
        final boolean knownType = type >= HelperProtocol.HELLO && type <= HelperProtocol.CLOSE;
        assertTrue(knownType, () -> "type " + type);
        final boolean definedType = Set.of(1, 4, 5, 16, 18).contains(type);
        assertTrue(definedType, () -> "undefined message type " + type);
      }
    }
  }

  private static final class CutShort extends RuntimeException {

    private static final long serialVersionUID = 1L;

    CutShort() {
      super(null, null, false, false);
    }
  }
}
