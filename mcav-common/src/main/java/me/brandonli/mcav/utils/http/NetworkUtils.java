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
package me.brandonli.mcav.utils.http;

import com.google.common.base.Preconditions;
import com.google.common.net.InetAddresses;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Small network helpers: looking up the public address of this machine and checking whether a URL can be
 * downloaded.
 *
 * <p>Every method blocks for at most a few seconds per request and never throws because of network failures;
 * failures are reported as an empty result or {@code false} instead. If the calling thread is interrupted while
 * waiting, the method gives up and the interrupt flag of the thread stays set.
 *
 * <pre>{@code
 * final Optional<String> publicAddress = NetworkUtils.lookUpPublicAddress();
 * final URI download = URI.create("https://example.com/pack.zip");
 * final boolean reachable = NetworkUtils.isReachable(download);
 * }</pre>
 */
public final class NetworkUtils {

  /**
   * The service {@link #lookUpPublicAddress()} asks for the public address, {@code https://ipv4.icanhazip.com/}.
   * It answers with the public IPv4 address of the caller as plain text.
   */
  public static final URI DEFAULT_ADDRESS_SERVICE = URI.create("https://ipv4.icanhazip.com/");

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final int HTTP_OK = 200;

  private NetworkUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Formats an address for the host part of a URL, putting an IPv6 address in square brackets.
   *
   * <p>A bare IPv6 address cannot go into a URL: {@code http://::1:8080/} has no way to tell the colons of the
   * address from the colon of the port, and every URL parser rejects it. RFC 3986 wraps the address in square
   * brackets for that reason, and the percent sign of a scoped address has to be escaped as {@code %25}. Host
   * names and IPv4 addresses are returned unchanged.
   *
   * @param address the host name or IP address
   * @return the address as it may appear before the colon of the port
   * @throws NullPointerException if the address is null
   */
  public static String formatHostForUrl(final String address) {
    Preconditions.checkNotNull(address, "Address must not be null");
    final boolean ipAddress = InetAddresses.isInetAddress(address);
    final boolean ipv6 = ipAddress && address.contains(":");
    if (!ipv6) {
      return address;
    }
    final String escaped = address.replace("%", "%25");
    return "[" + escaped + "]";
  }

  /**
   * Looks up the public IPv4 address of this machine by asking {@link #DEFAULT_ADDRESS_SERVICE}. This is the
   * address other machines on the internet see, which differs from the local address behind a NAT router.
   *
   * <p>The result is not cached, so every call sends a request. The request blocks for at most a few seconds.
   *
   * @return the public address, or an empty optional if the service cannot be reached, fails, or answers with
   *     anything but an IP address, or if the thread is interrupted
   */
  public static Optional<String> lookUpPublicAddress() {
    return lookUpPublicAddress(DEFAULT_ADDRESS_SERVICE);
  }

  /**
   * Looks up the public IP address of this machine by sending a {@code GET} request to the specified service.
   *
   * <p>The service must answer with status 200 and a body that consists of an IPv4 or IPv6 address as plain text.
   * Surrounding whitespace, such as a trailing line break, is ignored. Redirects are not followed. The result is
   * not cached, so every call sends a request. The request blocks for at most a few seconds.
   *
   * @param addressService the absolute {@code http} or {@code https} URI of a service that answers with the public
   *                       address of the caller as plain text, such as {@link #DEFAULT_ADDRESS_SERVICE}
   * @return the public address, or an empty optional if the service cannot be reached, fails, or answers with
   *     anything but an IP address, or if the thread is interrupted
   * @throws NullPointerException     if the service URI is null
   * @throws IllegalArgumentException if the service URI is not an absolute {@code http} or {@code https} URI with
   *                                  a host
   */
  public static Optional<String> lookUpPublicAddress(final URI addressService) {
    Preconditions.checkNotNull(addressService, "Address service must not be null");
    final HttpRequest request = createRequest(addressService, "GET");
    final HttpResponse.BodyHandler<String> bodyHandler = HttpResponse.BodyHandlers.ofString();

    try (final HttpClient client = createClient(HttpClient.Redirect.NEVER)) {
      final HttpResponse<String> response = client.send(request, bodyHandler);
      return readAddress(response);
    } catch (final IOException exception) {
      return Optional.empty();
    } catch (final InterruptedException exception) {
      restoreInterruptFlag();
      return Optional.empty();
    }
  }

  /**
   * Checks whether the URI points to a reachable HTTP resource by sending a {@code HEAD} request. Redirects are
   * followed, and the scheme may be written in any case. The request blocks for at most a few seconds.
   *
   * <p>This is useful to check whether a download URL is still valid before handing it to someone else, for
   * example a cached upload that the hosting service may have deleted.
   *
   * @param uri the URI to check
   * @return true if the resource answered with status 200; false if it answered with any other status, if the URI
   *     is not an absolute {@code http} or {@code https} URI with a host, if the server cannot be reached, or if the
   *     thread is interrupted
   * @throws NullPointerException if the URI is null
   */
  public static boolean isReachable(final URI uri) {
    Preconditions.checkNotNull(uri, "URI must not be null");
    final boolean httpScheme = isHttpScheme(uri);
    if (!httpScheme) {
      return false;
    }

    final HttpResponse.BodyHandler<Void> bodyHandler = HttpResponse.BodyHandlers.discarding();
    try (final HttpClient client = createClient(HttpClient.Redirect.NORMAL)) {
      final HttpRequest request = createRequest(uri, "HEAD");
      final HttpResponse<Void> response = client.send(request, bodyHandler);
      final int status = response.statusCode();
      return status == HTTP_OK;
    } catch (final IOException | IllegalArgumentException exception) {
      return false;
    } catch (final InterruptedException exception) {
      restoreInterruptFlag();
      return false;
    }
  }

  private static Optional<String> readAddress(final HttpResponse<String> response) {
    final int status = response.statusCode();
    final String body = response.body();
    final String address = body.trim();
    final boolean validAddress = InetAddresses.isInetAddress(address);
    if (status != HTTP_OK || !validAddress) {
      return Optional.empty();
    }
    return Optional.of(address);
  }

  private static boolean isHttpScheme(final URI uri) {
    final String scheme = uri.getScheme();
    if (scheme == null) {
      return false;
    }
    return scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https");
  }

  private static HttpClient createClient(final HttpClient.Redirect redirectPolicy) {
    final HttpClient.Builder clientBuilder = HttpClient.newBuilder();
    clientBuilder.connectTimeout(TIMEOUT);
    clientBuilder.followRedirects(redirectPolicy);
    return clientBuilder.build();
  }

  private static HttpRequest createRequest(final URI uri, final String method) {
    final HttpRequest.BodyPublisher noBody = HttpRequest.BodyPublishers.noBody();
    final HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri);
    requestBuilder.timeout(TIMEOUT);
    requestBuilder.method(method, noBody);
    return requestBuilder.build();
  }

  private static void restoreInterruptFlag() {
    final Thread currentThread = Thread.currentThread();
    currentThread.interrupt();
  }
}
