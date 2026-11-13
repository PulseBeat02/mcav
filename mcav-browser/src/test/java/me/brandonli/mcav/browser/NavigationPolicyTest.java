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

  private static final String LOCAL_FILE_URL = "file:///etc/passwd";
  private static final String BLANK_DOCUMENT_URL = "about:blank";
  private static final String FRAME_DOCUMENT_URL = "about:srcdoc";
  private static final String DATA_DOCUMENT_URL = "data:text/html,x";

  @ParameterizedTest
  @ValueSource(strings = { "http://example.com", "https://example.com/a?b#c", "HTTPS://EXAMPLE.COM", "http://127.0.0.1:8080/" })
  void shouldAcceptOnlyAbsoluteHttpOrHttpsAddressesWithAHostAsWebAddresses(final String address) {
    final boolean webAddress = NavigationPolicy.isWebAddress(URI.create(address));
    assertTrue(webAddress);
  }

  @ParameterizedTest
  @ValueSource(
    strings = { "file:///etc/passwd", "chrome://settings", "ftp://example.com/", "https:///no-host", "relative/path", "mailto:a@b.c" }
  )
  void shouldNotAcceptOtherAddressesAsWebAddresses(final String address) {
    final boolean webAddress = NavigationPolicy.isWebAddress(URI.create(address));
    assertFalse(webAddress);
  }

  @Test
  void shouldOnlyLetThePageNavigateToWebAddresses() {
    final boolean httpsAllowed = NavigationPolicy.allowsNavigation("https://example.com/", FrameKind.MAIN);
    assertTrue(httpsAllowed);

    final boolean fileAllowed = NavigationPolicy.allowsNavigation(LOCAL_FILE_URL, FrameKind.MAIN);
    assertFalse(fileAllowed);

    final boolean aboutBlankAllowed = NavigationPolicy.allowsNavigation(BLANK_DOCUMENT_URL, FrameKind.MAIN);
    assertTrue(aboutBlankAllowed, "the empty document the browser starts with");

    final boolean aboutSrcdocAllowed = NavigationPolicy.allowsNavigation(FRAME_DOCUMENT_URL, FrameKind.MAIN);
    assertFalse(aboutSrcdocAllowed);

    final boolean dataAllowed = NavigationPolicy.allowsNavigation(DATA_DOCUMENT_URL, FrameKind.MAIN);
    assertFalse(dataAllowed);

    final boolean malformedAllowed = NavigationPolicy.allowsNavigation("not a uri at all", FrameKind.MAIN);
    assertFalse(malformedAllowed);

    final boolean chromeAllowed = NavigationPolicy.allowsNavigation("chrome://gpu", FrameKind.MAIN);
    assertFalse(chromeAllowed);

    final boolean pipeAllowed = NavigationPolicy.allowsNavigation("https://example.com/a|b?q=%7C", FrameKind.MAIN);
    assertTrue(pipeAllowed, "Chromium's canonical form may hold '|'");
  }

  @Test
  void shouldAlsoLetFramesHoldLocalDocumentsOfTheBrowser() {
    final boolean httpsAllowed = NavigationPolicy.allowsNavigation("https://example.com/frame", FrameKind.CHILD);
    assertTrue(httpsAllowed);

    final boolean aboutBlankAllowed = NavigationPolicy.allowsNavigation(BLANK_DOCUMENT_URL, FrameKind.CHILD);
    assertTrue(aboutBlankAllowed);

    final boolean aboutSrcdocAllowed = NavigationPolicy.allowsNavigation(FRAME_DOCUMENT_URL, FrameKind.CHILD);
    assertTrue(aboutSrcdocAllowed);

    final boolean dataAllowed = NavigationPolicy.allowsNavigation(DATA_DOCUMENT_URL, FrameKind.CHILD);
    assertTrue(dataAllowed);

    final boolean blobAllowed = NavigationPolicy.allowsNavigation("blob:https://example.com/1234", FrameKind.CHILD);
    assertTrue(blobAllowed);

    final boolean upperCaseHttpAllowed = NavigationPolicy.allowsNavigation("HTTP://example.com/", FrameKind.CHILD);
    assertTrue(upperCaseHttpAllowed);

    final boolean aboutSettingsAllowed = NavigationPolicy.allowsNavigation("about:settings", FrameKind.CHILD);
    assertFalse(aboutSettingsAllowed);

    final boolean fileAllowed = NavigationPolicy.allowsNavigation(LOCAL_FILE_URL, FrameKind.CHILD);
    assertFalse(fileAllowed);

    final boolean javascriptAllowed = NavigationPolicy.allowsNavigation("javascript:alert(1)", FrameKind.CHILD);
    assertFalse(javascriptAllowed);

    final boolean emptySchemeAllowed = NavigationPolicy.allowsNavigation(":nothing", FrameKind.CHILD);
    assertFalse(emptySchemeAllowed);

    final boolean noSchemeAllowed = NavigationPolicy.allowsNavigation("noscheme", FrameKind.CHILD);
    assertFalse(noSchemeAllowed);
  }

  @Test
  void shouldOnlyLetRequestsUseTheWebSchemes() {
    final boolean httpAllowed = NavigationPolicy.allowsRequest("http://example.com/a.png");
    assertTrue(httpAllowed);

    final boolean httpsAllowed = NavigationPolicy.allowsRequest("https://example.com/a.png");
    assertTrue(httpsAllowed);

    final boolean secureSocketAllowed = NavigationPolicy.allowsRequest("wss://example.com/socket");
    assertTrue(secureSocketAllowed);

    final boolean socketAllowed = NavigationPolicy.allowsRequest("ws://example.com/socket");
    assertTrue(socketAllowed);

    final boolean dataAllowed = NavigationPolicy.allowsRequest("data:image/png;base64,AA==");
    assertTrue(dataAllowed);

    final boolean blobAllowed = NavigationPolicy.allowsRequest("blob:https://example.com/1");
    assertTrue(blobAllowed);

    final boolean fileAllowed = NavigationPolicy.allowsRequest("file:///home/user/config.yml");
    assertFalse(fileAllowed);

    final boolean extensionAllowed = NavigationPolicy.allowsRequest("chrome-extension://abc/x.js");
    assertFalse(extensionAllowed);

    final boolean steamAllowed = NavigationPolicy.allowsRequest("steam://run/1");
    assertFalse(steamAllowed);
  }

  @Test
  void shouldNotInstantiateThePolicy() {
    UtilityClassAssertions.assertNotInstantiable(NavigationPolicy.class);
  }
}
