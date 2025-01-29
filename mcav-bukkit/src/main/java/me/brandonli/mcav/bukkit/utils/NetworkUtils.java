/*
 * This file is part of mcav, a media playback library for Minecraft
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

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.bukkit.Bukkit;

/**
 * Utility class for network operations.
 */
public final class NetworkUtils {

  private static final String IP_URL = "https://ipv4.icanhazip.com/";

  private NetworkUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Retrieves the public IP address of the server. If the server's IP is not set in the
   * server.properties, it will attempt to fetch it using an external service.
   *
   * @return The public IP address of the server, or "localhost" if it cannot be determined.
   */
  public static String getPublicIPAddress() {
    final String ip = Bukkit.getIp();
    return ip.isEmpty() ? getPublicIPAddress0() : ip;
  }

  private static String getPublicIPAddress0() {
    try {
      final URI uri = URI.create(IP_URL);
      try (final HttpClient client = HttpClient.newHttpClient()) {
        final HttpRequest request = HttpRequest.newBuilder().uri(uri).build();
        final HttpResponse.BodyHandler<String> handler = HttpResponse.BodyHandlers.ofString();
        final HttpResponse<String> response = client.send(request, handler);
        final String address = response.body();
        final String encodedAddress = address.trim();
        final URI check = URI.create(encodedAddress);
        final boolean valid = checkValidUrl(check);
        return valid ? address : "localhost";
      }
    } catch (final IOException | InterruptedException e) {
      final Thread current = Thread.currentThread();
      current.interrupt();
      throw new AssertionError(e);
    }
  }

  /**
   * Checks if the following URI is a valid URL.
   *
   * @param uri The URI to check.
   * @return True if the URI is a valid URL, false otherwise.
   */
  public static boolean checkValidUrl(final URI uri) {
    try {
      final URL url = uri.toURL();
      final HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
      urlConnection.setRequestMethod("HEAD");
      urlConnection.setConnectTimeout(5000);
      urlConnection.setReadTimeout(5000);
      final int code = urlConnection.getResponseCode();
      return (code == HttpURLConnection.HTTP_OK);
    } catch (final Exception e) {
      return false;
    }
  }
}
