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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import me.brandonli.mcav.browser.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
