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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import me.brandonli.mcav.browser.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;

class BrowserOptionsTest {

  private static final String REFUSED_HOST = "mc.example.com";

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
      .refusedHosts(List.of(REFUSED_HOST, "203.0.113.5"))
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
    final List<String> expectedRefusedHosts = List.of(REFUSED_HOST, "203.0.113.5");
    assertEquals(expectedRefusedHosts, refusedHosts);

    final BrowserOptionsBuilder builder = BrowserOptions.builder().refusedHosts(List.of("a b"));
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, builder::build);
    final String message = refused.getMessage();
    assertEquals("Host a b is not a host name or address!", message);

    final int maximumFrameRate = BrowserOptions.builder().frameRate(MAXIMUM_FRAME_RATE).build().getFrameRate();
    assertEquals(MAXIMUM_FRAME_RATE, maximumFrameRate);
  }

  @Test
  void shouldKeepEveryFlagApart() {
    final BrowserOptions javaScriptJitOnly = BrowserOptions.builder().javaScriptJit(true).build();
    final List<Boolean> javaScriptJitFlags = this.flagsOf(javaScriptJitOnly);
    final List<Boolean> expectedJavaScriptJitFlags = List.of(true, false, false, true);
    assertEquals(expectedJavaScriptJitFlags, javaScriptJitFlags);

    final BrowserOptions privateNetworksOnly = BrowserOptions.builder().privateNetworks(true).build();
    final List<Boolean> privateNetworksFlags = this.flagsOf(privateNetworksOnly);
    final List<Boolean> expectedPrivateNetworksFlags = List.of(false, true, false, true);
    assertEquals(expectedPrivateNetworksFlags, privateNetworksFlags);
  }

  @Test
  void shouldCopyTheRefusedHostsWhenTheyAreSet() {
    final List<String> hosts = new ArrayList<>(List.of(REFUSED_HOST));
    final BrowserOptionsBuilder builder = BrowserOptions.builder().refusedHosts(hosts);
    hosts.add("other.example.com");

    final BrowserOptions options = builder.build();
    final List<String> refusedHosts = options.getRefusedHosts();
    final List<String> expectedRefusedHosts = List.of(REFUSED_HOST);
    assertEquals(expectedRefusedHosts, refusedHosts);
  }

  @Test
  void shouldRefuseANullListOrANullHostWhenTheHostsAreSet() {
    final BrowserOptionsBuilder builder = BrowserOptions.builder();
    final NullPointerException refused = assertThrows(NullPointerException.class, () -> builder.refusedHosts(null));
    final String message = refused.getMessage();
    assertEquals("Refused hosts must not be null!", message);

    final List<String> hostsWithANull = Arrays.asList(REFUSED_HOST, null);
    assertThrows(NullPointerException.class, () -> builder.refusedHosts(hostsWithANull));
  }

  @Test
  void shouldKeepTheFrameRateBetweenOneAndSixty() {
    final BrowserOptionsBuilder tooSlowBuilder = BrowserOptions.builder().frameRate(0);
    assertThrows(IllegalArgumentException.class, tooSlowBuilder::build);

    final BrowserOptionsBuilder tooFastBuilder = BrowserOptions.builder().frameRate(61);
    final IllegalArgumentException tooFast = assertThrows(IllegalArgumentException.class, tooFastBuilder::build);
    final String message = tooFast.getMessage();
    assertEquals("Frame rate 61 must be between 1 and 60 frames per second!", message);
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
      BrowserOptions.builder().refusedHosts(List.of(REFUSED_HOST)).build()
    );
  }

  @Test
  void shouldHashOptionsThatDifferInAValueDifferently() {
    final BrowserOptions slower = BrowserOptions.builder().frameRate(1).build();
    final int slowerHashCode = slower.hashCode();
    final int defaultHashCode = BrowserOptions.DEFAULT.hashCode();
    assertNotEquals(defaultHashCode, slowerHashCode);
  }

  @Test
  void shouldDescribeEveryOption() {
    final String description = BrowserOptions.DEFAULT.toString();
    assertEquals(
      "BrowserOptions{frameRate=60, javaScriptJit=false, privateNetworks=false, autoplay=false, confined=true, refusedHosts=[]}",
      description
    );
  }

  private List<Boolean> flagsOf(final BrowserOptions options) {
    final boolean javaScriptJit = options.isJavaScriptJit();
    final boolean privateNetworks = options.isPrivateNetworks();
    final boolean autoplay = options.isAutoplay();
    final boolean confined = options.isConfined();
    return List.of(javaScriptJit, privateNetworks, autoplay, confined);
  }
}
