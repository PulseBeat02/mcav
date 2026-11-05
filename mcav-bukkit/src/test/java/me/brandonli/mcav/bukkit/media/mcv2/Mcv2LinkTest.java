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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class Mcv2LinkTest {

  private static final int FRAME_BYTES = 60;

  private static final int LINK_LIMIT = 1000;

  @Test
  void shouldStartWithAKeyframe() {
    final Mcv2Link link = new Mcv2Link(LINK_LIMIT);
    final boolean decodesBeforeKeyframe = link.canDecode(Mcv2FrameKind.PREDICTED, 0);
    assertFalse(decodesBeforeKeyframe);
    // the id no frame has is never a reference, not even before the first frame
    final boolean decodesFromNoFrame = link.canDecode(Mcv2FrameKind.PREDICTED, -1);
    assertFalse(decodesFromNoFrame);

    final boolean sentPredicted = link.offer(5, 4, Mcv2FrameKind.PREDICTED, 10);
    assertFalse(sentPredicted);

    final long undecodable = link.getUndecodable();
    assertEquals(1, undecodable);

    final boolean sentKeyframe = link.offer(6, 6, Mcv2FrameKind.KEYFRAME, 10);
    assertTrue(sentKeyframe);

    final boolean sentNext = link.offer(7, 6, Mcv2FrameKind.PREDICTED, 10);
    assertTrue(sentNext);

    final long sent = link.getSent();
    assertEquals(2, sent);

    final long backlog = link.getBacklog();
    assertEquals(20, backlog);
  }

  @Test
  void shouldWaitForTheNextKeyframeWhenFramesPredictFromTheFrameBefore() {
    final Mcv2Link link = new Mcv2Link(100);
    final boolean sentKeyframe = link.offer(0, 0, Mcv2FrameKind.KEYFRAME, FRAME_BYTES);
    assertTrue(sentKeyframe);

    final boolean sentPredicted = link.offer(1, 0, Mcv2FrameKind.PREDICTED, FRAME_BYTES);
    assertTrue(sentPredicted);
    // 120 bytes not yet written: frame 2 is held back, and 3 predicts from 2, which the viewer lacks
    final boolean sentHeldBack = link.offer(2, 1, Mcv2FrameKind.PREDICTED, FRAME_BYTES);
    assertFalse(sentHeldBack);

    link.releaseBacklog(120);
    final boolean sentFromMissing = link.offer(3, 2, Mcv2FrameKind.PREDICTED, FRAME_BYTES);
    assertFalse(sentFromMissing);

    final boolean sentFromUndecodable = link.offer(4, 3, Mcv2FrameKind.PREDICTED, FRAME_BYTES);
    assertFalse(sentFromUndecodable);

    final boolean sentNextKeyframe = link.offer(5, 5, Mcv2FrameKind.KEYFRAME, FRAME_BYTES);
    assertTrue(sentNextKeyframe);

    final boolean sentAfterKeyframe = link.offer(6, 5, Mcv2FrameKind.PREDICTED, FRAME_BYTES);
    assertTrue(sentAfterKeyframe);

    final long behind = link.getBehind();
    assertEquals(1, behind);

    final long undecodable = link.getUndecodable();
    assertEquals(2, undecodable);

    final long sent = link.getSent();
    assertEquals(4, sent);
  }

  @Test
  void shouldRefuseAnOlderKeyframeAfterSendingAPredictedFrame() {
    final Mcv2Link link = new Mcv2Link(LINK_LIMIT);
    final boolean sentKeyframe = link.offer(10, 10, Mcv2FrameKind.KEYFRAME, 1);
    assertTrue(sentKeyframe);

    final boolean sentPredicted = link.offer(11, 10, Mcv2FrameKind.PREDICTED, 1);
    assertTrue(sentPredicted);

    final boolean decodesFromKeyframe = link.canDecode(Mcv2FrameKind.PREDICTED, 10);
    assertFalse(decodesFromKeyframe);

    final boolean sentFromKeyframe = link.offer(12, 10, Mcv2FrameKind.PREDICTED, 1);
    assertFalse(sentFromKeyframe);

    final boolean decodesFromPredicted = link.canDecode(Mcv2FrameKind.PREDICTED, 11);
    assertTrue(decodesFromPredicted);

    final boolean sentFromPredicted = link.offer(13, 11, Mcv2FrameKind.PREDICTED, 1);
    assertTrue(sentFromPredicted);

    final long undecodable = link.getUndecodable();
    assertEquals(1, undecodable);

    final long sent = link.getSent();
    assertEquals(3, sent);
  }

  @Test
  void shouldSendAtTheLimitAndHoldPastIt() {
    final Mcv2Link link = new Mcv2Link(50);
    final boolean sentKeyframe = link.offer(0, 0, Mcv2FrameKind.KEYFRAME, 50);
    assertTrue(sentKeyframe);

    final boolean sentAtTheLimit = link.offer(1, 0, Mcv2FrameKind.PREDICTED, 1);
    assertTrue(sentAtTheLimit);

    final boolean sentPastTheLimit = link.offer(2, 1, Mcv2FrameKind.PREDICTED, 1);
    assertFalse(sentPastTheLimit);
    // a keyframe still goes out up to twice the limit, the P frames after it only under the limit again
    final boolean sentLateKeyframe = link.offer(3, 3, Mcv2FrameKind.KEYFRAME, FRAME_BYTES);
    assertTrue(sentLateKeyframe);

    final boolean sentAfterLateKeyframe = link.offer(4, 3, Mcv2FrameKind.PREDICTED, 1);
    assertFalse(sentAfterLateKeyframe);

    final boolean sentPastTwiceTheLimit = link.offer(5, 5, Mcv2FrameKind.KEYFRAME, 1);
    assertFalse(sentPastTwiceTheLimit);

    link.releaseBacklog(62);
    final boolean sentOnceWritten = link.offer(6, 3, Mcv2FrameKind.PREDICTED, 1);
    assertTrue(sentOnceWritten);

    final long behind = link.getBehind();
    assertEquals(3, behind);

    final Mcv2Link none = new Mcv2Link(0);
    final boolean sentWithoutLimitKeyframe = none.offer(0, 0, Mcv2FrameKind.KEYFRAME, 5);
    assertTrue(sentWithoutLimitKeyframe);

    final boolean sentWithoutLimitPredicted = none.offer(1, 0, Mcv2FrameKind.PREDICTED, 5);
    assertFalse(sentWithoutLimitPredicted);

    final boolean sentWithoutLimitNextKeyframe = none.offer(2, 2, Mcv2FrameKind.KEYFRAME, 5);
    assertFalse(sentWithoutLimitNextKeyframe);

    final long largestAllowance = Mcv2Link.allowance(Long.MAX_VALUE);
    assertEquals(Long.MAX_VALUE, largestAllowance);

    final long saturatedAllowance = Mcv2Link.allowance(Long.MAX_VALUE / 2 + 1);
    assertEquals(Long.MAX_VALUE, saturatedAllowance);

    final long expectedDoubled = Long.MAX_VALUE - 1;
    final long largestDoubledAllowance = Mcv2Link.allowance(Long.MAX_VALUE / 2);
    assertEquals(expectedDoubled, largestDoubledAllowance);

    final long doubledAllowance = Mcv2Link.allowance(50);
    assertEquals(100, doubledAllowance);
  }

  @Test
  void shouldRefuseValuesOutOfRange() {
    assertThrows(IllegalArgumentException.class, () -> new Mcv2Link(-1));

    final Mcv2Link link = new Mcv2Link(10);
    assertThrows(IllegalArgumentException.class, () -> link.offer(-1, 0, Mcv2FrameKind.KEYFRAME, 1));
    assertThrows(IllegalArgumentException.class, () -> link.offer(1L << 32, 0, Mcv2FrameKind.KEYFRAME, 1));
    assertThrows(IllegalArgumentException.class, () -> link.offer(0, 0, Mcv2FrameKind.KEYFRAME, -1));

    final boolean sentLastId = link.offer(0xFFFFFFFFL, 0xFFFFFFFFL, Mcv2FrameKind.KEYFRAME, 1);
    assertTrue(sentLastId);

    final boolean decodesFromLastId = link.canDecode(Mcv2FrameKind.PREDICTED, 0xFFFFFFFFL);
    assertTrue(decodesFromLastId);
  }
}
