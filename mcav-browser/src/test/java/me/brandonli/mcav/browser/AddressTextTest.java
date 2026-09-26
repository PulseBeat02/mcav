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

import me.brandonli.mcav.browser.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests {@link AddressText}.
 */
final class AddressTextTest {

  @ParameterizedTest
  @CsvSource(
    delimiter = '|',
    value = {
      "https://example.com/page|https://example.com/page",
      "https://example.com|https://example.com",
      "https://example.com:8443/a/b|https://example.com:8443/a/b",
      "file:///etc/passwd|file:///etc/passwd",
      "chrome://settings|chrome://settings",
      "https://example.com/callback?code=secret&state=1|https://example.com/callback?<hidden>",
      "https://example.com/page#access_token=secret|https://example.com/page#<hidden>",
      "https://example.com/page#frag?not=query|https://example.com/page#<hidden>",
      "https://example.com?sig=secret|https://example.com?<hidden>",
      "https://user:password@example.com/page|https://<hidden>@example.com/page",
      "https://user@example.com|https://<hidden>@example.com",
      "https://a@b@example.com/x?y|https://<hidden>@example.com/x?<hidden>",
      "http://user@|http://<hidden>@",
      "http:///path@thing|http:///path@thing",
      "x@y:///p|<hidden>",
      "1http://example.com|<hidden>",
      "a?b:c|<hidden>",
      "ht#tp://example.com|<hidden>",
      "http:/example.com?x|http:<hidden>",
      "myapp://callback?code=secret|myapp://callback?<hidden>",
      "data:text/html,<script>https://example.com</script>|data:<hidden>",
      "blob:https://example.com/uuid|blob:<hidden>",
      "javascript:alert(1)|javascript:<hidden>",
      "about:blank|about:<hidden>",
      "no address|<hidden>",
      "://example.com|<hidden>",
      "HTTPS://Example.com/x?y|HTTPS://Example.com/x?<hidden>",
      "svn+ssh://host/repo?x|svn+ssh://host/repo?<hidden>",
      "coap.tcp://host|coap.tcp://host",
      "x-y://host|x-y://host",
      "Z9://host|Z9://host",
      "a_b://host|<hidden>",
      "{x://host|<hidden>",
      "a{b://host|<hidden>",
      "a b://host|<hidden>",
      "@x://host|<hidden>",
      "`x://host|<hidden>",
    }
  )
  void describesAnAddressWithoutThePartsThatMayHoldSecrets(final String address, final String described) {
    assertEquals(described, AddressText.describe(address));
  }

  @Test
  void anEmptyTextIsHidden() {
    assertEquals(AddressText.HIDDEN, AddressText.describe(""));
  }

  @Test
  void theTextIsNotInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(AddressText.class);
  }
}
