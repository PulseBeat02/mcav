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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.StringJoiner;

/**
 * Everything the browser helper process needs to know, handed to it as one line on its standard input, so none of it
 * shows up in the process list: the session token, the socket to connect to, the installed CEF, the profile folder,
 * the page and its size, and the security profile.
 */
final class HelperConfiguration {

  /**
   * The highest frame rate a browser may paint at, mcav's limit: CEF sets none above its minimum of 1.
   */
  static final int MAX_FRAME_RATE = 60;

  private static final String SEPARATOR = ":";
  private static final int FIELDS = 12;
  // the longest line a configuration of the longest address and long paths makes, in three-byte characters and Base64
  private static final int MAX_LINE_CHARACTERS = 1024 * 1024;
  private static final int MAX_FRAME_INTERVAL = 1000;

  private final byte[] token;
  private final Path socket;
  private final Path natives;
  private final Path profile;
  private final URI url;
  private final int width;
  private final int height;
  private final int frameInterval;
  private final int frameRate;
  private final boolean allowsJavaScriptJit;
  private final boolean allowsPrivateNetworks;
  private final boolean allowsAutoplay;

  /**
   * Constructs a configuration and checks every value.
   *
   * @param token                 the session token, {@link HelperProtocol#TOKEN_BYTES} bytes
   * @param socket                the socket the helper connects to, an absolute path
   * @param natives               the folder CEF is installed in, an absolute path
   * @param profile               the folder the browser keeps its profile in, an absolute path
   * @param url                   the page to open, an absolute {@code http} or {@code https} address
   * @param width                 the width of the page in pixels
   * @param height                the height of the page in pixels
   * @param frameInterval         send every n-th painted frame
   * @param frameRate             how many frames per second CEF paints at most
   * @param allowsJavaScriptJit   true to let V8 compile JavaScript to machine code
   * @param allowsPrivateNetworks true to let the page reach loopback, private and link-local addresses
   * @param allowsAutoplay        true to let the page play sound before anyone clicked or typed into it
   * @throws IllegalArgumentException if a value is out of range
   */
  HelperConfiguration(
    final byte[] token,
    final Path socket,
    final Path natives,
    final Path profile,
    final URI url,
    final int width,
    final int height,
    final int frameInterval,
    final int frameRate,
    final boolean allowsJavaScriptJit,
    final boolean allowsPrivateNetworks,
    final boolean allowsAutoplay
  ) {
    requireThat(token.length == HelperProtocol.TOKEN_BYTES, "The token must have " + HelperProtocol.TOKEN_BYTES + " bytes");
    requireAbsolute(socket, "socket");
    requireAbsolute(natives, "natives");
    requireAbsolute(profile, "profile");
    requireThat(NavigationPolicy.isWebAddress(url), "The page must be an absolute http or https address: " + url);
    requireRange(width, 1, HelperProtocol.MAX_SIDE, "width");
    requireRange(height, 1, HelperProtocol.MAX_SIDE, "height");
    requireRange(frameInterval, 1, MAX_FRAME_INTERVAL, "frame interval");
    requireRange(frameRate, 1, MAX_FRAME_RATE, "frame rate");
    this.token = token.clone();
    this.socket = socket;
    this.natives = natives;
    this.profile = profile;
    this.url = url;
    this.width = width;
    this.height = height;
    this.frameInterval = frameInterval;
    this.frameRate = frameRate;
    this.allowsJavaScriptJit = allowsJavaScriptJit;
    this.allowsPrivateNetworks = allowsPrivateNetworks;
    this.allowsAutoplay = allowsAutoplay;
  }

  /**
   * Writes the configuration as the line the helper reads: every value in a fixed order, each Base64-encoded as UTF-8
   * and separated by colons, which Base64 never contains.
   *
   * @return the line, without a line break
   */
  String toLine() {
    final HexFormat hex = HexFormat.of();
    final List<String> values = List.of(
      hex.formatHex(this.token),
      this.socket.toString(),
      this.natives.toString(),
      this.profile.toString(),
      this.url.toString(),
      Integer.toString(this.width),
      Integer.toString(this.height),
      Integer.toString(this.frameInterval),
      Integer.toString(this.frameRate),
      Boolean.toString(this.allowsJavaScriptJit),
      Boolean.toString(this.allowsPrivateNetworks),
      Boolean.toString(this.allowsAutoplay)
    );
    final Base64.Encoder encoder = Base64.getEncoder();
    final StringJoiner line = new StringJoiner(SEPARATOR);
    for (final String value : values) {
      final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      line.add(encoder.encodeToString(bytes));
    }
    return line.toString();
  }

  /**
   * Reads the line written by {@link #toLine()}.
   *
   * @param line the line
   * @return the configuration
   * @throws IllegalArgumentException if the line is not a complete, valid configuration
   */
  static HelperConfiguration fromLine(final String line) {
    requireThat(line.length() <= MAX_LINE_CHARACTERS, "The configuration line is too long");
    final String[] parts = line.split(SEPARATOR, -1);
    requireThat(parts.length == FIELDS, "The configuration has " + parts.length + " values instead of " + FIELDS);
    final Base64.Decoder decoder = Base64.getDecoder();
    final String[] values = new String[parts.length];
    for (int index = 0; index < parts.length; index++) {
      final byte[] bytes = decoder.decode(parts[index]);
      values[index] = new String(bytes, StandardCharsets.UTF_8);
    }
    final HexFormat hex = HexFormat.of();
    final byte[] token = hex.parseHex(values[0]);
    final Path socket = Path.of(values[1]);
    final Path natives = Path.of(values[2]);
    final Path profile = Path.of(values[3]);
    final URI url = URI.create(values[4]);
    final int width = Integer.parseInt(values[5]);
    final int height = Integer.parseInt(values[6]);
    final int frameInterval = Integer.parseInt(values[7]);
    final int frameRate = Integer.parseInt(values[8]);
    final boolean allowsJavaScriptJit = parseBoolean(values[9]);
    final boolean allowsPrivateNetworks = parseBoolean(values[10]);
    final boolean allowsAutoplay = parseBoolean(values[11]);
    return new HelperConfiguration(
      token,
      socket,
      natives,
      profile,
      url,
      width,
      height,
      frameInterval,
      frameRate,
      allowsJavaScriptJit,
      allowsPrivateNetworks,
      allowsAutoplay
    );
  }

  private static boolean parseBoolean(final String value) {
    requireThat(value.equals("true") || value.equals("false"), "Not a boolean: " + value);
    return Boolean.parseBoolean(value);
  }

  private static void requireAbsolute(final Path path, final String name) {
    requireThat(path.isAbsolute(), "The " + name + " path must be absolute: " + path);
  }

  private static void requireRange(final int value, final int minimum, final int maximum, final String name) {
    requireThat(
      value >= minimum && value <= maximum,
      "The " + name + " must be between " + minimum + " and " + maximum + " but was " + value
    );
  }

  private static void requireThat(final boolean holds, final String message) {
    if (!holds) {
      throw new IllegalArgumentException(message);
    }
  }

  byte[] getToken() {
    return this.token.clone();
  }

  Path getSocket() {
    return this.socket;
  }

  Path getNatives() {
    return this.natives;
  }

  Path getProfile() {
    return this.profile;
  }

  URI getUrl() {
    return this.url;
  }

  int getWidth() {
    return this.width;
  }

  int getHeight() {
    return this.height;
  }

  int getFrameInterval() {
    return this.frameInterval;
  }

  int getFrameRate() {
    return this.frameRate;
  }

  boolean isJavaScriptJit() {
    return this.allowsJavaScriptJit;
  }

  boolean isPrivateNetworks() {
    return this.allowsPrivateNetworks;
  }

  boolean isAutoplay() {
    return this.allowsAutoplay;
  }
}
