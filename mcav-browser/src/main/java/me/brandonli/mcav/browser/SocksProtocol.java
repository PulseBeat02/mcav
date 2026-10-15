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
  private static final int ADDRESS_IPV4 = 1;
  static final int ADDRESS_DOMAIN = 3;
  private static final int ADDRESS_IPV6 = 4;
  static final int SUCCEEDED = 0;
  static final int NOT_ALLOWED = 2;
  static final int HOST_UNREACHABLE = 4;
  static final int CONNECTION_REFUSED = 5;
  static final int COMMAND_NOT_SUPPORTED = 7;
  static final int ADDRESS_NOT_SUPPORTED = 8;

  private static final int IPV4_BYTES = 4;
  private static final int IPV6_BYTES = 16;

  private static final int UNSIGNED_BYTE_MASK = 0xFF;

  private SocksProtocol() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  /**
   * Reads the greeting of a client.
   *
   * @param input the stream from the client
   * @return true if the client offers to go without authentication
   * @throws IOException if the stream ends or the greeting is malformed
   */
  static boolean readGreeting(final DataInput input) throws IOException {
    readVersion(input);
    final int count = input.readUnsignedByte();
    if (count == 0) {
      throw new ProtocolException("The greeting offers no method!");
    }

    boolean withoutAuthentication = false;
    for (int index = 0; index < count; index++) {
      final int method = input.readUnsignedByte();
      withoutAuthentication = withoutAuthentication || method == NO_AUTHENTICATION;
    }
    return withoutAuthentication;
  }

  /**
   * Reads the request of a client.
   *
   * @param input the stream from the client
   * @return the host and port to connect to
   * @throws Refusal     if the request asks for something other than a connection, to an address of an unknown type,
   *                     or to port 0
   * @throws IOException if the stream ends or the request is malformed
   */
  static Request readRequest(final DataInput input) throws IOException {
    readVersion(input);
    final int command = input.readUnsignedByte();
    final int reserved = input.readUnsignedByte();
    if (reserved != 0) {
      final String message = "Reserved byte %s of the request must be 0!".formatted(reserved);
      throw new ProtocolException(message);
    }

    final int type = input.readUnsignedByte();
    final String host = switch (type) {
      case ADDRESS_IPV4 -> readAddress(input, IPV4_BYTES);
      case ADDRESS_IPV6 -> readAddress(input, IPV6_BYTES);
      case ADDRESS_DOMAIN -> readHostName(input);
      default -> {
        final String message = "Address type %s of the request is not supported!".formatted(type);
        throw new Refusal(ADDRESS_NOT_SUPPORTED, message);
      }
    };
    final int port = input.readUnsignedShort();
    if (command != CONNECT) {
      final String message = "Command %s of the request is not a connection!".formatted(command);
      throw new Refusal(COMMAND_NOT_SUPPORTED, message);
    }

    if (port == 0) {
      throw new Refusal(NOT_ALLOWED, "The request asks for port 0!");
    }

    return new Request(host, port);
  }

  private static void readVersion(final DataInput input) throws IOException {
    final int version = input.readUnsignedByte();
    if (version != VERSION) {
      final String message = "SOCKS version %s of the client is not SOCKS 5!".formatted(version);
      throw new ProtocolException(message);
    }
  }

  private static String readAddress(final DataInput input, final int length) throws IOException {
    final byte[] bytes = new byte[length];
    input.readFully(bytes);
    final InetAddress address = InetAddress.getByAddress(bytes);
    return address.getHostAddress();
  }

  private static String readHostName(final DataInput input) throws IOException {
    final int length = input.readUnsignedByte();
    if (length == 0) {
      throw new ProtocolException("The request names an empty host!");
    }

    final byte[] bytes = new byte[length];
    input.readFully(bytes);
    for (final byte value : bytes) {
      if (!isHostNameCharacter(value) && value != ':' && value != '[' && value != ']') {
        final String message = "Byte %s is not allowed in the host name of the request!".formatted(value & UNSIGNED_BYTE_MASK);
        throw new ProtocolException(message);
      }
    }
    final String host = new String(bytes, StandardCharsets.US_ASCII);
    // Chromium names an IPv6 address by its text, in the field of a host name
    if (host.indexOf(':') >= 0) {
      return readIpv6Literal(host);
    }

    if (host.indexOf('[') >= 0 || host.indexOf(']') >= 0) {
      final String message = "Host name %s of the request holds a bracket!".formatted(host);
      throw new ProtocolException(message);
    }

    return host;
  }

  /**
   * Reads an IPv6 address written as text, with or without brackets, without asking a resolver.
   *
   * @param text the text
   * @return the address in its full text form
   * @throws ProtocolException if the text is not an IPv6 address
   */
  private static String readIpv6Literal(final String text) throws ProtocolException {
    try {
      final InetAddress address = InetAddress.ofLiteral(text);
      return address.getHostAddress();
    } catch (final IllegalArgumentException exception) {
      final String message = "Host name %s of the request is not an IPv6 address!".formatted(text);
      final ProtocolException failure = new ProtocolException(message);
      failure.initCause(exception);
      throw failure;
    }
  }

  /**
   * Checks whether a byte may appear in a host name: an ASCII letter or digit, a hyphen, a dot or an underscore.
   * Chromium sends international names in their ASCII form.
   *
   * @param value the byte
   * @return true if it may appear
   */
  static boolean isHostNameCharacter(final byte value) {
    final boolean isLetter = (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
    final boolean isDigit = value >= '0' && value <= '9';
    return isLetter || isDigit || value == '-' || value == '.' || value == '_';
  }

  /**
   * Answers the greeting with the chosen method.
   *
   * @param output    the stream to the client
   * @param method {@link #NO_AUTHENTICATION} or {@link #NO_ACCEPTABLE_METHOD}
   * @throws IOException if the stream fails
   */
  static void writeMethod(final DataOutput output, final int method) throws IOException {
    output.writeByte(VERSION);
    output.writeByte(method);
  }

  /**
   * Answers a request. The bound address is left empty, as Chromium does not use it.
   *
   * @param output   the stream to the client
   * @param reply the reply code, such as {@link #SUCCEEDED}
   * @throws IOException if the stream fails
   */
  static void writeReply(final DataOutput output, final int reply) throws IOException {
    output.writeByte(VERSION);
    output.writeByte(reply);
    output.writeByte(0);
    output.writeByte(ADDRESS_IPV4);
    output.writeInt(0);
    output.writeShort(0);
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
     * Returns the host.
     *
     * @return the host name or the address in text form
     */
    String getHost() {
      return this.host;
    }

    /**
     * Returns the port.
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
    private Refusal(final int reply, final String message) {
      super(message);
      this.reply = reply;
    }

    /**
     * Returns the reply code.
     *
     * @return the code the client gets
     */
    int getReply() {
      return this.reply;
    }
  }
}
