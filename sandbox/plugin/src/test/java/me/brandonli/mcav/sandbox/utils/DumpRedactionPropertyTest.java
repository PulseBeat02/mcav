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
package me.brandonli.mcav.sandbox.utils;

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

/**
 * A property of the redaction of {@code /mcav dump}, which publishes the tail of the server log on a public paste site.
 * Pass 4 made it mask the addresses of players; this checks every address a server can log, formatted by the JDK
 * exactly as the server formats the address of a connection, in the lines that carry one: no player's address may
 * survive, whether IPv4, IPv6, an IPv4 address mapped into IPv6, or a link-local IPv6 address with its zone.
 */
final class DumpRedactionPropertyTest {

  private static final String SEED = "20260925";

  @Provide
  Arbitrary<InetSocketAddress> addresses() {
    final ByteArbitrary ipv4Bytes = Arbitraries.bytes();
    final ListArbitrary<Byte> ipv4List = ipv4Bytes.list();
    final ListArbitrary<Byte> ipv4 = ipv4List.ofSize(4);
    final ByteArbitrary ipv6Bytes = Arbitraries.bytes();
    final ListArbitrary<Byte> ipv6List = ipv6Bytes.list();
    final ListArbitrary<Byte> ipv6 = ipv6List.ofSize(16);
    final Arbitrary<List<Byte>> raw = Arbitraries.oneOf(ipv4, ipv6);
    final IntegerArbitrary scopeIntegers = Arbitraries.integers();
    final Arbitrary<Integer> scopes = scopeIntegers.between(-1, 64);
    final IntegerArbitrary portIntegers = Arbitraries.integers();
    final Arbitrary<Integer> ports = portIntegers.between(0, 65_535);
    final Combinators.Combinator3<List<Byte>, Integer, Integer> addresses = Combinators.combine(raw, scopes, ports);
    return addresses.as(DumpRedactionPropertyTest::createAddress);
  }

  /**
   * Creates the address of a connection; a scope below zero stands for an address without a zone.
   */
  private static InetSocketAddress createAddress(final List<Byte> raw, final int scope, final int port) {
    final byte[] bytes = new byte[raw.size()];
    for (int index = 0; index < bytes.length; index++) {
      bytes[index] = raw.get(index);
    }
    try {
      final boolean scoped = bytes.length == 16 && scope >= 0;
      final InetAddress address = scoped ? Inet6Address.getByAddress(null, bytes, scope) : InetAddress.getByAddress(bytes);
      return new InetSocketAddress(address, port);
    } catch (final UnknownHostException impossible) {
      throw new IllegalStateException("4 and 16 bytes are always an address", impossible);
    }
  }

  @Provide
  Arbitrary<String> names() {
    final StringArbitrary strings = Arbitraries.strings();
    final StringArbitrary nameCharacters = strings.withChars("abcXYZ019_");
    final StringArbitrary longEnough = nameCharacters.ofMinLength(3);
    return longEnough.ofMaxLength(16);
  }

  @Property(seed = SEED)
  void noPlayerAddressSurvivesTheRedaction(@ForAll("addresses") final InetSocketAddress connection, @ForAll("names") final String name) {
    final String socket = connection.toString();
    final InetAddress address = connection.getAddress();
    final String host = address.getHostAddress();
    final List<String> lines = List.of(
      "[12:34:56 INFO]: " + name + "[" + socket + "] logged in with entity id 42 at ([world]1.5, 64.0, -3.5)",
      "[12:34:56 INFO]: Disconnecting " + name + " (" + socket + "): You are not whitelisted on this server!",
      "[12:34:56 INFO]: " + socket + " lost connection: Disconnected"
    );

    for (final String line : lines) {
      final String redacted = DumpUtils.redactLogLine(line);
      final boolean keepsHost = redacted.contains(host);
      assertFalse(keepsHost, () -> "the address " + host + " survives in: " + redacted);
    }
  }
}
