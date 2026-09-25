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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
 *
 * <p>A network may translate with a prefix of its own instead of {@code 64:ff9b::/96}, and its addresses look like any
 * global unicast address. Its resolver reveals such prefixes when asked for {@value #IPV4_ONLY_HOST} (RFC 7050): it
 * answers with IPv6 addresses that embed the well-known IPv4 addresses of that name. {@link #findTranslationPrefixes}
 * reads the prefixes from that answer, and an address inside one of them is judged by the IPv4 address it embeds.
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

  /**
   * The name whose IPv6 answers show the NAT64 prefixes of the network (RFC 7050).
   */
  static final String IPV4_ONLY_HOST = "ipv4only.arpa";

  // the IPv4 addresses of ipv4only.arpa, 192.0.0.170 and 192.0.0.171
  private static final int[] WELL_KNOWN_IPV4 = { 0xC00000AA, 0xC00000AB };

  // the prefix lengths of RFC 6052, each with its own place for the IPv4 address
  private static final int[] TRANSLATION_PREFIX_LENGTHS = { 32, 40, 48, 56, 64, 96 };

  // the byte of an IPv6 address that RFC 6052 keeps zero and skips when it embeds an IPv4 address
  private static final int U_OCTET = 8;

  private static final int IPV6_BYTES = 16;

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
   * Checks whether an address is a public address of the internet on a network that translates IPv6 to IPv4 with
   * prefixes of its own: an address inside such a prefix is judged by the IPv4 address it embeds.
   *
   * @param address  the address
   * @param prefixes the NAT64 prefixes of the network
   * @return true if a page may connect to it without access to private networks
   */
  static boolean isPublic(final InetAddress address, final List<TranslationPrefix> prefixes) {
    final byte[] bytes = address.getAddress();
    if (address instanceof Inet4Address) {
      return isPublicIpv4(toInt(bytes, 0));
    }
    boolean translated = false;
    for (final TranslationPrefix prefix : prefixes) {
      if (prefix.contains(bytes)) {
        if (!isPublicIpv4(embeddedIpv4(bytes, prefix.getLength()))) {
          return false;
        }
        translated = true;
      }
    }
    return translated || isPublicIpv6(bytes);
  }

  /**
   * Finds the NAT64 prefixes of the network in the answer of its resolver for {@value #IPV4_ONLY_HOST}: every IPv6
   * address that embeds one of the name's IPv4 addresses where RFC 6052 puts it for a prefix length gives a prefix of
   * that length. IPv4 addresses in the answer are skipped.
   *
   * @param answer the addresses of {@value #IPV4_ONLY_HOST}
   * @return the prefixes, each once
   */
  static List<TranslationPrefix> findTranslationPrefixes(final InetAddress[] answer) {
    final List<TranslationPrefix> prefixes = new ArrayList<>();
    for (final InetAddress address : answer) {
      final byte[] bytes = address.getAddress();
      if (bytes.length != IPV6_BYTES) {
        continue;
      }
      for (final int length : TRANSLATION_PREFIX_LENGTHS) {
        // the byte RFC 6052 keeps zero lies after every prefix up to 64 bits
        final boolean uOctetAfterPrefix = length / Byte.SIZE <= U_OCTET;
        if (uOctetAfterPrefix && bytes[U_OCTET] != 0) {
          continue;
        }
        final int embedded = embeddedIpv4(bytes, length);
        if (embedded != WELL_KNOWN_IPV4[0] && embedded != WELL_KNOWN_IPV4[1]) {
          continue;
        }
        final TranslationPrefix prefix = new TranslationPrefix(bytes, length);
        if (prefixes.stream().noneMatch(prefix::isSameAs)) {
          prefixes.add(prefix);
        }
      }
    }
    return prefixes;
  }

  /**
   * Reads the IPv4 address an IPv6 address embeds after a NAT64 prefix of a length of RFC 6052: the four bytes after
   * the prefix, skipping the byte RFC 6052 keeps zero.
   *
   * @param bytes        the sixteen bytes of the address
   * @param prefixLength the length of the prefix in bits, one of 32, 40, 48, 56, 64 and 96
   * @return the IPv4 address as a big-endian number
   */
  static int embeddedIpv4(final byte[] bytes, final int prefixLength) {
    int address = 0;
    int index = prefixLength / Byte.SIZE;
    for (int taken = 0; taken < Integer.BYTES; taken++) {
      if (index == U_OCTET) {
        index++;
      }
      address = (address << Byte.SIZE) | (bytes[index] & 0xFF);
      index++;
    }
    return address;
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

  /**
   * A NAT64 prefix of the network: an IPv6 address that begins with it embeds an IPv4 address where RFC 6052 puts it
   * for the length of the prefix.
   */
  static final class TranslationPrefix {

    private final byte[] bytes;
    private final int length;

    /**
     * Takes a prefix from the start of an address.
     *
     * @param address the sixteen bytes of an address inside the prefix
     * @param length  the length of the prefix in bits, a multiple of eight
     */
    TranslationPrefix(final byte[] address, final int length) {
      this.bytes = Arrays.copyOf(address, length / Byte.SIZE);
      this.length = length;
    }

    /**
     * Gets the length of the prefix.
     *
     * @return the length in bits
     */
    int getLength() {
      return this.length;
    }

    /**
     * Checks whether an IPv6 address begins with the prefix.
     *
     * @param address the sixteen bytes of the address
     * @return true if the address is inside the prefix
     */
    boolean contains(final byte[] address) {
      return Arrays.equals(this.bytes, 0, this.bytes.length, address, 0, this.bytes.length);
    }

    /**
     * Checks whether another prefix has the same bits and length.
     *
     * @param other the other prefix
     * @return true if both are the same prefix
     */
    boolean isSameAs(final TranslationPrefix other) {
      return this.length == other.length && Arrays.equals(this.bytes, other.bytes);
    }
  }
}
