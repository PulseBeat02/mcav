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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BrowserOptionsTest {

  @Test
  void theDefaultsSuitUntrustedPages() {
    assertEquals(BrowserOptions.MAX_FRAME_RATE, BrowserOptions.DEFAULT.getFrameRate());
    assertEquals(60, BrowserOptions.MAX_FRAME_RATE);
    assertFalse(BrowserOptions.DEFAULT.isJavaScriptJit());
    assertFalse(BrowserOptions.DEFAULT.isPrivateNetworks());
    assertFalse(BrowserOptions.DEFAULT.isAutoplay());
  }

  @Test
  void theBuilderSetsEveryOption() {
    final BrowserOptions options = BrowserOptions.builder().frameRate(1).javaScriptJit(true).privateNetworks(true).autoplay(true).build();
    assertEquals(1, options.getFrameRate());
    assertTrue(options.isJavaScriptJit());
    assertTrue(options.isPrivateNetworks());
    assertTrue(options.isAutoplay());
    assertEquals(60, BrowserOptions.builder().frameRate(60).build().getFrameRate());
  }

  @Test
  void theFrameRateIsBetweenOneAndSixty() {
    final BrowserOptions.Builder builder = BrowserOptions.builder();
    assertThrows(IllegalArgumentException.class, () -> builder.frameRate(0));
    final IllegalArgumentException tooFast = assertThrows(IllegalArgumentException.class, () -> builder.frameRate(61));
    assertEquals("Frame rate must be between 1 and 60 but was 61", tooFast.getMessage());
  }
}
