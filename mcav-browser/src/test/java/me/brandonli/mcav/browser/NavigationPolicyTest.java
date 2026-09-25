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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import me.brandonli.mcav.browser.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NavigationPolicyTest {

  @ParameterizedTest
  @ValueSource(strings = { "http://example.com", "https://example.com/a?b#c", "HTTPS://EXAMPLE.COM", "http://127.0.0.1:8080/" })
  void webAddressesAreAbsoluteHttpOrHttpsWithAHost(final String address) {
    assertTrue(NavigationPolicy.isWebAddress(URI.create(address)));
  }

  @ParameterizedTest
  @ValueSource(
    strings = { "file:///etc/passwd", "chrome://settings", "ftp://example.com/", "https:///no-host", "relative/path", "mailto:a@b.c" }
  )
  void otherAddressesAreNotWebAddresses(final String address) {
    assertFalse(NavigationPolicy.isWebAddress(URI.create(address)));
  }

  @Test
  void thePageMayOnlyNavigateToWebAddresses() {
    assertTrue(NavigationPolicy.allowsNavigation("https://example.com/", true));
    assertFalse(NavigationPolicy.allowsNavigation("file:///etc/passwd", true));
    assertTrue(NavigationPolicy.allowsNavigation("about:blank", true), "the empty document the browser starts with");
    assertFalse(NavigationPolicy.allowsNavigation("about:srcdoc", true));
    assertFalse(NavigationPolicy.allowsNavigation("data:text/html,x", true));
    assertFalse(NavigationPolicy.allowsNavigation("not a uri at all", true));
    assertFalse(NavigationPolicy.allowsNavigation("chrome://gpu", true));
    assertTrue(NavigationPolicy.allowsNavigation("https://example.com/a|b?q=%7C", true), "Chromium's canonical form may hold '|'");
  }

  @Test
  void framesMayAlsoHoldLocalDocumentsOfTheBrowser() {
    assertTrue(NavigationPolicy.allowsNavigation("about:blank", false));
    assertTrue(NavigationPolicy.allowsNavigation("about:srcdoc", false));
    assertTrue(NavigationPolicy.allowsNavigation("data:text/html,x", false));
    assertTrue(NavigationPolicy.allowsNavigation("blob:https://example.com/1234", false));
    assertTrue(NavigationPolicy.allowsNavigation("HTTP://example.com/", false));
    assertFalse(NavigationPolicy.allowsNavigation("about:settings", false));
    assertFalse(NavigationPolicy.allowsNavigation("file:///etc/passwd", false));
    assertFalse(NavigationPolicy.allowsNavigation("javascript:alert(1)", false));
    assertFalse(NavigationPolicy.allowsNavigation(":nothing", false));
    assertFalse(NavigationPolicy.allowsNavigation("noscheme", false));
  }

  @Test
  void requestsMayOnlyUseTheWebSchemes() {
    assertTrue(NavigationPolicy.allowsRequest("https://example.com/a.png"));
    assertTrue(NavigationPolicy.allowsRequest("wss://example.com/socket"));
    assertTrue(NavigationPolicy.allowsRequest("ws://example.com/socket"));
    assertTrue(NavigationPolicy.allowsRequest("data:image/png;base64,AA=="));
    assertTrue(NavigationPolicy.allowsRequest("blob:https://example.com/1"));
    assertFalse(NavigationPolicy.allowsRequest("file:///home/user/config.yml"));
    assertFalse(NavigationPolicy.allowsRequest("chrome-extension://abc/x.js"));
    assertFalse(NavigationPolicy.allowsRequest("steam://run/1"));
  }

  @Test
  void thePolicyIsNotInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(NavigationPolicy.class);
  }
}
