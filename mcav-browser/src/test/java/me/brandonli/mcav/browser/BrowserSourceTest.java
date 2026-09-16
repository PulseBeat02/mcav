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

import java.net.URI;
import me.brandonli.mcav.browser.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link BrowserSource} and {@link BrowserSourceImpl}.
 */
final class BrowserSourceTest {

  private static final URI PAGE = URI.create("https://example.org/page");

  @Test
  void keepsTheScreencastSettings() {
    final BrowserSource source = BrowserSource.uri(PAGE, 55, 640, 360, 3);
    final URI uri = source.getUri();
    final int quality = source.getScreencastQuality();
    final int width = source.getScreencastWidth();
    final int height = source.getScreencastHeight();
    final int nthFrame = source.getScreencastNthFrame();
    assertEquals(PAGE, uri);
    assertEquals(55, quality);
    assertEquals(640, width);
    assertEquals(360, height);
    assertEquals(3, nthFrame);
  }

  @Test
  void usesTheDefaultsForAPlainUri() {
    final BrowserSource source = BrowserSource.uri(PAGE);
    final int quality = source.getScreencastQuality();
    final int width = source.getScreencastWidth();
    final int height = source.getScreencastHeight();
    final int nthFrame = source.getScreencastNthFrame();
    assertEquals(BrowserSource.DEFAULT_QUALITY, quality);
    assertEquals(BrowserSource.DEFAULT_WIDTH, width);
    assertEquals(BrowserSource.DEFAULT_HEIGHT, height);
    assertEquals(1, nthFrame);
  }

  @Test
  void describesItselfAsAnIndirectBrowserSource() {
    final BrowserSource source = BrowserSource.uri(PAGE, 80, 640, 360, 1);
    final String name = source.getName();
    final boolean direct = source.isDirect();
    final String resource = source.getResource();
    final String text = source.toString();
    assertEquals("browser", name);
    assertFalse(direct);
    assertEquals("https://example.org/page", resource);
    assertEquals("BrowserSource[https://example.org/page, 640x360, q=80]", text);
  }

  @Test
  void acceptsTheLimitsOfEveryRange() {
    final BrowserSource lowest = BrowserSource.uri(PAGE, 0, 1, 1, 1);
    final BrowserSource highest = BrowserSource.uri(PAGE, 100, 1, 1, 1);
    final int lowestQuality = lowest.getScreencastQuality();
    final int highestQuality = highest.getScreencastQuality();
    assertEquals(0, lowestQuality);
    assertEquals(100, highestQuality);
  }

  @Test
  void rejectsInvalidSettings() {
    assertThrows(NullPointerException.class, () -> BrowserSource.uri(null));
    assertThrows(NullPointerException.class, () -> BrowserSource.uri(null, 80, 640, 360, 1));
    assertThrows(IllegalArgumentException.class, () -> BrowserSource.uri(PAGE, -1, 640, 360, 1));
    assertThrows(IllegalArgumentException.class, () -> BrowserSource.uri(PAGE, 101, 640, 360, 1));
    assertThrows(IllegalArgumentException.class, () -> BrowserSource.uri(PAGE, 80, 0, 360, 1));
    assertThrows(IllegalArgumentException.class, () -> BrowserSource.uri(PAGE, 80, 640, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> BrowserSource.uri(PAGE, 80, 640, 360, 0));
  }

  @Test
  void comparesEverySetting() {
    final BrowserSource source = BrowserSource.uri(PAGE, 80, 640, 360, 2);
    final BrowserSource equal = BrowserSource.uri(PAGE, 80, 640, 360, 2);
    final URI otherPage = URI.create("https://example.org/other");
    final BrowserSource otherUri = BrowserSource.uri(otherPage, 80, 640, 360, 2);
    final BrowserSource otherQuality = BrowserSource.uri(PAGE, 81, 640, 360, 2);
    final BrowserSource otherWidth = BrowserSource.uri(PAGE, 80, 641, 360, 2);
    final BrowserSource otherHeight = BrowserSource.uri(PAGE, 80, 640, 361, 2);
    final BrowserSource otherInterval = BrowserSource.uri(PAGE, 80, 640, 360, 3);
    EqualityAssertions.assertEqualityContract(source, equal, otherUri, otherQuality, otherWidth, otherHeight, otherInterval);
  }
}
