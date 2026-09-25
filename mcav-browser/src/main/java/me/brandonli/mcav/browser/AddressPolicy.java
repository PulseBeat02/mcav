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

import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * Decides which addresses a page may connect to when private networks are not allowed: public unicast addresses of the
 * internet only.
 *
 * <p>Refused are the IPv4 ranges that are not globally reachable: "this" network, private networks, carrier-grade
 * NAT, loopback, link-local (which holds the metadata service of cloud machines), IETF assignments, documentation,
 * benchmarking, multicast and the reserved range with broadcast. IPv6 addresses must be global unicast
 * ({@code 2000::/3}) outside the IETF assignments with Teredo, documentation and 6to4; an IPv4 address inside an IPv6
 * address, mapped ({@code ::ffff:0:0/96}) or translated by NAT64 ({@code 64:ff9b::/96}), is judged as that IPv4
 * address, so an IPv6-only server can still reach IPv4 sites through NAT64.
 */
final class AddressPolicy {

  // pairs of a network and its prefix length
  private static final int[][] REFUSED_IPV4 = {
    { 0x00000000, 8 },
    { 0x0A000000, 8 },
    { 0x64400000, 10 },
    { 0x7F000000, 8 },
    { 0xA9FE0000, 16 },
    { 0xAC100000, 12 },
    { 0xC0000000, 24 },
    { 0xC0000200, 24 },
    { 0xC0586300, 24 },
    { 0xC0A80000, 16 },
    { 0xC6120000, 15 },
    { 0xC6336400, 24 },
    { 0xCB007100, 24 },
    { 0xE0000000, 4 },
    { 0xF0000000, 4 },
  };

  private static final int EMBEDDED_IPV4_OFFSET = 12;

  private AddressPolicy() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Checks whether an address is a public address of the internet.
   *
   * @param address the address
   * @return true if a page may connect to it without access to private networks
   */
  static boolean isPublic(final InetAddress address) {
    final byte[] bytes = address.getAddress();
    if (address instanceof Inet4Address) {
      return isPublicIpv4(toInt(bytes, 0));
    }
    // every other address is an IPv6 address of sixteen bytes
    return isPublicIpv6(bytes);
  }

  /**
   * Checks whether an IPv4 address is public.
   *
   * @param address the address as a big-endian number
   * @return true if it is in none of the refused ranges
   */
  static boolean isPublicIpv4(final int address) {
    for (final int[] range : REFUSED_IPV4) {
      final int mask = -1 << (Integer.SIZE - range[1]);
      if ((address & mask) == range[0]) {
        return false;
      }
    }
    return true;
  }

  /**
   * Checks whether an IPv6 address is public.
   *
   * @param bytes the sixteen bytes of the address
   * @return true if it is global unicast outside the refused ranges, or embeds a public IPv4 address
   */
  static boolean isPublicIpv6(final byte[] bytes) {
    final int first = toInt(bytes, 0);
    final int second = toInt(bytes, 4);
    final int third = toInt(bytes, 8);
    final boolean mapped = first == 0 && second == 0 && third == 0x0000FFFF;
    final boolean nat64 = first == 0x0064FF9B && second == 0 && third == 0;
    if (mapped || nat64) {
      return isPublicIpv4(toInt(bytes, EMBEDDED_IPV4_OFFSET));
    }
    final boolean globalUnicast = (first & 0xE0000000) == 0x20000000;
    final boolean ietf = (first & 0xFFFFFE00) == 0x20010000;
    final boolean documentation = first == 0x20010DB8 || (first & 0xFFFFF000) == 0x3FFF0000;
    final boolean sixToFour = (first & 0xFFFF0000) == 0x20020000;
    return globalUnicast && !ietf && !documentation && !sixToFour;
  }

  private static int toInt(final byte[] bytes, final int offset) {
    return (
      ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16) | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF)
    );
  }
}
