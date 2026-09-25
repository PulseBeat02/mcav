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

class BrowserSourceTest {

  private static final URI PAGE = URI.create("https://example.com/");

  @Test
  void theDefaultSourceIs720pEveryFrame() {
    final BrowserSource source = BrowserSource.uri(PAGE);
    assertEquals(PAGE, source.getUri());
    assertEquals(BrowserSource.DEFAULT_WIDTH, source.getWidth());
    assertEquals(BrowserSource.DEFAULT_HEIGHT, source.getHeight());
    assertEquals(1, source.getFrameInterval());
    assertEquals("browser", source.getName());
    assertFalse(source.isDirect());
    assertEquals(PAGE.toString(), source.getResource());
  }

  @Test
  void everySettingIsKept() {
    final BrowserSource source = BrowserSource.uri(PAGE, 4096, 1, 1000);
    assertEquals(4096, source.getWidth());
    assertEquals(1, source.getHeight());
    assertEquals(1000, source.getFrameInterval());
    assertEquals("BrowserSource[https://example.com/, 4096x1, every 1000]", source.toString());
  }

  @Test
  void onlyWebAddressesAndSizesWithinTheLimitsAreAccepted() {
    assertThrows(NullPointerException.class, () -> BrowserSource.uri(null));
    final IllegalArgumentException file = assertThrows(IllegalArgumentException.class, () ->
      BrowserSource.uri(URI.create("file:///etc/passwd"))
    );
    assertEquals("The browser shows http and https addresses only but got file:///etc/passwd", file.getMessage());
    assertThrows(IllegalArgumentException.class, () -> BrowserSource.uri(PAGE, 0, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> BrowserSource.uri(PAGE, 1, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> BrowserSource.uri(PAGE, 4097, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> BrowserSource.uri(PAGE, 1, 4097, 1));
    assertThrows(IllegalArgumentException.class, () -> BrowserSource.uri(PAGE, 1, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> BrowserSource.uri(PAGE, 1, 1, 1001));
  }

  @Test
  void sourcesWithTheSameSettingsAreEqual() {
    EqualityAssertions.assertEqualityContract(
      BrowserSource.uri(PAGE, 640, 480, 2),
      BrowserSource.uri(PAGE, 640, 480, 2),
      BrowserSource.uri(URI.create("https://example.org/"), 640, 480, 2),
      BrowserSource.uri(PAGE, 641, 480, 2),
      BrowserSource.uri(PAGE, 640, 481, 2),
      BrowserSource.uri(PAGE, 640, 480, 3)
    );
  }
}
