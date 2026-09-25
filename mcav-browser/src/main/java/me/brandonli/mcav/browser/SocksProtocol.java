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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;

/**
 * The part of SOCKS 5 (RFC 1928) that Chromium speaks to a proxy: a greeting that offers "no authentication", then one
 * CONNECT request per connection, to a host name, an IPv4 or an IPv6 address.
 *
 * <p>The reader is strict and never reads more than a request can hold: at most 255 offered methods and a host name
 * of at most 255 characters, which may only hold the characters of host names. A request the guard does not serve
 * fails with a {@link Refusal} that names the reply code, and anything malformed with a {@link ProtocolException}.
 */
final class SocksProtocol {

  static final int VERSION = 5;
  static final int NO_AUTHENTICATION = 0x00;
  static final int NO_ACCEPTABLE_METHOD = 0xFF;
  static final int CONNECT = 1;
  static final int ADDRESS_IPV4 = 1;
  static final int ADDRESS_DOMAIN = 3;
  static final int ADDRESS_IPV6 = 4;
  static final int SUCCEEDED = 0;
  static final int GENERAL_FAILURE = 1;
  static final int NOT_ALLOWED = 2;
  static final int HOST_UNREACHABLE = 4;
  static final int CONNECTION_REFUSED = 5;
  static final int COMMAND_NOT_SUPPORTED = 7;
  static final int ADDRESS_NOT_SUPPORTED = 8;

  private static final int IPV4_BYTES = 4;
  private static final int IPV6_BYTES = 16;

  private SocksProtocol() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Reads the greeting of a client.
   *
   * @param in the stream from the client
   * @return true if the client offers to go without authentication
   * @throws IOException if the stream ends or the greeting is malformed
   */
  static boolean readGreeting(final DataInput in) throws IOException {
    readVersion(in);
    final int count = in.readUnsignedByte();
    if (count == 0) {
      throw new ProtocolException("The greeting offers no method");
    }
    boolean withoutAuthentication = false;
    for (int index = 0; index < count; index++) {
      final int method = in.readUnsignedByte();
      withoutAuthentication = withoutAuthentication || method == NO_AUTHENTICATION;
    }
    return withoutAuthentication;
  }

  /**
   * Reads the request of a client.
   *
   * @param in the stream from the client
   * @return the host and port to connect to
   * @throws Refusal     if the request asks for something other than a connection, to an address of an unknown type,
   *                     or to port 0
   * @throws IOException if the stream ends or the request is malformed
   */
  static Request readRequest(final DataInput in) throws IOException {
    readVersion(in);
    final int command = in.readUnsignedByte();
    final int reserved = in.readUnsignedByte();
    if (reserved != 0) {
      throw new ProtocolException("The reserved byte of the request is " + reserved);
    }
    final int type = in.readUnsignedByte();
    final String host =
      switch (type) {
        case ADDRESS_IPV4 -> readAddress(in, IPV4_BYTES);
        case ADDRESS_IPV6 -> readAddress(in, IPV6_BYTES);
        case ADDRESS_DOMAIN -> readHostName(in);
        default -> throw new Refusal(ADDRESS_NOT_SUPPORTED, "The request names an address of type " + type);
      };
    final int port = in.readUnsignedShort();
    if (command != CONNECT) {
      throw new Refusal(COMMAND_NOT_SUPPORTED, "The request asks for command " + command + " instead of a connection");
    }
    if (port == 0) {
      throw new Refusal(NOT_ALLOWED, "The request asks for port 0");
    }
    return new Request(host, port);
  }

  private static void readVersion(final DataInput in) throws IOException {
    final int version = in.readUnsignedByte();
    if (version != VERSION) {
      throw new ProtocolException("The client speaks SOCKS " + version + " instead of SOCKS 5");
    }
  }

  private static String readAddress(final DataInput in, final int length) throws IOException {
    final byte[] bytes = new byte[length];
    in.readFully(bytes);
    final InetAddress address = InetAddress.getByAddress(bytes);
    return address.getHostAddress();
  }

  private static String readHostName(final DataInput in) throws IOException {
    final int length = in.readUnsignedByte();
    if (length == 0) {
      throw new ProtocolException("The request names an empty host");
    }
    final byte[] bytes = new byte[length];
    in.readFully(bytes);
    for (final byte value : bytes) {
      if (!isHostNameCharacter(value)) {
        throw new ProtocolException("The host name of the request holds the byte " + (value & 0xFF));
      }
    }
    return new String(bytes, StandardCharsets.US_ASCII);
  }

  /**
   * Checks whether a byte may appear in a host name: an ASCII letter or digit, a hyphen, a dot or an underscore.
   * Chromium sends international names in their ASCII form.
   *
   * @param value the byte
   * @return true if it may appear
   */
  static boolean isHostNameCharacter(final byte value) {
    final boolean letter = (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
    final boolean digit = value >= '0' && value <= '9';
    return letter || digit || value == '-' || value == '.' || value == '_';
  }

  /**
   * Answers the greeting with the chosen method.
   *
   * @param out    the stream to the client
   * @param method {@link #NO_AUTHENTICATION} or {@link #NO_ACCEPTABLE_METHOD}
   * @throws IOException if the stream fails
   */
  static void writeMethod(final DataOutput out, final int method) throws IOException {
    out.writeByte(VERSION);
    out.writeByte(method);
  }

  /**
   * Answers a request. The bound address is left empty, as Chromium does not use it.
   *
   * @param out   the stream to the client
   * @param reply the reply code, such as {@link #SUCCEEDED}
   * @throws IOException if the stream fails
   */
  static void writeReply(final DataOutput out, final int reply) throws IOException {
    out.writeByte(VERSION);
    out.writeByte(reply);
    out.writeByte(0);
    out.writeByte(ADDRESS_IPV4);
    out.writeInt(0);
    out.writeShort(0);
  }

  /**
   * Where a client asks to connect to.
   */
  static final class Request {

    private final String host;
    private final int port;

    /**
     * Constructs a request.
     *
     * @param host the host name or the address in text form
     * @param port the port, from 1 to 65535
     */
    Request(final String host, final int port) {
      this.host = host;
      this.port = port;
    }

    /**
     * Gets the host.
     *
     * @return the host name or the address in text form
     */
    String getHost() {
      return this.host;
    }

    /**
     * Gets the port.
     *
     * @return the port
     */
    int getPort() {
      return this.port;
    }
  }

  /**
   * A request the guard does not serve, with the reply code the client gets.
   */
  static final class Refusal extends ProtocolException {

    private static final long serialVersionUID = 1L;

    private final int reply;

    /**
     * Constructs a refusal.
     *
     * @param reply   the reply code
     * @param message what was refused
     */
    Refusal(final int reply, final String message) {
      super(message);
      this.reply = reply;
    }

    /**
     * Gets the reply code.
     *
     * @return the code the client gets
     */
    int getReply() {
      return this.reply;
    }
  }
}
