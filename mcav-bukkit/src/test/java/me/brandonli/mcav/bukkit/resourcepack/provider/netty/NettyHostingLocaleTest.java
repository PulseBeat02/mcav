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
package me.brandonli.mcav.bukkit.resourcepack.provider.netty;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import me.brandonli.mcav.bukkit.utils.ServerAddress;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

@ResourceLock(Resources.LOCALE)
final class NettyHostingLocaleTest {

  private static final int PORT = 25673;

  @ParameterizedTest
  @ValueSource(strings = { "ar-EG", "fa-IR", "bn-BD" })
  void listenerKeysRemainValidInEveryFormattingLocale(final String languageTag) {
    final Locale previous = Locale.getDefault(Locale.Category.FORMAT);
    try {
      Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag(languageTag));
      final NettyHosting hosting = assertDoesNotThrow(() -> new NettyHosting(Path.of("pack.zip")), "listener keys must use ASCII digits");
      assertDownloadAddress(hosting);
    } finally {
      Locale.setDefault(Locale.Category.FORMAT, previous);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = { "ar-EG", "fa-IR", "bn-BD" })
  void downloadPortsRemainAsciiAfterTheFormattingLocaleChanges(final String languageTag) {
    final Locale previous = Locale.getDefault(Locale.Category.FORMAT);
    try {
      Locale.setDefault(Locale.Category.FORMAT, Locale.ROOT);
      final NettyHosting hosting = new NettyHosting(Path.of("pack.zip"));
      Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag(languageTag));
      assertDownloadAddress(hosting);
    } finally {
      Locale.setDefault(Locale.Category.FORMAT, previous);
    }
  }

  private static void assertDownloadAddress(final NettyHosting hosting) {
    try (final MockedStatic<Bukkit> server = Mockito.mockStatic(Bukkit.class); final MockedStatic<ServerAddress> address = Mockito.mockStatic(ServerAddress.class)) {
      server.when(Bukkit::getPort).thenReturn(PORT);
      address.when(ServerAddress::getPublicIPAddress).thenReturn("127.0.0.1");
      final URI uri = URI.create(hosting.getRawUrl());
      assertEquals(PORT, uri.getPort(), "resource-pack URLs must keep a parseable ASCII port");
      assertEquals("127.0.0.1", uri.getHost());
      assertTrue(uri.getRawPath().matches("/mcav/resourcepack_[0-9]+\\.zip"));
    }
  }
}
