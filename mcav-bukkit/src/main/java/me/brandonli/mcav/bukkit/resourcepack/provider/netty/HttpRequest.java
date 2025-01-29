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
package me.brandonli.mcav.bukkit.resourcepack.provider.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;

final class HttpRequest {

  private final String request;
  private final Map<String, String> headers;
  private final String[] requestParts;
  private final String requestMethod;
  private final String requestURI;
  private final String protocolVersion;

  HttpRequest(final String request, final Map<String, String> headers) {
    this.request = request;
    this.headers = headers;
    this.requestParts = request.split(" ");
    this.requestMethod = this.requestParts[0];
    this.requestURI = this.requestParts[1];
    this.protocolVersion = this.requestParts[2];
  }

  @Nullable
  String get(final String header) {
    return this.headers.get(header);
  }

  static HttpRequest parse(final ByteBuf buf) {
    try (final ByteBufInputStream stream = new ByteBufInputStream(buf)) {
      return parse(stream);
    } catch (final IOException e) {
      throw new InjectorException(e.getMessage(), e);
    }
  }

  private static HttpRequest parse(final InputStream stream) {
    try (final InputStreamReader reader = new InputStreamReader(stream); final BufferedReader bufferedReader = new BufferedReader(reader)) {
      final String request = bufferedReader.readLine();
      final String line = request == null ? "" : request;
      final Map<String, String> headers = readHeaders(bufferedReader);
      return new HttpRequest(line, headers);
    } catch (final IOException e) {
      throw new InjectorException(e.getMessage(), e);
    }
  }

  private static Map<String, String> readHeaders(final BufferedReader reader) {
    final Map<String, String> headers = new HashMap<>();
    try {
      String header = reader.readLine();
      while (header != null && !header.isEmpty()) {
        final int split = header.indexOf(':');
        headers.put(header.substring(0, split), header.substring(split + 1).trim());
        header = reader.readLine();
      }
    } catch (final IOException e) {
      throw new InjectorException(e.getMessage(), e);
    }
    return headers;
  }

  String getRequest() {
    return this.request;
  }

  Map<String, String> getHeaders() {
    return this.headers;
  }

  String[] getRequestParts() {
    return this.requestParts;
  }

  String getRequestMethod() {
    return this.requestMethod;
  }

  String getRequestURI() {
    return this.requestURI;
  }

  String getProtocolVersion() {
    return this.protocolVersion;
  }
}
