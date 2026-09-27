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

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Which addresses the browser may show and load. Web content is untrusted: a page may only navigate to {@code http}
 * and {@code https} addresses, frames inside it may also hold the local documents browsers create themselves
 * ({@code about:blank}, {@code about:srcdoc}, {@code data:} and {@code blob:}), and its requests may only use the web
 * schemes, including WebSockets. Anything else, such as {@code file:} with the files of the server, {@code chrome:}
 * with the internal pages of the browser, or an external program's scheme, is refused.
 */
final class NavigationPolicy {

  /**
   * The empty document a browser is created with, before it loads the page.
   */
  static final String BLANK = "about:blank";

  private static final Set<String> WEB_SCHEMES = Set.of("http", "https");
  private static final Set<String> FRAME_SCHEMES = Set.of("http", "https", "data", "blob");
  private static final Set<String> FRAME_ABOUT_PAGES = Set.of(BLANK, "about:srcdoc");
  private static final Set<String> REQUEST_SCHEMES = Set.of("http", "https", "ws", "wss", "data", "blob");

  private NavigationPolicy() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Checks whether an address is an absolute {@code http} or {@code https} address with a host, the only kind a
   * browser may be started with.
   *
   * @param address the address
   * @return true if it is a web address
   */
  static boolean isWebAddress(final URI address) {
    final String scheme = address.getScheme();
    final String host = address.getHost();
    if (scheme == null || host == null) {
      return false;
    }
    final String lowerScheme = scheme.toLowerCase(Locale.ROOT);
    return WEB_SCHEMES.contains(lowerScheme);
  }

  /**
   * Checks whether a frame may navigate to an address.
   *
   * @param address   the address, as the browser reports it
   * @param mainFrame true for the page itself, false for a frame inside it
   * @return true if the navigation is allowed
   */
  static boolean allowsNavigation(final String address, final boolean mainFrame) {
    if (mainFrame) {
      // the address is Chromium's canonical form, which java.net.URI may refuse for characters such as '|'; the page
      // itself may also be the empty document, which the browser starts with
      return BLANK.equals(address) || hasScheme(address, WEB_SCHEMES);
    }
    final boolean aboutPage = FRAME_ABOUT_PAGES.contains(address);
    return aboutPage || hasScheme(address, FRAME_SCHEMES);
  }

  /**
   * Checks whether the page may request an address, for an image, a script, a fetch or a WebSocket.
   *
   * @param address the address, as the browser reports it
   * @return true if the request is allowed
   */
  static boolean allowsRequest(final String address) {
    return hasScheme(address, REQUEST_SCHEMES);
  }

  private static boolean hasScheme(final String address, final Set<String> schemes) {
    final int colon = address.indexOf(':');
    if (colon <= 0) {
      return false;
    }
    final String scheme = address.substring(0, colon);
    final String lowerScheme = scheme.toLowerCase(Locale.ROOT);
    return schemes.contains(lowerScheme);
  }
}
