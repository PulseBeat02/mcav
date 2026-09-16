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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.net.URI;
import java.util.Optional;
import me.brandonli.mcav.utils.http.NetworkUtils;
import org.bukkit.Bukkit;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Determines the address players use to reach the Minecraft server, for example to build the URL of a resource
 * pack the server hosts itself.
 */
public final class ServerAddress {

  private static final String FALLBACK_ADDRESS = "localhost";
  private static final String WILDCARD_ADDRESS = "0.0.0.0";

  private static volatile @Nullable String PUBLIC_ADDRESS;

  private ServerAddress() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Gets the address players use to reach the server.
   *
   * <p>If {@code server-ip} is set in {@code server.properties}, that address is used. Otherwise, the public
   * address is looked up with {@link NetworkUtils#lookUpPublicAddress()}. A successful lookup is cached for the
   * lifetime of the server; a failed one is not, so the next call tries again. The lookup blocks for at most a few
   * seconds, so avoid calling this method on the main thread before the address is known.
   *
   * @return the public address of the server, or {@code localhost} if it cannot be determined right now, see
   *     {@link #isFallbackAddress(String)}
   */
  public static String getPublicIPAddress() {
    return getPublicIPAddress(NetworkUtils.DEFAULT_ADDRESS_SERVICE);
  }

  /**
   * Gets the address players use to reach the server, looking the public address up with the specified service.
   *
   * @param addressService the service that answers with the public address of the caller as plain text
   * @return the public address of the server, or {@code localhost} if it cannot be determined right now
   */
  @VisibleForTesting
  static String getPublicIPAddress(final URI addressService) {
    Preconditions.checkNotNull(addressService, "Address service must not be null");
    final String configuredAddress = Bukkit.getIp();
    final boolean hasConfiguredAddress = !configuredAddress.isBlank() && !configuredAddress.equals(WILDCARD_ADDRESS);
    if (hasConfiguredAddress) {
      return configuredAddress;
    }

    final String cachedAddress = PUBLIC_ADDRESS;
    if (cachedAddress != null) {
      return cachedAddress;
    }

    final Optional<String> lookedUpAddress = NetworkUtils.lookUpPublicAddress(addressService);
    if (lookedUpAddress.isEmpty()) {
      return FALLBACK_ADDRESS;
    }
    final String address = lookedUpAddress.get();
    PUBLIC_ADDRESS = address;
    return address;
  }

  /**
   * Checks whether an address is the fallback that {@link #getPublicIPAddress()} returns when the public address
   * cannot be determined. Callers that cache values derived from the address should not cache the fallback,
   * because a later lookup may succeed.
   *
   * @param address the address to check
   * @return true if the address is the fallback address {@code localhost}
   * @throws NullPointerException if the address is null
   */
  public static boolean isFallbackAddress(final String address) {
    Preconditions.checkNotNull(address, "Address must not be null");
    return address.equals(FALLBACK_ADDRESS);
  }
}
