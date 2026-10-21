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
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Public unicast addresses only, as a page could otherwise reach private networks or a cloud metadata service. IPv4
// inside IPv6 (mapped, or translated by NAT64 with the well-known or a network's own prefix) is judged as IPv4. A network
// prefix counts only in global unicast or well-known NAT64 space, so a forged answer cannot make refused addresses pass.
final class AddressPolicy {

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

  // its IPv6 answers show the NAT64 prefixes of the network (RFC 7050)
  static final String IPV4_ONLY_HOST = "ipv4only.arpa";

  // 192.0.0.170 and 192.0.0.171
  private static final int[] WELL_KNOWN_IPV4 = { 0xC00000AA, 0xC00000AB };

  // RFC 6052, each with its own place for the IPv4 address
  private static final int[] TRANSLATION_PREFIX_LENGTHS = { 32, 40, 48, 56, 64, 96 };

  // RFC 6052 keeps this byte zero and skips it when it embeds an IPv4 address
  private static final int U_OCTET = 8;

  private static final int IPV6_BYTES = 16;

  private static final int UNSIGNED_BYTE_MASK = 0xFF;

  private static final int SECOND_WORD_OFFSET = 4;
  private static final int THIRD_WORD_OFFSET = 8;

  // the third 32 bits of ::ffff:0:0/96
  private static final int IPV4_MAPPED_WORD = 0x0000FFFF;

  private static final int GLOBAL_UNICAST_MASK = 0xE0000000;
  private static final int GLOBAL_UNICAST_PREFIX = 0x20000000;

  private static final int IETF_MASK = 0xFFFFFE00;
  private static final int IETF_PREFIX = 0x20010000;

  private static final int DOCUMENTATION_PREFIX = 0x20010DB8;
  private static final int WIDE_DOCUMENTATION_MASK = 0xFFFFF000;
  private static final int WIDE_DOCUMENTATION_PREFIX = 0x3FFF0000;

  private static final int SIX_TO_FOUR_MASK = 0xFFFF0000;
  private static final int SIX_TO_FOUR_PREFIX = 0x20020000;

  // the first 32 bits of the well-known NAT64 prefix 64:ff9b::/96 (RFC 6052) and of 64:ff9b:1::/48 (RFC 8215)
  private static final int WELL_KNOWN_NAT64 = 0x0064FF9B;

  private AddressPolicy() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  static boolean isPublic(final InetAddress address) {
    final byte[] bytes = address.getAddress();
    if (address instanceof Inet4Address) {
      return isPublicIpv4(toInt(bytes, 0));
    }

    return isPublicIpv6(bytes);
  }

  static boolean isPublic(final InetAddress address, final List<TranslationPrefix> prefixes) {
    final byte[] bytes = address.getAddress();
    if (address instanceof Inet4Address) {
      return isPublicIpv4(toInt(bytes, 0));
    }

    boolean translated = false;
    for (final TranslationPrefix prefix : prefixes) {
      if (!prefix.contains(bytes)) {
        continue;
      }
      if (!isPublicIpv4(embeddedIpv4(bytes, prefix.getLength()))) {
        return false;
      }
      translated = true;
    }

    return translated || isPublicIpv6(bytes);
  }

  // an IPv6 address embedding a well-known IPv4 address where RFC 6052 puts it for a length gives a prefix of that length
  static List<TranslationPrefix> findTranslationPrefixes(final InetAddress[] answer) {
    final List<TranslationPrefix> prefixes = new ArrayList<>();
    for (final InetAddress address : answer) {
      final byte[] bytes = address.getAddress();
      if (bytes.length != IPV6_BYTES || !isTranslationNetwork(bytes)) {
        continue;
      }
      addTranslationPrefixes(bytes, prefixes);
    }

    return prefixes;
  }

  private static void addTranslationPrefixes(final byte[] bytes, final List<TranslationPrefix> prefixes) {
    for (final int length : TRANSLATION_PREFIX_LENGTHS) {
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

  private static int embeddedIpv4(final byte[] bytes, final int prefixLength) {
    int address = 0;
    int index = prefixLength / Byte.SIZE;
    for (int taken = 0; taken < Integer.BYTES; taken++) {
      if (index == U_OCTET) {
        index++;
      }
      address = (address << Byte.SIZE) | (bytes[index] & UNSIGNED_BYTE_MASK);
      index++;
    }

    return address;
  }

  static boolean isPublicIpv4(final int address) {
    for (final int[] range : REFUSED_IPV4) {
      final int mask = -1 << (Integer.SIZE - range[1]);
      if ((address & mask) == range[0]) {
        return false;
      }
    }

    return true;
  }

  static boolean isPublicIpv6(final byte[] bytes) {
    final int first = toInt(bytes, 0);
    final int second = toInt(bytes, SECOND_WORD_OFFSET);
    final int third = toInt(bytes, THIRD_WORD_OFFSET);
    final boolean mapped = first == 0 && second == 0 && third == IPV4_MAPPED_WORD;
    final boolean inNat64Prefix = first == WELL_KNOWN_NAT64 && second == 0 && third == 0;
    if (mapped || inNat64Prefix) {
      return isPublicIpv4(toInt(bytes, EMBEDDED_IPV4_OFFSET));
    }

    return isGlobalUnicast(first);
  }

  private static boolean isTranslationNetwork(final byte[] bytes) {
    final int first = toInt(bytes, 0);
    return first == WELL_KNOWN_NAT64 || isGlobalUnicast(first);
  }

  private static boolean isGlobalUnicast(final int first) {
    final boolean inGlobalUnicastRange = (first & GLOBAL_UNICAST_MASK) == GLOBAL_UNICAST_PREFIX;
    final boolean inIetfRange = (first & IETF_MASK) == IETF_PREFIX;
    final boolean inDocumentationRange = first == DOCUMENTATION_PREFIX || (first & WIDE_DOCUMENTATION_MASK) == WIDE_DOCUMENTATION_PREFIX;
    final boolean inSixToFourRange = (first & SIX_TO_FOUR_MASK) == SIX_TO_FOUR_PREFIX;
    return inGlobalUnicastRange && !inIetfRange && !inDocumentationRange && !inSixToFourRange;
  }

  private static int toInt(final byte[] bytes, final int offset) {
    final ByteBuffer word = ByteBuffer.wrap(bytes, offset, Integer.BYTES);
    return word.getInt();
  }

  static final class TranslationPrefix {

    private final byte[] bytes;
    private final int length;

    private TranslationPrefix(final byte[] address, final int length) {
      this.bytes = Arrays.copyOf(address, length / Byte.SIZE);
      this.length = length;
    }

    boolean contains(final byte[] address) {
      return Arrays.equals(this.bytes, 0, this.bytes.length, address, 0, this.bytes.length);
    }

    private boolean isSameAs(final TranslationPrefix other) {
      return this.length == other.length && Arrays.equals(this.bytes, other.bytes);
    }

    int getLength() {
      return this.length;
    }
  }
}
