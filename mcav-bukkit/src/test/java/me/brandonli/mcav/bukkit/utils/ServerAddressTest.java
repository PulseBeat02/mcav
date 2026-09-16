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
package me.brandonli.mcav.bukkit.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.testing.LocalHttpServer;
import me.brandonli.mcav.bukkit.testing.UtilityClassAssertions;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Tests {@link ServerAddress}.
 */
final class ServerAddressTest {

  private FakeServer server;
  private LocalHttpServer http;

  @BeforeEach
  void startServers() throws ReflectiveOperationException {
    clearCachedAddress();
    this.server = FakeServer.start();
    this.http = LocalHttpServer.start();
  }

  @AfterEach
  void stopServers() throws ReflectiveOperationException {
    this.http.close();
    this.server.close();
    clearCachedAddress();
  }

  private static void clearCachedAddress() throws ReflectiveOperationException {
    final Field field = ServerAddress.class.getDeclaredField("PUBLIC_ADDRESS");
    field.setAccessible(true);
    field.set(null, null);
  }

  private void configureServerAddress(final String address) {
    final MockedStatic<Bukkit> bukkit = this.server.getBukkit();
    bukkit.when(Bukkit::getIp).thenReturn(address);
  }

  @Test
  void prefersTheAddressConfiguredInTheServerProperties() {
    this.configureServerAddress("10.0.0.5");
    final String address = ServerAddress.getPublicIPAddress();
    assertEquals("10.0.0.5", address);
  }

  @Test
  void looksUpThePublicAddressOnceWhenNoAddressIsConfigured() {
    this.configureServerAddress("");
    final byte[] body = " 203.0.113.7\n".getBytes(StandardCharsets.US_ASCII);
    this.http.respond("/ip", 200, body);
    final URI service = this.http.uri("/ip");
    final URI otherService = this.http.uri("/other");
    final String first = ServerAddress.getPublicIPAddress(service);
    final String second = ServerAddress.getPublicIPAddress(otherService);
    final int requests = this.http.getRequestCount("/ip");
    final int otherRequests = this.http.getRequestCount("/other");
    assertEquals("203.0.113.7", first);
    assertEquals("203.0.113.7", second);
    assertEquals(1, requests, "the address is cached");
    assertEquals(0, otherRequests);
  }

  @Test
  void treatsTheWildcardAddressAsNotConfigured() {
    this.configureServerAddress("0.0.0.0");
    final byte[] body = "2001:db8::1".getBytes(StandardCharsets.US_ASCII);
    this.http.respond("/ip", 200, body);
    final URI service = this.http.uri("/ip");
    final String address = ServerAddress.getPublicIPAddress(service);
    assertEquals("2001:db8::1", address);
  }

  @Test
  void fallsBackToLocalhostWithoutCachingItWhenTheLookupFails() {
    this.configureServerAddress("");
    final byte[] error = "unavailable".getBytes(StandardCharsets.US_ASCII);
    final byte[] address = "203.0.113.7".getBytes(StandardCharsets.US_ASCII);
    this.http.respond("/ip", 503, error);
    this.http.respond("/ip", 200, address);
    final URI service = this.http.uri("/ip");
    final String failed = ServerAddress.getPublicIPAddress(service);
    final String retried = ServerAddress.getPublicIPAddress(service);
    final String cached = ServerAddress.getPublicIPAddress(service);
    final int requests = this.http.getRequestCount("/ip");
    final boolean failedIsFallback = ServerAddress.isFallbackAddress(failed);
    assertEquals("localhost", failed);
    assertTrue(failedIsFallback);
    assertEquals("203.0.113.7", retried, "a transient failure is not remembered");
    assertEquals("203.0.113.7", cached);
    assertEquals(2, requests, "only the successful lookup is cached");
  }

  @Test
  void requiresAnAddressService() {
    this.configureServerAddress("10.0.0.5");
    assertThrows(NullPointerException.class, () -> ServerAddress.getPublicIPAddress(null));
  }

  @Test
  void recognizesOnlyTheFallbackAddress() {
    final boolean localhost = ServerAddress.isFallbackAddress("localhost");
    final boolean publicAddress = ServerAddress.isFallbackAddress("203.0.113.7");
    final boolean loopback = ServerAddress.isFallbackAddress("127.0.0.1");
    assertTrue(localhost);
    assertFalse(publicAddress);
    assertFalse(loopback);
    assertThrows(NullPointerException.class, () -> ServerAddress.isFallbackAddress(null));
  }

  @Test
  void cannotBeInstantiated() {
    UtilityClassAssertions.assertNotInstantiable(ServerAddress.class);
  }
}
