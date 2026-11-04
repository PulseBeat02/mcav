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

  private static final int MAXIMUM_PAGE_DIMENSION = 4096;

  private static final URI PAGE = URI.create("https://example.com/");

  private static final int WIDTH = 640;
  private static final int HEIGHT = 480;

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
    final BrowserSource source = BrowserSource.uri(PAGE, MAXIMUM_PAGE_DIMENSION, 1, 1000);
    final int width = source.getWidth();
    assertEquals(MAXIMUM_PAGE_DIMENSION, width);

    final int height = source.getHeight();
    assertEquals(1, height);

    final int frameInterval = source.getFrameInterval();
    assertEquals(1000, frameInterval);

    final String description = source.toString();
    assertEquals("DefaultBrowserSource{uri=https://example.com/, width=4096, height=1, frameInterval=1000}", description);
  }

  @Test
  void shouldOnlyAcceptWebAddressesAndSizesWithinTheLimits() {
    assertThrows(NullPointerException.class, () -> BrowserSource.uri(null));

    final IllegalArgumentException file = assertThrows(IllegalArgumentException.class, () -> this.sourceOf("file:///etc/passwd"));
    final String fileMessage = file.getMessage();
    assertEquals("Address file:///etc/passwd must be an http or https address for the browser!", fileMessage);
    final String underscoreAddress = "https://my_site.example.com/";
    final IllegalArgumentException underscore = assertThrows(IllegalArgumentException.class, () -> this.sourceOf(underscoreAddress));

    final String underscoreMessage = underscore.getMessage();
    assertEquals(
      "Address https://my_site.example.com/ has no host name the browser can check, such as a name with an underscore!",
      underscoreMessage
    );
    assertThrows(IllegalArgumentException.class, () -> BrowserSource.uri(PAGE, 0, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> BrowserSource.uri(PAGE, 1, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> BrowserSource.uri(PAGE, 4097, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> BrowserSource.uri(PAGE, 1, 4097, 1));

    final int height = BrowserSource.uri(PAGE, MAXIMUM_PAGE_DIMENSION, MAXIMUM_PAGE_DIMENSION, 1).getHeight();
    assertEquals(MAXIMUM_PAGE_DIMENSION, height, "4096 per side is the largest frame, and allowed");
    assertThrows(IllegalArgumentException.class, () -> BrowserSource.uri(PAGE, 1, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> BrowserSource.uri(PAGE, 1, 1, 1001));

    final String start = "https://example.com/";
    final URI longest = URI.create(start + "a".repeat(BrowserSource.MAXIMUM_ADDRESS_LENGTH - start.length()));
    final URI longestUri = BrowserSource.uri(longest).getUri();
    assertEquals(longest, longestUri);

    final URI tooLong = URI.create(longest + "a");
    final IllegalArgumentException length = assertThrows(IllegalArgumentException.class, () -> BrowserSource.uri(tooLong));
    final String message = length.getMessage();
    assertEquals("Address length 65537 must be at most 65536 characters!", message);
  }

  private BrowserSource sourceOf(final String address) {
    return BrowserSource.uri(URI.create(address));
  }

  @Test
  void shouldTreatSourcesWithTheSameSettingsAsEqual() {
    EqualityAssertions.assertEqualityContract(
      BrowserSource.uri(PAGE, WIDTH, HEIGHT, 2),
      BrowserSource.uri(PAGE, WIDTH, HEIGHT, 2),
      BrowserSource.uri(URI.create("https://example.org/"), WIDTH, HEIGHT, 2),
      BrowserSource.uri(PAGE, WIDTH + 1, HEIGHT, 2),
      BrowserSource.uri(PAGE, WIDTH, HEIGHT + 1, 2),
      BrowserSource.uri(PAGE, WIDTH, HEIGHT, 3)
    );
  }

  @Test
  void shouldHashSourcesThatDifferInASettingDifferently() {
    final BrowserSource first = BrowserSource.uri(PAGE, WIDTH, HEIGHT, 1);
    final BrowserSource wider = BrowserSource.uri(PAGE, WIDTH + 1, HEIGHT, 1);
    final int widerHashCode = wider.hashCode();
    assertNotEquals(first.hashCode(), widerHashCode);
  }

  @Test
  void shouldRefuseASideOfZeroForEitherSide() {
    assertThrows(IllegalArgumentException.class, () -> BrowserSource.uri(PAGE, 0, HEIGHT, 1));
    assertThrows(IllegalArgumentException.class, () -> BrowserSource.uri(PAGE, WIDTH, 0, 1));
  }
}
