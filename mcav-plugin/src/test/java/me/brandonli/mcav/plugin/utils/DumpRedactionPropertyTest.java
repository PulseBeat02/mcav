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
package me.brandonli.mcav.plugin.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.arbitraries.ByteArbitrary;
import net.jqwik.api.arbitraries.IntegerArbitrary;
import net.jqwik.api.arbitraries.ListArbitrary;
import net.jqwik.api.arbitraries.StringArbitrary;

final class DumpRedactionPropertyTest {

  private static final String SEED = "20260925";

  private static final int IPV6_BYTE_COUNT = 16;

  @Provide
  Arbitrary<InetSocketAddress> addresses() {
    final ByteArbitrary ipv4Bytes = Arbitraries.bytes();
    final ListArbitrary<Byte> ipv4List = ipv4Bytes.list();
    final ListArbitrary<Byte> ipv4 = ipv4List.ofSize(4);
    final ByteArbitrary ipv6Bytes = Arbitraries.bytes();
    final ListArbitrary<Byte> ipv6List = ipv6Bytes.list();
    final ListArbitrary<Byte> ipv6 = ipv6List.ofSize(IPV6_BYTE_COUNT);
    final Arbitrary<List<Byte>> raw = Arbitraries.oneOf(ipv4, ipv6);
    final IntegerArbitrary scopeIntegers = Arbitraries.integers();
    final Arbitrary<Integer> scopes = scopeIntegers.between(-1, 64);
    final IntegerArbitrary portIntegers = Arbitraries.integers();
    final Arbitrary<Integer> ports = portIntegers.between(0, 65_535);
    final Combinators.Combinator3<List<Byte>, Integer, Integer> addresses = Combinators.combine(raw, scopes, ports);
    return addresses.as(this::createAddress);
  }

  private InetSocketAddress createAddress(final List<Byte> raw, final int scope, final int port) {
    final byte[] bytes = new byte[raw.size()];
    for (int index = 0; index < bytes.length; index++) {
      bytes[index] = raw.get(index);
    }
    try {
      final boolean scoped = bytes.length == IPV6_BYTE_COUNT && scope >= 0;
      final InetAddress address = scoped ? Inet6Address.getByAddress(null, bytes, scope) : InetAddress.getByAddress(bytes);
      return new InetSocketAddress(address, port);
    } catch (final UnknownHostException exception) {
      final String message = "4 and 16 bytes are always an address!";
      throw new IllegalStateException(message, exception);
    }
  }

  @Provide
  Arbitrary<String> names() {
    final StringArbitrary strings = Arbitraries.strings();
    final StringArbitrary nameCharacters = strings.withChars("abcXYZ019_");
    final StringArbitrary longEnough = nameCharacters.ofMinLength(3);
    return longEnough.ofMaxLength(16);
  }

  @Provide
  Arbitrary<String> secrets() {
    final StringArbitrary strings = Arbitraries.strings();
    final StringArbitrary secretCharacters = strings.withChars("QJVXZ=&%+");
    return secretCharacters.ofMinLength(1).ofMaxLength(40);
  }

  @Provide
  Arbitrary<String> hosts() {
    final StringArbitrary strings = Arbitraries.strings();
    final StringArbitrary hostCharacters = strings.withChars("abcdefghiklmnoprstuy.-");
    return hostCharacters.ofMinLength(1).ofMaxLength(30);
  }

  @Property(seed = SEED)
  void shouldLetNoSecretOfAWebAddressSurviveTheRedaction(
    @ForAll("hosts") final String host,
    @ForAll("secrets") final String user,
    @ForAll("secrets") final String query,
    @ForAll("secrets") final String fragment
  ) {
    final String address = "https://%s@%s/page?%s#%s".formatted(user, host, query, fragment);
    final List<String> lines = List.of(
      "[12:34:56 INFO]: Steve issued server command: /mcav browser create @a 640x360 1 5x3 0 NEAREST_COLOR NONE %s".formatted(address),
      "[12:34:56 INFO]: [HelperSession] Browser: Refused a navigation to %s".formatted(address),
      "[12:34:56 WARN]: The browser could not load %s: refused (-102)".formatted(address)
    );

    for (final String line : lines) {
      final String redacted = DumpUtils.redactLogLine(line);
      for (final String secret : List.of(user, query, fragment)) {
        final boolean contained = redacted.contains(secret);
        assertFalse(contained, () -> "the secret %s survives in: %s".formatted(secret, redacted));
      }
    }
  }

  @Property(seed = SEED)
  void shouldLetNoPlayerAddressSurviveTheRedaction(
    @ForAll("addresses") final InetSocketAddress connection,
    @ForAll("names") final String name
  ) {
    final String socket = connection.toString();
    final InetAddress address = connection.getAddress();
    final String host = address.getHostAddress();
    final List<String> lines = List.of(
      "[12:34:56 INFO]: %s[%s] logged in with entity id 42 at ([world]1.5, 64.0, -3.5)".formatted(name, socket),
      "[12:34:56 INFO]: Disconnecting %s (%s): You are not whitelisted on this server!".formatted(name, socket),
      "[12:34:56 INFO]: %s lost connection: Disconnected".formatted(socket),
      "[12:34:56 INFO]: Banned IP %s: Banned by an operator.".formatted(host)
    );

    for (final String line : lines) {
      final String redacted = DumpUtils.redactLogLine(line);
      final boolean keepsHost = redacted.contains(host);
      assertFalse(keepsHost, () -> "the address %s survives in: %s".formatted(host, redacted));
    }
  }
}
