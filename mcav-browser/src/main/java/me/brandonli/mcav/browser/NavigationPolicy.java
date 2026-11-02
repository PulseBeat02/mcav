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

// Web content is untrusted, so file:, chrome: and the schemes of external programs are refused.
final class NavigationPolicy {

  static final String BLANK = "about:blank";

  private static final Set<String> WEB_SCHEMES = Set.of("http", "https");
  private static final Set<String> FRAME_SCHEMES = Set.of("http", "https", "data", "blob");
  private static final Set<String> FRAME_ABOUT_PAGES = Set.of(BLANK, "about:srcdoc");
  private static final Set<String> REQUEST_SCHEMES = Set.of("http", "https", "ws", "wss", "data", "blob");

  private NavigationPolicy() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  static boolean isWebAddress(final URI address) {
    final String scheme = address.getScheme();
    final String host = address.getHost();
    if (scheme == null || host == null) {
      return false;
    }

    final String lowerScheme = scheme.toLowerCase(Locale.ROOT);
    return WEB_SCHEMES.contains(lowerScheme);
  }

  static boolean allowsNavigation(final String address, final FrameKind frame) {
    if (frame == FrameKind.MAIN) {
      // Chromium's canonical form, which java.net.URI may refuse for characters such as '|'
      return BLANK.equals(address) || hasScheme(address, WEB_SCHEMES);
    }

    final boolean isAboutPage = FRAME_ABOUT_PAGES.contains(address);
    return isAboutPage || hasScheme(address, FRAME_SCHEMES);
  }

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
