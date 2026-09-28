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
package me.brandonli.mcav.sandbox.command.interaction;

import com.google.common.base.Preconditions;
import java.util.List;
import java.util.Locale;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The VNC servers {@code /mcav vnc create} may connect to: the entries of {@code vnc.allowed-hosts} in
 * {@code config.yml}. The Minecraft server makes the connection, so a player could otherwise make it reach any host
 * and port it can, such as services of its own network; only a host and port an operator listed can be named, and none
 * is listed by default. The password of a server is written in its entry, never in the command, where the server's log
 * would keep it.
 *
 * @param entries the allowed servers
 */
public record VncAllowList(List<Entry> entries) {
  private static final int MAX_PORT = 65_535;

  /**
   * An empty list, which allows no server.
   */
  public static final VncAllowList NONE = new VncAllowList(List.of());

  /**
   * Copies the entries.
   *
   * @param entries the allowed servers
   */
  public VncAllowList {
    Preconditions.checkNotNull(entries, "Entries must not be null");
    entries = List.copyOf(entries);
  }

  /**
   * An allowed VNC server.
   *
   * @param host     its host name or address, compared without regard to case; an IPv6 address may be written in
   *                 brackets, as in a command, which the entry leaves out
   * @param port     its port
   * @param password its password, or null if it has none
   */
  public record Entry(String host, int port, @Nullable String password) {
    /**
     * Validates the entry.
     *
     * @throws IllegalArgumentException if the host is blank or the port is not a TCP port
     */
    public Entry {
      Preconditions.checkNotNull(host, "Host must not be null");
      host = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
      Preconditions.checkArgument(!host.isBlank(), "Host must not be blank");
      Preconditions.checkArgument(port >= 1 && port <= MAX_PORT, "Port must be from 1 to 65535");
    }

    /**
     * Names the server by its host and port, never its password, so the entry can be logged.
     *
     * @return {@code host:port}
     */
    @Override
    public String toString() {
      return format(this.host, this.port);
    }
  }

  /**
   * A server as a command names it: {@code host:port}, or {@code [address]:port} for an IPv6 address.
   *
   * @param host the host
   * @param port the port
   */
  record Target(String host, int port) {}

  /**
   * Parses a server as a command names it.
   *
   * @param text {@code host:port}, or {@code [address]:port} for an IPv6 address
   * @return the server, or null if the text is not a host and a TCP port
   */
  static @Nullable Target parse(final String text) {
    Preconditions.checkNotNull(text, "Text must not be null");
    final int colon = text.lastIndexOf(':');
    if (colon <= 0 || colon == text.length() - 1) {
      return null;
    }
    final String host = parseHost(text.substring(0, colon));
    final int port = parsePort(text.substring(colon + 1));
    if (host == null || port < 1) {
      return null;
    }
    return new Target(host, port);
  }

  /** A host as a command names it: brackets only around an IPv6 address, and no spaces. */
  private static @Nullable String parseHost(final String text) {
    final boolean bracketed = text.startsWith("[") && text.endsWith("]");
    final String host = bracketed ? text.substring(1, text.length() - 1) : text;
    // an IPv6 address needs its brackets, or its last group would be read as the port
    final boolean address = host.indexOf(':') >= 0;
    final boolean invalid = host.isEmpty() || bracketed != address || host.chars().anyMatch(VncAllowList::isOutsideAHost);
    return invalid ? null : host;
  }

  private static boolean isOutsideAHost(final int character) {
    return character == '[' || character == ']' || Character.isWhitespace(character);
  }

  private static int parsePort(final String text) {
    // never empty: parse refuses a colon at the end
    if (text.length() > 5 || !text.chars().allMatch(Character::isDigit)) {
      return -1;
    }
    final int port = Integer.parseInt(text);
    return port <= MAX_PORT ? port : -1;
  }

  /**
   * Finds the entry of a server a command names.
   *
   * @param text the server as the command names it
   * @return its entry, or null if the text is not a server or the server is not allowed
   */
  @Nullable Entry find(final String text) {
    final Target target = parse(text);
    if (target == null) {
      return null;
    }
    final String host = target.host().toLowerCase(Locale.ROOT);
    for (final Entry entry : this.entries) {
      if (entry.port() == target.port() && entry.host().toLowerCase(Locale.ROOT).equals(host)) {
        return entry;
      }
    }
    return null;
  }

  /**
   * Formats a server as a command names it.
   *
   * @param host the host
   * @param port the port
   * @return {@code host:port}, with an IPv6 address in brackets
   */
  private static String format(final String host, final int port) {
    return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
  }
}
