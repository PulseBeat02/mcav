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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;

@Tag("fuzz")
final class BrowserSourceFuzzTest {

  private static final Set<String> WEB_SCHEMES = Set.of("http", "https");

  @FuzzTest(maxDuration = "30s")
  void shouldAcceptOrRefuseEveryAddress(final FuzzedDataProvider data) {
    final String address = data.consumeRemainingAsString();
    final Optional<URI> uri = this.parseUri(address);
    uri.ifPresent(this::assertRefusedOrAcceptedAsAWebAddress);
  }

  private Optional<URI> parseUri(final String address) {
    try {
      final URI uri = new URI(address);
      return Optional.of(uri);
    } catch (final URISyntaxException exception) {
      return Optional.empty();
    }
  }

  private void assertRefusedOrAcceptedAsAWebAddress(final URI uri) {
    final Optional<BrowserSource> source = this.createSource(uri);
    source.ifPresent(accepted -> this.assertWebAddress(uri, accepted));
  }

  private Optional<BrowserSource> createSource(final URI uri) {
    try {
      final BrowserSource source = BrowserSource.uri(uri);
      return Optional.of(source);
    } catch (final IllegalArgumentException exception) {
      return Optional.empty();
    }
  }

  private void assertWebAddress(final URI uri, final BrowserSource source) {
    final URI sourceUri = source.getUri();
    assertEquals(uri, sourceUri);

    final String scheme = sourceUri.getScheme();
    assertNotNull(scheme);

    final String lowerScheme = scheme.toLowerCase(Locale.ROOT);
    final boolean webScheme = WEB_SCHEMES.contains(lowerScheme);
    assertTrue(webScheme);

    final String host = sourceUri.getHost();
    assertNotNull(host);

    final String address = sourceUri.toString();
    final int length = address.length();
    final boolean withinLimit = length <= BrowserSource.MAXIMUM_ADDRESS_LENGTH;
    assertTrue(withinLimit);
  }
}
