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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The keyframe requests of a channel; that none is lost to a frame taking them at the same time, jcstress checks. */
final class Mcv2KeyframeRequestTest {

  @Test
  void noFrameNeedsAKeyframeUntilOneIsRequested() {
    final Mcv2KeyframeRequest requests = new Mcv2KeyframeRequest();
    assertFalse(requests.take());
  }

  @Test
  void oneFrameTakesTheRequest() {
    final Mcv2KeyframeRequest requests = new Mcv2KeyframeRequest();
    requests.request();
    assertTrue(requests.take());
    assertFalse(requests.take(), "the frame after it needs no keyframe");
  }

  @Test
  void requestsBeforeAFrameAreTakenByThatFrame() {
    final Mcv2KeyframeRequest requests = new Mcv2KeyframeRequest();
    requests.request();
    requests.request();
    assertTrue(requests.take());
    assertFalse(requests.take());
  }
}
