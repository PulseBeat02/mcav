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
import org.checkerframework.checker.initialization.qual.UnderInitialization;

// Handed to the helper as one line on its standard input, so none of it shows up in the process list.
final class HelperConfiguration {

  private static final int KEEP_TRAILING_EMPTY_PARTS = -1;

  // MCAV's limit: CEF sets none above its minimum of 1
  static final int MAXIMUM_FRAME_RATE = 60;
  private static final int MAXIMUM_FRAME_INTERVAL = 1000;

  private static final String SEPARATOR = ":";
  private static final int FIELDS = 15;

  private static final String HOST_SEPARATOR = ",";

  private static final int MAXIMUM_LINE_CHARACTERS = 1024 * 1024;

  private static final int TOKEN_FIELD = 0;
  private static final int SOCKET_FIELD = 1;
  private static final int NATIVES_FIELD = 2;
  private static final int PROFILE_FIELD = 3;
  private static final int URL_FIELD = 4;
  private static final int WIDTH_FIELD = 5;
  private static final int HEIGHT_FIELD = 6;
  private static final int FRAME_INTERVAL_FIELD = 7;
  private static final int FRAME_RATE_FIELD = 8;
  private static final int JAVA_SCRIPT_JIT_FIELD = 9;
  private static final int PRIVATE_NETWORKS_FIELD = 10;
  private static final int AUTOPLAY_FIELD = 11;
  private static final int CONFINED_FIELD = 12;
  private static final int SERVER_FOLDER_FIELD = 13;
  private static final int REFUSED_HOSTS_FIELD = 14;

  private static final String TRUE_TEXT = "true";
  private static final String FALSE_TEXT = "false";

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
  private final boolean confined;
  private final Path serverFolder;
  private final List<String> refusedHosts;

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
    final boolean allowsAutoplay,
    final boolean confined,
    final Path serverFolder,
    final List<String> refusedHosts
  ) {
    requireThat(token.length == HelperProtocol.TOKEN_BYTES, "The token must have %s bytes!".formatted(HelperProtocol.TOKEN_BYTES));
    this.requireAbsolute(socket, "socket");
    this.requireAbsolute(natives, "natives");
    this.requireAbsolute(profile, "profile");
    this.requireAbsolute(serverFolder, "server folder");
    for (final String host : refusedHosts) {
      requireThat(isHost(host), "Host %s is not a host name or address!".formatted(host));
    }

    requireThat(NavigationPolicy.isWebAddress(url), "Page %s must be an absolute http or https address!".formatted(url));
    this.requireRange(width, 1, HelperProtocol.MAXIMUM_SIDE, "width");
    this.requireRange(height, 1, HelperProtocol.MAXIMUM_SIDE, "height");
    this.requireRange(frameInterval, 1, MAXIMUM_FRAME_INTERVAL, "frame interval");
    this.requireRange(frameRate, 1, MAXIMUM_FRAME_RATE, "frame rate");
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
    this.confined = confined;
    this.serverFolder = serverFolder;
    this.refusedHosts = List.copyOf(refusedHosts);
  }

  // Base64 never contains the colons that separate the values
  String toLine() {
    final HexFormat hexadecimal = HexFormat.of();
    final List<String> values = List.of(
      hexadecimal.formatHex(this.token),
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
      Boolean.toString(this.allowsAutoplay),
      Boolean.toString(this.confined),
      this.serverFolder.toString(),
      String.join(HOST_SEPARATOR, this.refusedHosts)
    );
    final Base64.Encoder encoder = Base64.getEncoder();
    final StringJoiner line = new StringJoiner(SEPARATOR);
    for (final String value : values) {
      final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      line.add(encoder.encodeToString(bytes));
    }

    return line.toString();
  }

  static HelperConfiguration fromLine(final String line) {
    requireThat(line.length() <= MAXIMUM_LINE_CHARACTERS, "The configuration line is too long!");
    final String[] parts = line.split(SEPARATOR, KEEP_TRAILING_EMPTY_PARTS);
    requireThat(parts.length == FIELDS, "The configuration has %s values instead of %s values!".formatted(parts.length, FIELDS));
    final Base64.Decoder decoder = Base64.getDecoder();
    final String[] values = new String[parts.length];
    for (int index = 0; index < parts.length; index++) {
      final byte[] bytes = decoder.decode(parts[index]);
      values[index] = new String(bytes, StandardCharsets.UTF_8);
    }

    final HexFormat hexadecimal = HexFormat.of();
    final byte[] token = hexadecimal.parseHex(values[TOKEN_FIELD]);
    final Path socket = Path.of(values[SOCKET_FIELD]);
    final Path natives = Path.of(values[NATIVES_FIELD]);
    final Path profile = Path.of(values[PROFILE_FIELD]);
    final URI url = URI.create(values[URL_FIELD]);
    final int width = Integer.parseInt(values[WIDTH_FIELD]);
    final int height = Integer.parseInt(values[HEIGHT_FIELD]);
    final int frameInterval = Integer.parseInt(values[FRAME_INTERVAL_FIELD]);
    final int frameRate = Integer.parseInt(values[FRAME_RATE_FIELD]);
    final boolean allowsJavaScriptJit = parseBoolean(values[JAVA_SCRIPT_JIT_FIELD]);
    final boolean allowsPrivateNetworks = parseBoolean(values[PRIVATE_NETWORKS_FIELD]);
    final boolean allowsAutoplay = parseBoolean(values[AUTOPLAY_FIELD]);
    final boolean confined = parseBoolean(values[CONFINED_FIELD]);
    final Path serverFolder = Path.of(values[SERVER_FOLDER_FIELD]);
    final List<String> refusedHosts = values[REFUSED_HOSTS_FIELD].isEmpty()
      ? List.of()
      : List.of(values[REFUSED_HOSTS_FIELD].split(HOST_SEPARATOR, KEEP_TRAILING_EMPTY_PARTS));
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
      allowsAutoplay,
      confined,
      serverFolder,
      refusedHosts
    );
  }

  static boolean isHost(final String host) {
    return !host.isEmpty() && !host.contains(HOST_SEPARATOR) && host.chars().noneMatch(Character::isWhitespace);
  }

  private static boolean parseBoolean(final String value) {
    requireThat(value.equals(TRUE_TEXT) || value.equals(FALSE_TEXT), "Value %s is not a boolean!".formatted(value));

    return Boolean.parseBoolean(value);
  }

  private void requireAbsolute(@UnderInitialization HelperConfiguration this, final Path path, final String name) {
    requireThat(path.isAbsolute(), "The %s path %s must be absolute!".formatted(name, path));
  }

  private void requireRange(
    @UnderInitialization HelperConfiguration this,
    final int value,
    final int minimum,
    final int maximum,
    final String name
  ) {
    requireThat(
      value >= minimum && value <= maximum,
      "The %s %s must be between %s and %s inclusive!".formatted(name, value, minimum, maximum)
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

  boolean isConfined() {
    return this.confined;
  }

  Path getServerFolder() {
    return this.serverFolder;
  }

  List<String> getRefusedHosts() {
    return this.refusedHosts;
  }
}
