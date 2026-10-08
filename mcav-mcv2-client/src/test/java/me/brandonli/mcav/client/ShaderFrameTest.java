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
package me.brandonli.mcav.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ShaderFrameTest {

  private static final byte[] PAGE = MapFixtures.colours(MapFixtures.pageSymbols(7, 0));

  private static final byte[] ANCHOR = MapFixtures.colours(MapFixtures.anchorSymbols(1, 0, 2, 1, 3, 7));

  private static final byte[] PICTURE = new byte[Mcv2Maps.COLOURS];

  @Test
  void keepsThePagesOfTheFrameAndTheAnchorsItPosed() {
    final ShaderFrame frame = new ShaderFrame();
    assertTrue(frame.isEmpty());
    frame.extracted("page", PAGE);
    frame.extracted("anchor", ANCHOR);
    frame.extracted("picture", PICTURE);
    assertEquals(1, frame.pages().size());
    assertSame(PAGE, frame.pages().get(0));
    assertFalse(frame.isEmpty());
    frame.posed("anchor", 1, 2, 3);
    frame.posed("picture", 4, 5, 6);
    frame.posed("page", 7, 8, 9);
    frame.posed("never extracted", 7, 8, 9);
    assertEquals(List.of(StripAnchor.of(ANCHOR, 1, 2, 3)), frame.anchors());
  }

  @Test
  void aFrameWithAnAnchorAloneIsNotEmpty() {
    final ShaderFrame frame = new ShaderFrame();
    frame.extracted("anchor", ANCHOR);
    assertTrue(frame.isEmpty(), "extracted, not drawn");
    frame.posed("anchor", 1, 2, 3);
    assertFalse(frame.isEmpty());
  }

  @Test
  void aMapThatTurnsIntoAPageOrAPictureIsNoAnchorAnyMore() {
    final ShaderFrame frame = new ShaderFrame();
    frame.extracted("map", ANCHOR);
    frame.extracted("map", PAGE);
    frame.posed("map", 1, 2, 3);
    frame.extracted("other", ANCHOR);
    frame.extracted("other", PICTURE);
    frame.posed("other", 1, 2, 3);
    assertTrue(frame.anchors().isEmpty());
  }

  @Test
  void theNextFrameStartsWithoutPagesAnchorsOrProjectionAndKnowsTheAnchorsStill() {
    final ShaderFrame frame = new ShaderFrame();
    assertNull(frame.projection());
    final float[] projection = new float[16];
    frame.projected(projection);
    assertSame(projection, frame.projection());
    frame.extracted("page", PAGE);
    frame.extracted("anchor", ANCHOR);
    frame.posed("anchor", 1, 2, 3);
    frame.finish();
    assertTrue(frame.isEmpty());
    assertTrue(frame.pages().isEmpty());
    assertNull(frame.projection());
    // the colours of an anchor stay known from frame to frame
    frame.posed("anchor", 4, 5, 6);
    assertArrayEquals(
      new float[] { 4, 5, 6 },
      new float[] { frame.anchors().get(0).cornerX(), frame.anchors().get(0).cornerY(), frame.anchors().get(0).cornerZ() }
    );
  }
}
