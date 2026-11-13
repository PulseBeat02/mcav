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

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import me.brandonli.mcav.browser.AddressPolicy.TranslationPrefix;
import org.junit.jupiter.api.Tag;

@Tag("fuzz")
final class AddressPolicyFuzzTest {

  private static final int IPV6_BYTES = 16;

  private static final int WELL_KNOWN_NAT64_PREFIX = 0x0064FF9B;

  @FuzzTest(maxDuration = "30s")
  void shouldNeverLetAPrefixLearnedFromTheNetworkOpenAnAddressOutsideNat64Space(final FuzzedDataProvider data) throws UnknownHostException {
    final byte[] bytes = data.consumeRemainingAsBytes();
    if (bytes.length < IPV6_BYTES) {
      return;
    }

    final byte[] judgedBytes = Arrays.copyOf(bytes, IPV6_BYTES);
    final InetAddress judged = InetAddress.getByAddress(judgedBytes);
    final InetAddress[] answer = this.readAnswer(bytes);
    final List<TranslationPrefix> prefixes = AddressPolicy.findTranslationPrefixes(answer);
    final boolean publicWithPrefixes = AddressPolicy.isPublic(judged, prefixes);
    final boolean publicWithoutPrefixes = AddressPolicy.isPublic(judged);
    if (publicWithPrefixes && !publicWithoutPrefixes) {
      final int firstWord = ByteBuffer.wrap(judgedBytes).getInt();
      assertEquals(WELL_KNOWN_NAT64_PREFIX, firstWord);
    }
  }

  private InetAddress[] readAnswer(final byte[] bytes) throws UnknownHostException {
    final List<InetAddress> answer = new ArrayList<>();
    for (int offset = IPV6_BYTES; offset + Integer.BYTES <= bytes.length; offset += IPV6_BYTES) {
      final int remaining = bytes.length - offset;
      final int length = remaining >= IPV6_BYTES ? IPV6_BYTES : Integer.BYTES;
      final byte[] address = Arrays.copyOfRange(bytes, offset, offset + length);
      answer.add(InetAddress.getByAddress(address));
    }

    return answer.toArray(InetAddress[]::new);
  }
}
