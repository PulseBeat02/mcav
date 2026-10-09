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
package me.brandonli.mcav.plugin.utils;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import me.brandonli.mcav.bukkit.resourcepack.provider.PackHosting;
import me.brandonli.mcav.bukkit.resourcepack.provider.WebsiteHosting;
import me.brandonli.mcav.bukkit.resourcepack.provider.http.HttpHosting;
import me.brandonli.mcav.bukkit.resourcepack.provider.netty.InjectorHosting;
import me.brandonli.mcav.bukkit.utils.ServerAddress;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link Mcv2Hosting}: each setting hosts a pack with its strategy, and an HTTP server without a host name is
 * reached at the address the server finds for itself. The strategies themselves run on Paper's Netty, which the tests
 * of the plugin do not have, so their factories stand in for them.
 */
final class Mcv2HostingTest {

  private static final Path ZIP = Path.of("mcv2", "pack", "mcav-mcv2-1.zip");

  @Test
  void hostsAPackWithTheStrategyOfTheSetting() {
    final InjectorHosting injector = mock(InjectorHosting.class);
    final HttpHosting http = mock(HttpHosting.class);
    final WebsiteHosting website = mock(WebsiteHosting.class);
    try (final MockedStatic<PackHosting> hostings = Mockito.mockStatic(PackHosting.class)) {
      hostings.when(() -> PackHosting.injector(ZIP)).thenReturn(injector);
      hostings.when(() -> PackHosting.http(ZIP, "mc.example", 8443)).thenReturn(http);
      hostings.when(() -> PackHosting.website(ZIP)).thenReturn(website);
      assertSame(injector, Mcv2Hosting.INJECTOR.hosting("", 25580).apply(ZIP));
      assertSame(http, Mcv2Hosting.HTTP.hosting("mc.example", 8443).apply(ZIP));
      assertSame(website, Mcv2Hosting.WEBSITE.hosting("", 25580).apply(ZIP));
    }
  }

  @Test
  void anHttpServerWithoutAHostNameUsesTheAddressTheServerFinds() {
    final HttpHosting http = mock(HttpHosting.class);
    try (
      final MockedStatic<PackHosting> hostings = Mockito.mockStatic(PackHosting.class);
      final MockedStatic<ServerAddress> addresses = Mockito.mockStatic(ServerAddress.class)
    ) {
      addresses.when(ServerAddress::getPublicIPAddress).thenReturn("203.0.113.9");
      hostings.when(() -> PackHosting.http(ZIP, "203.0.113.9", 25580)).thenReturn(http);
      assertSame(http, Mcv2Hosting.HTTP.hosting("", 25580).apply(ZIP));
    }
  }
}
