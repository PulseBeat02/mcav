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

import java.net.URI;
import me.brandonli.mcav.browser.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;

class BrowserSourceTest {

  private static final URI PAGE = URI.create("https://example.com/");

  @Test
  void shouldDefaultTo720pEveryFrame() {
    final BrowserSource source = BrowserSource.uri(PAGE);
    final URI uri = source.getUri();
    assertEquals(PAGE, uri);
    final int width = source.getWidth();
    assertEquals(BrowserSource.DEFAULT_WIDTH, width);
    final int height = source.getHeight();
    assertEquals(BrowserSource.DEFAULT_HEIGHT, height);
    final int frameInterval = source.getFrameInterval();
    assertEquals(1, frameInterval);
    final String name = source.getName();
    assertEquals("browser", name);
    final boolean direct = source.isDirect();
    assertFalse(direct);
    final String resource = source.getResource();
    assertEquals(PAGE.toString(), resource);
  }

  @Test
  void shouldKeepEverySetting() {
    final BrowserSource source = BrowserSource.uri(PAGE, 4096, 1, 1000);
    final int width = source.getWidth();
    assertEquals(4096, width);
    final int height = source.getHeight();
    assertEquals(1, height);
    final int frameInterval = source.getFrameInterval();
    assertEquals(1000, frameInterval);
    final String description = source.toString();
    assertEquals("BrowserSource[https://example.com/, 4096x1, every 1000]", description);
  }

  @Test
  void shouldOnlyAcceptWebAddressesAndSizesWithinTheLimits() {
    assertThrows(NullPointerException.class, () -> BrowserSource.uri(null));
    final IllegalArgumentException file = assertThrows(IllegalArgumentException.class, () ->
      BrowserSource.uri(URI.create("file:///etc/passwd"))
    );
    final String fileMessage = file.getMessage();
    assertEquals("Address file:///etc/passwd must be an http or https address for the browser!", fileMessage);
    final IllegalArgumentException underscore = assertThrows(IllegalArgumentException.class, () ->
      BrowserSource.uri(URI.create("https://my_site.example.com/"))
    );
    final String underscoreMessage = underscore.getMessage();
    assertEquals(
      "Address https://my_site.example.com/ has no host name the browser can check, such as a name with an underscore!",
      underscoreMessage
    );
    assertThrows(IllegalArgumentException.class, () -> BrowserSource.uri(PAGE, 0, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> BrowserSource.uri(PAGE, 1, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> BrowserSource.uri(PAGE, 4097, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> BrowserSource.uri(PAGE, 1, 4097, 1));
    final int height = BrowserSource.uri(PAGE, 4096, 4096, 1).getHeight();
    assertEquals(4096, height, "4096 per side is the largest frame, and allowed");
    assertThrows(IllegalArgumentException.class, () -> BrowserSource.uri(PAGE, 1, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> BrowserSource.uri(PAGE, 1, 1, 1001));
    final String start = "https://example.com/";
    final URI longest = URI.create(start + "a".repeat(BrowserSource.MAX_ADDRESS_LENGTH - start.length()));
    final URI longestUri = BrowserSource.uri(longest).getUri();
    assertEquals(longest, longestUri);
    final URI tooLong = URI.create(longest + "a");
    final IllegalArgumentException length = assertThrows(IllegalArgumentException.class, () -> BrowserSource.uri(tooLong));
    final String message = length.getMessage();
    assertEquals("Address length 65537 must be at most 65536 characters!", message);
  }

  @Test
  void shouldTreatSourcesWithTheSameSettingsAsEqual() {
    EqualityAssertions.assertEqualityContract(
      BrowserSource.uri(PAGE, 640, 480, 2),
      BrowserSource.uri(PAGE, 640, 480, 2),
      BrowserSource.uri(URI.create("https://example.org/"), 640, 480, 2),
      BrowserSource.uri(PAGE, 641, 480, 2),
      BrowserSource.uri(PAGE, 640, 481, 2),
      BrowserSource.uri(PAGE, 640, 480, 3)
    );
  }

  @Test
  void shouldHashSourcesThatDifferInASettingDifferently() {
    final BrowserSource first = BrowserSource.uri(PAGE, 640, 480, 1);
    final BrowserSource wider = BrowserSource.uri(PAGE, 641, 480, 1);
    final int widerHashCode = wider.hashCode();
    assertNotEquals(first.hashCode(), widerHashCode);
  }

  @Test
  void shouldRefuseASideOfZeroForEitherSide() {
    assertThrows(IllegalArgumentException.class, () -> BrowserSource.uri(PAGE, 0, 480, 1));
    assertThrows(IllegalArgumentException.class, () -> BrowserSource.uri(PAGE, 640, 0, 1));
  }
}
