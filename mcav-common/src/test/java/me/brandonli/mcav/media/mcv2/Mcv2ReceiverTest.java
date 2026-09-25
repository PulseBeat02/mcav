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
package me.brandonli.mcav.media.mcv2;

import static me.brandonli.mcav.media.mcv2.Mcv2Frames.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import me.brandonli.mcav.media.mcv2.encode.FrameWriter;
import org.junit.jupiter.api.Test;

/** The receiver's state machine: commit only newer frames, keep the state on every failure, and wrap ids. */
final class Mcv2ReceiverTest {

  private static byte[] keyframeWithId(final long id, final int gray) {
    return FrameWriter.write(4, 4, id, id, true, 0, 0, List.of(solid(gray, gray, gray)), DERIVED);
  }

  @Test
  void commitsNewerFramesOnly() throws Mcv2Exception {
    final Mcv2Receiver receiver = new Mcv2Receiver();
    assertEquals(-1, receiver.getFrameId());
    receiver.accept(keyframeWithId(5, 1));
    assertEquals(5, receiver.getFrameId());
    assertEquals(
      "Stale or ambiguous frame number",
      assertThrows(Mcv2Exception.class, () -> receiver.accept(keyframeWithId(5, 2))).getMessage()
    );
    assertThrows(Mcv2Exception.class, () -> receiver.accept(keyframeWithId(4, 2)));
    assertThrows(Mcv2Exception.class, () -> receiver.accept(keyframeWithId(5 + 0x80000000L, 2)));
    assertEquals(5, receiver.getFrameId());
  }

  @Test
  void wrapsFrameIdsAroundThirtyTwoBits() throws Mcv2Exception {
    final Mcv2Receiver receiver = new Mcv2Receiver();
    receiver.accept(keyframeWithId(0xFFFFFFFFL, 1));
    final byte[] picture = receiver.accept(keyframeWithId(0, 9));
    assertArrayEquals(new byte[] { 9, 9, 9 }, java.util.Arrays.copyOf(picture, 3));
    assertEquals(0, receiver.getFrameId());
  }

  @Test
  void keepsItsStateWhenAFrameFails() throws Mcv2Exception {
    final Mcv2Receiver receiver = new Mcv2Receiver();
    receiver.accept(keyframeWithId(1, 3));
    final byte[] orphan = FrameWriter.write(4, 4, 2, 7, false, 0, 0, List.of(motion(0, 0)), SHORT);
    assertThrows(Mcv2Exception.class, () -> receiver.accept(orphan));
    assertEquals(1, receiver.getFrameId());
  }
}
