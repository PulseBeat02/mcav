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

final class AddressPolicyPropertyTest {

  private static final int IPV6_ADDRESS_BYTES = 16;

  private static final int FIRST_DISCOVERY_ADDRESS = 0xC00000AA;
  private static final int SECOND_DISCOVERY_ADDRESS = 0xC00000AB;

  private static final int MAPPED_IPV4_MARKER = 0x0000FFFF;

  private static final int WELL_KNOWN_NAT64_PREFIX = 0x0064FF9B;

  private static final int[] TRANSLATION_PREFIX_LENGTHS = { 32, 40, 48, 56, 64, 96 };

  private static final String SEED = "20260925";

  private byte[] ipv6(final int first, final int second, final int third, final int fourth) {
    return ByteBuffer.allocate(IPV6_ADDRESS_BYTES).putInt(first).putInt(second).putInt(third).putInt(fourth).array();
  }

  private InetAddress translate(final byte[] prefix, final int length, final int ipv4) throws UnknownHostException {
    final byte[] bytes = new byte[IPV6_ADDRESS_BYTES];
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
  void shouldJudgeAnAddressBehindTheNat64PrefixOfTheNetworkByItsIpv4Address(
    @ForAll @Size(value = 16) final byte[] prefix,
    @ForAll @IntRange(min = 0, max = 5) final int lengthIndex,
    @ForAll final int ipv4
  ) throws UnknownHostException {
    final int length = TRANSLATION_PREFIX_LENGTHS[lengthIndex];
    prefix[0] = (byte) (0x20 | (prefix[0] & 0x1F));
    final InetAddress[] answer = {
      this.translate(prefix, length, FIRST_DISCOVERY_ADDRESS),
      this.translate(prefix, length, SECOND_DISCOVERY_ADDRESS),
    };
    final List<AddressPolicy.TranslationPrefix> prefixes = AddressPolicy.findTranslationPrefixes(answer);
    if (prefixes.size() == 1) {
      final boolean translatedJudgedPublic = AddressPolicy.isPublic(this.translate(prefix, length, ipv4), prefixes);
      final boolean expectedJudgedPublic = AddressPolicy.isPublicIpv4(ipv4);
      assertEquals(expectedJudgedPublic, translatedJudgedPublic);
    }

    final byte[] ordinary = prefix.clone();
    ordinary[0] = 0x2A;
    final InetAddress[] ordinaryAnswer = {
      this.translate(ordinary, length, FIRST_DISCOVERY_ADDRESS),
      this.translate(ordinary, length, SECOND_DISCOVERY_ADDRESS),
    };
    final List<AddressPolicy.TranslationPrefix> discovered = AddressPolicy.findTranslationPrefixes(ordinaryAnswer);
    final List<AddressPolicy.TranslationPrefix> matching = discovered
      .stream()
      .filter(found -> found.getLength() == length && found.contains(ordinary))
      .toList();
    final int matchingCount = matching.size();
    assertEquals(1, matchingCount, "the generated ordinary prefix is discovered exactly once");

    final boolean ordinaryTranslatedJudgedPublic = AddressPolicy.isPublic(this.translate(ordinary, length, ipv4), matching);
    final boolean expectedOrdinaryJudgedPublic = AddressPolicy.isPublicIpv4(ipv4);
    assertEquals(expectedOrdinaryJudgedPublic, ordinaryTranslatedJudgedPublic);
  }

  @Property(seed = SEED, tries = 2000)
  void shouldNeverTranslateAPrefixOutsideGlobalUnicastAndWellKnownNat64Space(
    @ForAll @Size(value = 16) final byte[] prefix,
    @ForAll @IntRange(min = 0, max = 5) final int lengthIndex
  ) throws UnknownHostException {
    final int length = TRANSLATION_PREFIX_LENGTHS[lengthIndex];
    if ((prefix[0] & 0xE0) == 0x20) {
      prefix[0] = (byte) (prefix[0] ^ 0x80);
    }

    final boolean wellKnown = prefix[0] == 0 && prefix[1] == 0x64 && prefix[2] == (byte) 0xFF && prefix[3] == (byte) 0x9B;
    final InetAddress[] answer = {
      this.translate(prefix, length, FIRST_DISCOVERY_ADDRESS),
      this.translate(prefix, length, SECOND_DISCOVERY_ADDRESS),
    };
    if (!wellKnown) {
      final List<AddressPolicy.TranslationPrefix> translationPrefixes = AddressPolicy.findTranslationPrefixes(answer);
      assertEquals(List.of(), translationPrefixes);
    }
  }

  @Property(seed = SEED, tries = 2000)
  void shouldJudgeAnIpv4AddressTheSameMappedOrTranslated(@ForAll final int address) {
    final boolean plain = AddressPolicy.isPublicIpv4(address);
    final boolean mappedJudgedPublic = AddressPolicy.isPublicIpv6(this.ipv6(0, 0, MAPPED_IPV4_MARKER, address));
    assertEquals(plain, mappedJudgedPublic, "mapped");

    final boolean translatedJudgedPublic = AddressPolicy.isPublicIpv6(this.ipv6(WELL_KNOWN_NAT64_PREFIX, 0, 0, address));
    assertEquals(plain, translatedJudgedPublic, "translated by NAT64");
  }

  @Property(seed = SEED, tries = 2000)
  void shouldNeverTreatWhatJavaCallsLocalOrMulticastAsPublic(
    @ForAll @Size(value = 4) final byte[] ipv4,
    @ForAll @Size(value = 16) final byte[] ipv6
  ) throws UnknownHostException {
    for (final byte[] bytes : new byte[][] { ipv4, ipv6 }) {
      final InetAddress address = InetAddress.getByAddress(bytes);
      final boolean local =
        address.isAnyLocalAddress() ||
        address.isLoopbackAddress() ||
        address.isLinkLocalAddress() ||
        address.isSiteLocalAddress() ||
        address.isMulticastAddress();
      if (local) {
        final boolean judgedPublic = AddressPolicy.isPublic(address);
        assertFalse(judgedPublic, address::toString);
      }
    }
  }

  @Property(seed = SEED, tries = 2000)
  void shouldTreatNothingOutsideIpv6GlobalUnicastAsPublicUnlessItCarriesIpv4(@ForAll @Size(value = 16) final byte[] bytes) {
    final ByteBuffer buffer = ByteBuffer.wrap(bytes);
    final int first = buffer.getInt();
    final int second = buffer.getInt();
    final int third = buffer.getInt();
    final boolean mapped = first == 0 && second == 0 && third == MAPPED_IPV4_MARKER;
    final boolean translated = first == WELL_KNOWN_NAT64_PREFIX && second == 0 && third == 0;
    final boolean inGlobalUnicastRange = (first & 0xE0000000) == 0x20000000;
    if (!inGlobalUnicastRange && !mapped && !translated) {
      final boolean judgedPublic = AddressPolicy.isPublicIpv6(bytes);
      assertFalse(judgedPublic);
    }
  }
}
