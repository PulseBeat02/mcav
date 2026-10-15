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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.Locale;
import org.junit.jupiter.api.Tag;

/**
 * Fuzzes the navigation policy with the addresses a page can make the browser report. It never throws, the page itself
 * only ever goes to an {@code http} or {@code https} address or the empty document, and nothing it allows has a scheme
 * outside the lists {@link NavigationPolicy} documents.
 */
@Tag("fuzz")
final class NavigationPolicyFuzzTest {

  @FuzzTest(maxDuration = "30s")
  void shouldAllowOnlyTheDocumentedSchemes(final FuzzedDataProvider data) {
    final String address = data.consumeRemainingAsString();
    final String lower = address.toLowerCase(Locale.ROOT);
    if (NavigationPolicy.allowsNavigation(address, FrameKind.MAIN)) {
      final boolean documentedPageScheme = address.equals("about:blank") || lower.startsWith("http:") || lower.startsWith("https:");
      assertTrue(documentedPageScheme, address);
    }
    if (NavigationPolicy.allowsNavigation(address, FrameKind.CHILD)) {
      final boolean isAboutPage = address.equals("about:blank") || address.equals("about:srcdoc");
      final boolean documentedFrameScheme = isAboutPage || hasScheme(lower, "http", "https", "data", "blob");
      assertTrue(documentedFrameScheme, address);
    }
    if (NavigationPolicy.allowsRequest(address)) {
      final boolean documentedRequestScheme = hasScheme(lower, "http", "https", "ws", "wss", "data", "blob");
      assertTrue(documentedRequestScheme, address);
    }
  }

  private static boolean hasScheme(final String lower, final String... schemes) {
    for (final String scheme : schemes) {
      if (lower.startsWith(scheme + ":")) {
        return true;
      }
    }
    return false;
  }
}
