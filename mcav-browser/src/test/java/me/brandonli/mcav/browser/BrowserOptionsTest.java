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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.brandonli.mcav.browser.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;

class BrowserOptionsTest {

  private static final int MAXIMUM_FRAME_RATE = 60;

  @Test
  void shouldSuitUntrustedPagesByDefault() {
    final int frameRate = BrowserOptions.DEFAULT.getFrameRate();
    assertEquals(BrowserOptions.MAXIMUM_FRAME_RATE, frameRate);
    assertEquals(MAXIMUM_FRAME_RATE, BrowserOptions.MAXIMUM_FRAME_RATE);
    final boolean javaScriptJit = BrowserOptions.DEFAULT.isJavaScriptJit();
    assertFalse(javaScriptJit);
    final boolean privateNetworks = BrowserOptions.DEFAULT.isPrivateNetworks();
    assertFalse(privateNetworks);
    final boolean autoplay = BrowserOptions.DEFAULT.isAutoplay();
    assertFalse(autoplay);
    final boolean confined = BrowserOptions.DEFAULT.isConfined();
    assertTrue(confined, "Chromium is confined where the system can");
    final List<String> refusedHosts = BrowserOptions.DEFAULT.getRefusedHosts();
    assertEquals(List.of(), refusedHosts);
  }

  @Test
  void shouldSetEveryOptionThroughTheBuilder() {
    final BrowserOptions options = BrowserOptions.builder()
      .frameRate(1)
      .javaScriptJit(true)
      .privateNetworks(true)
      .autoplay(true)
      .confinement(false)
      .refusedHosts(List.of("mc.example.com", "203.0.113.5"))
      .build();
    final int minimumFrameRate = options.getFrameRate();
    assertEquals(1, minimumFrameRate);
    final boolean javaScriptJit = options.isJavaScriptJit();
    assertTrue(javaScriptJit);
    final boolean privateNetworks = options.isPrivateNetworks();
    assertTrue(privateNetworks);
    final boolean autoplay = options.isAutoplay();
    assertTrue(autoplay);
    final boolean confined = options.isConfined();
    assertFalse(confined);
    final List<String> refusedHosts = options.getRefusedHosts();
    assertEquals(List.of("mc.example.com", "203.0.113.5"), refusedHosts);
    final BrowserOptions.Builder builder = BrowserOptions.builder();
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> builder.refusedHosts(List.of("a b")));
    final String message = refused.getMessage();
    assertEquals("Host 'a b' is not a host name or address!", message);
    final int maximumFrameRate = BrowserOptions.builder().frameRate(MAXIMUM_FRAME_RATE).build().getFrameRate();
    assertEquals(MAXIMUM_FRAME_RATE, maximumFrameRate);
  }

  @Test
  void shouldKeepTheFrameRateBetweenOneAndSixty() {
    final BrowserOptions.Builder builder = BrowserOptions.builder();
    assertThrows(IllegalArgumentException.class, () -> builder.frameRate(0));
    final IllegalArgumentException tooFast = assertThrows(IllegalArgumentException.class, () -> builder.frameRate(61));
    final String message = tooFast.getMessage();
    assertEquals("Frame rate 61 must be between 1 and 60!", message);
  }

  @Test
  void shouldTreatOptionsWithTheSameValuesAsEqual() {
    EqualityAssertions.assertEqualityContract(
      BrowserOptions.builder().build(),
      BrowserOptions.builder().build(),
      BrowserOptions.builder().frameRate(1).build(),
      BrowserOptions.builder().javaScriptJit(true).build(),
      BrowserOptions.builder().privateNetworks(true).build(),
      BrowserOptions.builder().autoplay(true).build(),
      BrowserOptions.builder().confinement(false).build(),
      BrowserOptions.builder().refusedHosts(List.of("mc.example.com")).build()
    );
  }

  @Test
  void shouldHashOptionsThatDifferInAValueDifferently() {
    final BrowserOptions slower = BrowserOptions.builder().frameRate(1).build();
    final int slowerHashCode = slower.hashCode();
    assertNotEquals(BrowserOptions.DEFAULT.hashCode(), slowerHashCode);
  }

  @Test
  void shouldDescribeEveryOption() {
    final String description = BrowserOptions.DEFAULT.toString();
    assertEquals(
      "BrowserOptions{frameRate=60, javaScriptJit=false, privateNetworks=false, autoplay=false, confined=true, refusedHosts=[]}",
      description
    );
  }
}
