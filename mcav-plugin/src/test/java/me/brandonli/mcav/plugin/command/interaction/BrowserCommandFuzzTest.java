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
package me.brandonli.mcav.plugin.command.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import me.brandonli.mcav.plugin.MCAVSandbox;
import org.junit.jupiter.api.Tag;

@Tag("fuzz")
final class BrowserCommandFuzzTest {

  private static final Set<String> WEB_SCHEMES = Set.of("http", "https");

  private static final BrowserCommand COMMAND = new BrowserCommand(mock(MCAVSandbox.class));

  @FuzzTest(maxDuration = "30s")
  void shouldOpenOnlyWebPagesOrRefuseEveryTypedAddress(final FuzzedDataProvider data) {
    final String url = data.consumeRemainingAsString();
    final URI uri = COMMAND.parseWebAddress(url);
    if (uri == null) {
      return;
    }

    final String scheme = uri.getScheme();
    final String lowerScheme = scheme.toLowerCase(Locale.ROOT);
    final boolean webScheme = WEB_SCHEMES.contains(lowerScheme);
    final String host = uri.getHost();
    final String address = uri.toString();
    assertTrue(webScheme, url);
    assertNotNull(host, url);
    assertEquals(url, address);
  }
}
