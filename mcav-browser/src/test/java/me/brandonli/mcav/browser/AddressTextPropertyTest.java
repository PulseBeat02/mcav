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
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Properties of {@link AddressText}: whatever the parts of a web address are, the user name and password, the query and
 * the fragment never reach the log, while the scheme, the host and the path do; and any text at all is described
 * without a failure and without growing by more than the markers.
 */
final class AddressTextPropertyTest {

  private static final String SEED = "20260925";

  @Provide
  Arbitrary<String> schemes() {
    return Arbitraries.of("http", "https", "ws", "wss", "myapp");
  }

  @Provide
  Arbitrary<String> hosts() {
    return Arbitraries.strings().withChars("abcdefghijklmnopqrstuvwxyz0123456789.-").ofMinLength(1).ofMaxLength(40);
  }

  @Provide
  Arbitrary<String> paths() {
    return Arbitraries.strings().withChars("abcdefghijklmnopqrstuvwxyz0123456789/._~-").ofMaxLength(60).map(path -> "/" + path);
  }

  // the secrets are written with characters the host and the path never hold, so finding one in the text is a leak
  @Provide
  Arbitrary<String> secrets() {
    return Arbitraries.strings().withChars("ABCDEFGHIJKLMNOPQRSTUVWXYZ=&%+").ofMinLength(1).ofMaxLength(60);
  }

  @Property(seed = SEED, tries = 2000)
  void theUserTheQueryAndTheFragmentNeverReachTheLog(
    @ForAll("schemes") final String scheme,
    @ForAll("secrets") final String user,
    @ForAll("hosts") final String host,
    @ForAll("paths") final String path,
    @ForAll("secrets") final String query,
    @ForAll("secrets") final String fragment
  ) {
    final String address = scheme + "://" + user + "@" + host + path + "?" + query + "#" + fragment;
    final String described = AddressText.describe(address);
    assertEquals(scheme + "://" + AddressText.HIDDEN + "@" + host + path + "?" + AddressText.HIDDEN, described);
    assertFalse(described.contains(user), described);
    assertFalse(described.contains(query), described);
    assertFalse(described.contains(fragment), described);
  }

  @Property(seed = SEED, tries = 2000)
  void anAddressWithoutSecretsIsKeptAsItIs(
    @ForAll("schemes") final String scheme,
    @ForAll("hosts") final String host,
    @ForAll("paths") final String path
  ) {
    final String address = scheme + "://" + host + path;
    assertEquals(address, AddressText.describe(address));
  }

  @Property(seed = SEED, tries = 2000)
  void anyTextIsDescribedWithoutFailingOrGrowingBeyondTheMarkers(@ForAll final String text) {
    final String described = AddressText.describe(text);
    final int markers = 2 * AddressText.HIDDEN.length() + 2;
    assertTrue(described.length() <= text.length() + markers, described);
  }
}
