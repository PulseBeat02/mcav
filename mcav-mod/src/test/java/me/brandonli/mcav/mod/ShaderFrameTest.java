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
package me.brandonli.mcav.mod;

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

  private static final String PAGE_TEXTURE = "page";
  private static final String ANCHOR_TEXTURE = "anchor";

  @Test
  void shouldKeepThePagesOfTheFrameAndTheAnchorsItPosed() {
    final ShaderFrame frame = new ShaderFrame();
    final boolean emptyAtStart = frame.isEmpty();
    assertTrue(emptyAtStart);
    frame.extracted(PAGE_TEXTURE, PAGE);
    frame.extracted(ANCHOR_TEXTURE, ANCHOR);
    frame.extracted("picture", PICTURE);
    final int pageCount = frame.pages().size();
    final byte[] firstPage = frame.pages().get(0);
    final boolean emptyWithAPage = frame.isEmpty();
    assertEquals(1, pageCount);
    assertSame(PAGE, firstPage);
    assertFalse(emptyWithAPage);
    frame.posed(ANCHOR_TEXTURE, 1, 2, 3);
    frame.posed("picture", 4, 5, 6);
    frame.posed(PAGE_TEXTURE, 7, 8, 9);
    frame.posed("never extracted", 7, 8, 9);
    final List<StripAnchor> anchors = frame.anchors();
    assertEquals(List.of(StripAnchor.of(ANCHOR, 1, 2, 3)), anchors);
  }

  @Test
  void shouldNotBeEmptyWithAnAnchorAlone() {
    final ShaderFrame frame = new ShaderFrame();
    frame.extracted(ANCHOR_TEXTURE, ANCHOR);
    final boolean emptyBeforeDrawing = frame.isEmpty();
    assertTrue(emptyBeforeDrawing, "extracted, not drawn");
    frame.posed(ANCHOR_TEXTURE, 1, 2, 3);
    final boolean emptyOnceDrawn = frame.isEmpty();
    assertFalse(emptyOnceDrawn);
  }

  @Test
  void shouldForgetTheAnchorOfAMapThatTurnsIntoAPageOrAPicture() {
    final ShaderFrame frame = new ShaderFrame();
    frame.extracted("map", ANCHOR);
    frame.extracted("map", PAGE);
    frame.posed("map", 1, 2, 3);
    frame.extracted("other", ANCHOR);
    frame.extracted("other", PICTURE);
    frame.posed("other", 1, 2, 3);
    final List<StripAnchor> anchors = frame.anchors();
    assertTrue(anchors.isEmpty());
  }

  @Test
  void shouldStartTheNextFrameWithoutPagesAnchorsOrProjectionAndStillKnowTheAnchors() {
    final ShaderFrame frame = new ShaderFrame();
    final float[] projectionAtStart = frame.projection();
    assertNull(projectionAtStart);
    final float[] projection = new float[16];
    frame.projected(projection);
    final float[] markedProjection = frame.projection();
    assertSame(projection, markedProjection);
    frame.extracted(PAGE_TEXTURE, PAGE);
    frame.extracted(ANCHOR_TEXTURE, ANCHOR);
    frame.posed(ANCHOR_TEXTURE, 1, 2, 3);
    frame.finish();
    final boolean emptyNextFrame = frame.isEmpty();
    final List<byte[]> nextPages = frame.pages();
    final float[] nextProjection = frame.projection();
    assertTrue(emptyNextFrame);
    assertTrue(nextPages.isEmpty());
    assertNull(nextProjection);
    frame.posed(ANCHOR_TEXTURE, 4, 5, 6);
    final List<StripAnchor> anchors = frame.anchors();
    final StripAnchor anchor = anchors.get(0);
    final float[] corner = { anchor.getCornerX(), anchor.getCornerY(), anchor.getCornerZ() };
    assertArrayEquals(new float[] { 4, 5, 6 }, corner);
  }
}
