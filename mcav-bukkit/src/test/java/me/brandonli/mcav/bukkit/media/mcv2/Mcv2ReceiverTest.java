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
package me.brandonli.mcav.bukkit.media.mcv2;

import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees.motion;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees.solid;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

final class Mcv2ReceiverTest {

  private static final String STALE_FRAME_MESSAGE = "Stale or ambiguous frame number!";

  private byte[] keyframeWithId(final long id, final int gray) {
    return Mcv2Trees.write(4, 4, id, id, Mcv2FrameKind.KEYFRAME, List.of(solid(gray, gray, gray)));
  }

  @Test
  void shouldCommitNewerFramesOnly() {
    final Mcv2Receiver receiver = new Mcv2Receiver();
    final boolean noFrameYet = receiver.getFrameId().isEmpty();
    assertTrue(noFrameYet);

    receiver.accept(this.keyframeWithId(5, 1));
    final long acceptedFrameId = receiver.getFrameId().orElseThrow();
    assertEquals(5, acceptedFrameId);

    final Mcv2Exception exception = assertThrows(Mcv2Exception.class, () -> receiver.accept(this.keyframeWithId(5, 2)));
    final String message = exception.getMessage();
    assertEquals(STALE_FRAME_MESSAGE, message);
    assertThrows(Mcv2Exception.class, () -> receiver.accept(this.keyframeWithId(4, 2)));
    assertThrows(Mcv2Exception.class, () -> receiver.accept(this.keyframeWithId(5 + 0x80000000L, 2)));

    final long frameIdAfterRejections = receiver.getFrameId().orElseThrow();
    assertEquals(5, frameIdAfterRejections);
  }

  @Test
  void shouldRefuseNullFrameBytes() {
    final Mcv2Receiver receiver = new Mcv2Receiver();

    assertThrows(NullPointerException.class, () -> receiver.accept(null));
  }

  @Test
  void shouldStillRequireAFrameAfterFrameZeroToBeNewer() {
    final Mcv2Receiver receiver = new Mcv2Receiver();
    receiver.accept(this.keyframeWithId(0, 1));

    final Mcv2Exception exception = assertThrows(Mcv2Exception.class, () -> receiver.accept(this.keyframeWithId(0, 2)));
    final String message = exception.getMessage();
    assertEquals(STALE_FRAME_MESSAGE, message);

    final long frameId = receiver.getFrameId().orElseThrow();
    assertEquals(0, frameId);
  }

  @Test
  void shouldWrapFrameIdsAroundThirtyTwoBits() {
    final Mcv2Receiver receiver = new Mcv2Receiver();
    receiver.accept(this.keyframeWithId(0xFFFFFFFFL, 1));
    final byte[] picture = receiver.accept(this.keyframeWithId(0, 9));

    final byte[] expected = new byte[] { 9, 9, 9 };
    final byte[] firstPixel = Arrays.copyOf(picture, 3);
    assertArrayEquals(expected, firstPixel);

    final long frameId = receiver.getFrameId().orElseThrow();
    assertEquals(0, frameId);
  }

  @Test
  void shouldKeepItsStateWhenAFrameFails() {
    final Mcv2Receiver receiver = new Mcv2Receiver();
    receiver.accept(this.keyframeWithId(1, 3));
    final byte[] orphan = Mcv2Trees.write(4, 4, 2, 7, Mcv2FrameKind.PREDICTED, List.of(motion(0, 0)));
    assertThrows(Mcv2Exception.class, () -> receiver.accept(orphan));

    final long frameIdAfterOrphan = receiver.getFrameId().orElseThrow();
    assertEquals(1, frameIdAfterOrphan);

    final byte[] expected = new byte[4 * 4 * 3];
    Arrays.fill(expected, (byte) 3);
    final byte[] next = Mcv2Trees.write(4, 4, 2, 1, Mcv2FrameKind.PREDICTED, List.of(motion(0, 0)));
    final byte[] nextPicture = assertDoesNotThrow(() -> receiver.accept(next));
    assertArrayEquals(expected, nextPicture);

    final long frameIdAfterNext = receiver.getFrameId().orElseThrow();
    assertEquals(2, frameIdAfterNext);

    final byte[] anotherOrphan = Mcv2Trees.write(4, 4, 3, 8, Mcv2FrameKind.PREDICTED, List.of(motion(0, 0)));
    assertThrows(Mcv2Exception.class, () -> receiver.accept(anotherOrphan));

    final byte[] retry = Mcv2Trees.write(4, 4, 3, 2, Mcv2FrameKind.PREDICTED, List.of(motion(0, 0)));
    final byte[] retryPicture = assertDoesNotThrow(() -> receiver.accept(retry));
    assertArrayEquals(expected, retryPicture);

    final long frameIdAfterRetry = receiver.getFrameId().orElseThrow();
    assertEquals(3, frameIdAfterRetry);
  }

  @Test
  void shouldRejectAReferenceWithTheSamePixelCountButDifferentDimensions() {
    final Mcv2Receiver receiver = new Mcv2Receiver();
    receiver.accept(Mcv2Trees.write(8, 16, 0, 0, Mcv2FrameKind.KEYFRAME, List.of(solid(1, 2, 3))));
    final byte[] wrongShape = Mcv2Trees.write(16, 8, 1, 0, Mcv2FrameKind.PREDICTED, List.of(motion(0, 0)));
    final Mcv2Exception shapeException = assertThrows(Mcv2Exception.class, () -> receiver.accept(wrongShape));
    final String shapeMessage = shapeException.getMessage();
    assertEquals("Reference frame dimensions mismatch!", shapeMessage);

    final long frameIdAfterWrongShape = receiver.getFrameId().orElseThrow();
    assertEquals(0, frameIdAfterWrongShape);

    final byte[] wrongHeight = Mcv2Trees.write(8, 8, 1, 0, Mcv2FrameKind.PREDICTED, List.of(motion(0, 0)));
    final Mcv2Exception heightException = assertThrows(Mcv2Exception.class, () -> receiver.accept(wrongHeight));
    final String heightMessage = heightException.getMessage();
    assertEquals("Reference frame dimensions mismatch!", heightMessage);

    final long frameIdAfterWrongHeight = receiver.getFrameId().orElseThrow();
    assertEquals(0, frameIdAfterWrongHeight);

    assertDoesNotThrow(() -> receiver.accept(Mcv2Trees.write(8, 16, 1, 0, Mcv2FrameKind.PREDICTED, List.of(motion(0, 0)))));
  }
}
