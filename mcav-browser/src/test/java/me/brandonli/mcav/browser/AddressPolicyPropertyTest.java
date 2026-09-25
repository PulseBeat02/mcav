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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

/**
 * Properties of {@link AddressPolicy} over the whole address space: an IPv4 address is judged the same however it is
 * written, whatever Java itself calls local or multicast is never public, nothing outside IPv6 global unicast is
 * public unless it carries a public IPv4 address, and behind a NAT64 prefix of the network found from the answer for
 * ipv4only.arpa, an address is judged by the IPv4 address it embeds.
 */
final class AddressPolicyPropertyTest {

  private static final String SEED = "20260925";

  private static byte[] ipv6(final int first, final int second, final int third, final int fourth) {
    return ByteBuffer.allocate(16).putInt(first).putInt(second).putInt(third).putInt(fourth).array();
  }

  // the lengths of RFC 6052; the byte after the first 64 bits stays zero and is skipped
  private static InetAddress translate(final byte[] prefix, final int length, final int ipv4) throws UnknownHostException {
    final byte[] bytes = new byte[16];
    System.arraycopy(prefix, 0, bytes, 0, length / Byte.SIZE);
    int index = length / Byte.SIZE;
    for (int shift = 24; shift >= 0; shift -= Byte.SIZE) {
      if (index == 8) {
        index++;
      }
      bytes[index] = (byte) (ipv4 >>> shift);
      index++;
    }
    return InetAddress.getByAddress(bytes);
  }

  @Property(seed = SEED, tries = 2000)
  void behindTheNat64PrefixOfTheNetworkAnAddressIsJudgedByItsIpv4Address(
    @ForAll @Size(value = 16) final byte[] prefix,
    @ForAll @IntRange(min = 0, max = 5) final int lengthIndex,
    @ForAll final int ipv4
  ) throws UnknownHostException {
    final int length = new int[] { 32, 40, 48, 56, 64, 96 }[lengthIndex];
    final InetAddress[] answer = { translate(prefix, length, 0xC00000AA), translate(prefix, length, 0xC00000AB) };
    final List<AddressPolicy.TranslationPrefix> prefixes = AddressPolicy.findTranslationPrefixes(answer);
    // another length finds a prefix only where its own place happens to hold 192.0.0.170 or 192.0.0.171 as well
    if (prefixes.size() == 1) {
      assertEquals(AddressPolicy.isPublicIpv4(ipv4), AddressPolicy.isPublic(translate(prefix, length, ipv4), prefixes));
    }
  }

  @Property(seed = SEED, tries = 2000)
  void anIpv4AddressIsJudgedTheSameMappedOrTranslated(@ForAll final int address) {
    final boolean plain = AddressPolicy.isPublicIpv4(address);
    assertEquals(plain, AddressPolicy.isPublicIpv6(ipv6(0, 0, 0x0000FFFF, address)), "mapped");
    assertEquals(plain, AddressPolicy.isPublicIpv6(ipv6(0x0064FF9B, 0, 0, address)), "translated by NAT64");
  }

  @Property(seed = SEED, tries = 2000)
  void whatJavaCallsLocalOrMulticastIsNeverPublic(@ForAll @Size(value = 4) final byte[] ipv4, @ForAll @Size(value = 16) final byte[] ipv6)
    throws UnknownHostException {
    for (final byte[] bytes : new byte[][] { ipv4, ipv6 }) {
      final InetAddress address = InetAddress.getByAddress(bytes);
      final boolean local =
        address.isAnyLocalAddress() ||
        address.isLoopbackAddress() ||
        address.isLinkLocalAddress() ||
        address.isSiteLocalAddress() ||
        address.isMulticastAddress();
      if (local) {
        assertFalse(AddressPolicy.isPublic(address), address::toString);
      }
    }
  }

  @Property(seed = SEED, tries = 2000)
  void nothingOutsideIpv6GlobalUnicastIsPublicUnlessItCarriesIpv4(@ForAll @Size(value = 16) final byte[] bytes) {
    final ByteBuffer buffer = ByteBuffer.wrap(bytes);
    final int first = buffer.getInt();
    final int second = buffer.getInt();
    final int third = buffer.getInt();
    final boolean mapped = first == 0 && second == 0 && third == 0x0000FFFF;
    final boolean translated = first == 0x0064FF9B && second == 0 && third == 0;
    final boolean globalUnicast = (first & 0xE0000000) == 0x20000000;
    if (!globalUnicast && !mapped && !translated) {
      assertFalse(AddressPolicy.isPublicIpv6(bytes));
    }
  }
}
