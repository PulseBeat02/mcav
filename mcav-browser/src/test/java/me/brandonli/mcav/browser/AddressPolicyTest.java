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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import me.brandonli.mcav.browser.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AddressPolicyTest {

  @ParameterizedTest
  @ValueSource(
    strings = {
      "0.0.0.0",
      "0.1.2.3",
      "10.0.0.1",
      "10.255.255.255",
      "100.64.0.1",
      "100.127.255.255",
      "127.0.0.1",
      "127.1.2.3",
      "169.254.169.254",
      "172.16.0.1",
      "172.31.255.255",
      "192.0.0.8",
      "192.0.2.1",
      "192.88.99.1",
      "192.168.1.1",
      "198.18.0.1",
      "198.19.255.255",
      "198.51.100.7",
      "203.0.113.9",
      "224.0.0.1",
      "239.255.255.250",
      "240.0.0.1",
      "255.255.255.255",
      "::",
      "::1",
      "::a00:1",
      "100::1",
      "64:ff9b::a00:1",
      "64:ff9b::7f00:1",
      "64:ff9b:1::808:808",
      "2001::1",
      "2001:1ff::1",
      "2001:db8::1",
      "2002:c000:204::1",
      "3fff::1",
      "fc00::1",
      "fd12:3456::1",
      "fe80::1",
      "fec0::1",
      "ff02::1",
    }
  )
  void specialAddressesAreNotPublic(final String text) throws UnknownHostException {
    assertFalse(AddressPolicy.isPublic(InetAddress.getByName(text)), text);
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "1.1.1.1",
      "8.8.8.8",
      "100.63.255.255",
      "100.128.0.1",
      "172.15.255.255",
      "172.32.0.1",
      "192.0.1.1",
      "192.167.255.255",
      "192.169.0.1",
      "198.17.255.255",
      "198.20.0.1",
      "223.255.255.254",
      "2001:200::1",
      "2606:4700:4700::1111",
      "2a00:1450:4001::200e",
      "64:ff9b::808:808",
    }
  )
  void publicAddressesArePublic(final String text) throws UnknownHostException {
    assertTrue(AddressPolicy.isPublic(InetAddress.getByName(text)), text);
  }

  @Test
  void onlyTheExactPrefixesCarryAnIpv4Address() {
    // 0:0:1::808:808 is neither mapped nor translated
    assertFalse(AddressPolicy.isPublicIpv6(new byte[] { 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 8, 8, 8, 8 }));
    // 64:ff9b:0:1::808:808 is not translated either
    assertFalse(AddressPolicy.isPublicIpv6(new byte[] { 0, 0x64, (byte) 0xFF, (byte) 0x9B, 0, 0, 0, 1, 0, 0, 0, 0, 8, 8, 8, 8 }));
    // 64:ff9b::1:0:808:808
    assertFalse(AddressPolicy.isPublicIpv6(new byte[] { 0, 0x64, (byte) 0xFF, (byte) 0x9B, 0, 0, 0, 0, 0, 0, 0, 1, 8, 8, 8, 8 }));
  }

  private static InetAddress[] addresses(final String... texts) throws UnknownHostException {
    final InetAddress[] addresses = new InetAddress[texts.length];
    for (int index = 0; index < texts.length; index++) {
      addresses[index] = InetAddress.getByName(texts[index]);
    }
    return addresses;
  }

  private static boolean isPublic(final String text, final List<AddressPolicy.TranslationPrefix> prefixes) throws UnknownHostException {
    return AddressPolicy.isPublic(InetAddress.getByName(text), prefixes);
  }

  // the addresses a translator with the network-specific prefix 2a01:4f8:1:2:3:4::/<length> gives 192.0.0.170,
  // 192.0.0.171, 10.0.0.1, 169.254.169.254 and 8.8.8.8, laid out as in RFC 6052 section 2.2
  @ParameterizedTest
  @CsvSource(
    {
      "32, 2a01:4f8:c000:aa::, 2a01:4f8:c000:ab::, 2a01:4f8:a00:1::, 2a01:4f8:a9fe:a9fe::, 2a01:4f8:808:808::",
      "40, 2a01:4f8:c0:0:aa::, 2a01:4f8:c0:0:ab::, 2a01:4f8:a:0:1::, 2a01:4f8:a9:fea9:fe::, 2a01:4f8:8:808:8::",
      "48, 2a01:4f8:1:c000:0:aa00::, 2a01:4f8:1:c000:0:ab00::, 2a01:4f8:1:a00:0:100::, 2a01:4f8:1:a9fe:a9:fe00::, 2a01:4f8:1:808:8:800::",
      "56, 2a01:4f8:1:c0:0:aa::, 2a01:4f8:1:c0:0:ab::, 2a01:4f8:1:a:0:1::, 2a01:4f8:1:a9:fe:a9fe::, 2a01:4f8:1:8:8:808::",
      "64, 2a01:4f8:1:2:c0:0:aa00:0, 2a01:4f8:1:2:c0:0:ab00:0, 2a01:4f8:1:2:a:0:100:0, 2a01:4f8:1:2:a9:fea9:fe00:0, 2a01:4f8:1:2:8:808:800:0",
      "96, 2a01:4f8:1:2:3:4:c000:aa, 2a01:4f8:1:2:3:4:c000:ab, 2a01:4f8:1:2:3:4:a00:1, 2a01:4f8:1:2:3:4:a9fe:a9fe, 2a01:4f8:1:2:3:4:808:808",
    }
  )
  void theNat64PrefixOfTheNetworkIsFoundAndItsPrivateAddressesAreRefused(
    final int length,
    final String first,
    final String second,
    final String privateAddress,
    final String metadataService,
    final String publicAddress
  ) throws UnknownHostException {
    final List<AddressPolicy.TranslationPrefix> prefixes = AddressPolicy.findTranslationPrefixes(
      addresses("192.0.0.170", first, "192.0.0.171", second)
    );
    assertEquals(1, prefixes.size(), "both well-known addresses give the same prefix");
    assertEquals(length, prefixes.getFirst().getLength());
    assertTrue(AddressPolicy.isPublic(InetAddress.getByName(privateAddress)), "without the prefix it looks like any address");
    assertFalse(isPublic(privateAddress, prefixes), privateAddress);
    assertFalse(isPublic(metadataService, prefixes), metadataService);
    assertTrue(isPublic(publicAddress, prefixes), publicAddress);
    assertTrue(isPublic("2606:4700:4700::1111", prefixes), "an address outside the prefix is judged as before");
    assertFalse(isPublic("10.0.0.1", prefixes));
    assertTrue(isPublic("8.8.8.8", prefixes));
  }

  @Test
  void aNetworkWithoutTranslationHasNoPrefix() throws UnknownHostException {
    assertEquals(0, AddressPolicy.findTranslationPrefixes(addresses("192.0.0.170", "192.0.0.171")).size());
    assertEquals(0, AddressPolicy.findTranslationPrefixes(addresses("2a01:4f8:1:2:3:4:5:6", "2a01:4f8:1:2:3:4:c000:ac")).size());
    // the byte RFC 6052 keeps zero is 1: no prefix of 64 bits, and no other length finds 192.0.0.170
    assertEquals(0, AddressPolicy.findTranslationPrefixes(addresses("2a01:4f8:1:2:1c0:0:aa00:0")).size());
  }

  @Test
  void anAddressInsideSeveralPrefixesMustEmbedAPublicAddressInEach() throws UnknownHostException {
    final List<AddressPolicy.TranslationPrefix> nested = AddressPolicy.findTranslationPrefixes(
      addresses("2a01:4f8:c000:aa::", "2a01:4f8:1:2:3:4:c000:aa")
    );
    assertEquals(2, nested.size());
    // inside both: 8.8.8.8 after 96 bits, but 0.1.0.2 after 32 bits
    assertFalse(isPublic("2a01:4f8:1:2:3:4:808:808", nested));
    final List<AddressPolicy.TranslationPrefix> separate = AddressPolicy.findTranslationPrefixes(
      addresses("2a01:4f8:1:2:3:4:c000:aa", "2a01:4f9:1:2:3:4:c000:aa")
    );
    assertEquals(2, separate.size());
    assertTrue(isPublic("2a01:4f9:1:2:3:4:808:808", separate));
    assertFalse(isPublic("2a01:4f9:1:2:3:4:a00:1", separate));
  }

  @Test
  void thePolicyIsNotInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(AddressPolicy.class);
  }

  @Test
  void anIpv4AddressMappedIntoIpv6IsJudgedAsItself() {
    final byte[] mappedPublic = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 8, 8, 8, 8 };
    final byte[] mappedLoopback = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 127, 0, 0, 1 };
    assertTrue(AddressPolicy.isPublicIpv6(mappedPublic));
    assertFalse(AddressPolicy.isPublicIpv6(mappedLoopback));
    final byte[] almostMapped = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, (byte) 0xFF, (byte) 0xFF, 8, 8, 8, 8 };
    assertFalse(AddressPolicy.isPublicIpv6(almostMapped));
    assertFalse(AddressPolicy.isPublicIpv4(0x7F000001));
  }
}
