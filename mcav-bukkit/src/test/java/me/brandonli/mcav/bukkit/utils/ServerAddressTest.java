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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

final class ServerAddressTest {

  private static final String CONFIGURED_ADDRESS = "10.0.0.5";
  private static final String PUBLIC_ADDRESS = "203.0.113.7";
  private static final String LOCALHOST = "localhost";

  private static final String ADDRESS_PATH = "/ip";
  private static final String OTHER_PATH = "/other";
  private static final int HTTP_OK = 200;

  private FakeServer server;
  private LocalHttpServer http;

  @BeforeEach
  void startServers() throws ReflectiveOperationException {
    this.clearCachedAddress();
    this.server = FakeServer.start();
    this.http = LocalHttpServer.start();
  }

  @AfterEach
  void stopServers() throws ReflectiveOperationException {
    this.http.close();
    this.server.close();
    this.clearCachedAddress();
  }

  private void clearCachedAddress() throws ReflectiveOperationException {
    final Field field = ServerAddress.class.getDeclaredField("publicAddress");
    field.setAccessible(true);
    field.set(null, null);
  }

  private void configureServerAddress(final String address) {
    final MockedStatic<Bukkit> bukkit = this.server.getBukkit();
    bukkit.when(Bukkit::getIp).thenReturn(address);
  }

  @Test
  void shouldPreferTheAddressConfiguredInTheServerProperties() {
    this.configureServerAddress(CONFIGURED_ADDRESS);
    final String address = ServerAddress.getPublicAddress();
    assertEquals(CONFIGURED_ADDRESS, address);
  }

  @Test
  void shouldLookUpThePublicAddressOnceWhenNoAddressIsConfigured() {
    this.configureServerAddress("");
    final byte[] body = " 203.0.113.7\n".getBytes(StandardCharsets.US_ASCII);
    this.http.respond(ADDRESS_PATH, HTTP_OK, body);
    final URI service = this.http.uri(ADDRESS_PATH);
    final URI otherService = this.http.uri(OTHER_PATH);
    final String first = ServerAddress.getPublicAddress(service);
    final String second = ServerAddress.getPublicAddress(otherService);
    final int requests = this.http.getRequestCount(ADDRESS_PATH);
    final int otherRequests = this.http.getRequestCount(OTHER_PATH);
    assertEquals(PUBLIC_ADDRESS, first);
    assertEquals(PUBLIC_ADDRESS, second);
    assertEquals(1, requests, "the address is cached");
    assertEquals(0, otherRequests);
  }

  @ParameterizedTest
  @ValueSource(strings = { "0.0.0.0", "::", "0:0:0:0:0:0:0:0", "0::0" })
  void shouldTreatTheWildcardAddressAsNotConfigured(final String wildcard) {
    this.configureServerAddress(wildcard);
    final byte[] body = "2001:db8::1".getBytes(StandardCharsets.US_ASCII);
    this.http.respond(ADDRESS_PATH, HTTP_OK, body);
    final URI service = this.http.uri(ADDRESS_PATH);
    final String address = ServerAddress.getPublicAddress(service);
    assertEquals("2001:db8::1", address);
  }

  @Test
  void shouldTreatABlankAddressAsNotConfigured() {
    this.configureServerAddress(" ");
    final byte[] body = PUBLIC_ADDRESS.getBytes(StandardCharsets.US_ASCII);
    this.http.respond(ADDRESS_PATH, HTTP_OK, body);
    final URI service = this.http.uri(ADDRESS_PATH);
    final String address = ServerAddress.getPublicAddress(service);
    assertEquals(PUBLIC_ADDRESS, address);
  }

  @Test
  void shouldPreserveAConfiguredHostname() {
    this.configureServerAddress("mc.example.org");
    final String address = ServerAddress.getPublicAddress();
    assertEquals("mc.example.org", address);
  }

  @Test
  void shouldFallBackToLocalhostWithoutCachingItWhenTheLookupFails() {
    this.configureServerAddress("");
    final byte[] error = "unavailable".getBytes(StandardCharsets.US_ASCII);
    final byte[] address = PUBLIC_ADDRESS.getBytes(StandardCharsets.US_ASCII);
    this.http.respond(ADDRESS_PATH, 503, error);
    this.http.respond(ADDRESS_PATH, HTTP_OK, address);
    final URI service = this.http.uri(ADDRESS_PATH);
    final String failed = ServerAddress.getPublicAddress(service);
    final String retried = ServerAddress.getPublicAddress(service);
    final String cached = ServerAddress.getPublicAddress(service);
    final int requests = this.http.getRequestCount(ADDRESS_PATH);
    final boolean failedIsFallback = ServerAddress.isFallbackAddress(failed);
    assertEquals(LOCALHOST, failed);
    assertTrue(failedIsFallback);
    assertEquals(PUBLIC_ADDRESS, retried, "a transient failure is not remembered");
    assertEquals(PUBLIC_ADDRESS, cached);
    assertEquals(2, requests, "only the successful lookup is cached");
  }

  @Test
  void shouldRecognizeOnlyTheFallbackAddress() {
    final boolean localhost = ServerAddress.isFallbackAddress(LOCALHOST);
    final boolean publicAddress = ServerAddress.isFallbackAddress(PUBLIC_ADDRESS);
    final boolean loopback = ServerAddress.isFallbackAddress("127.0.0.1");
    assertTrue(localhost);
    assertFalse(publicAddress);
    assertFalse(loopback);
    assertThrows(NullPointerException.class, () -> ServerAddress.isFallbackAddress(null));
  }

  @Test
  void shouldNotBeInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(ServerAddress.class);
  }
}
