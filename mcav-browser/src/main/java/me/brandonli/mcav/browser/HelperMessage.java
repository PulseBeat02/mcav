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

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A message read by {@link HelperProtocol}: its type and the values of that type. Values a type does not carry are
 * empty, zero or null.
 */
final class HelperMessage {

  private static final byte[] NO_TOKEN = new byte[0];

  private final int type;
  private final byte[] token;
  private final int number;
  private final String text;
  private final String url;
  private final @Nullable FrameRegion region;
  private final @Nullable MouseInput mouse;
  private final byte[] samples;

  private HelperMessage(
    final int type,
    final byte[] token,
    final int number,
    final String text,
    final String url,
    final @Nullable FrameRegion region,
    final @Nullable MouseInput mouse
  ) {
    this(type, token, number, text, url, region, mouse, NO_TOKEN);
  }

  private HelperMessage(
    final int type,
    final byte[] token,
    final int number,
    final String text,
    final String url,
    final @Nullable FrameRegion region,
    final @Nullable MouseInput mouse,
    final byte[] samples
  ) {
    this.type = type;
    this.token = token;
    this.number = number;
    this.text = text;
    this.url = url;
    this.region = region;
    this.mouse = mouse;
    this.samples = samples;
  }

  /**
   * Creates sound of the page.
   *
   * @param samples the samples, 16-bit little-endian stereo at 48 kHz, which the message owns
   * @return the message
   */
  static HelperMessage audio(final byte[] samples) {
    return new HelperMessage(HelperProtocol.AUDIO, NO_TOKEN, 0, "", "", null, null, samples);
  }

  static HelperMessage hello(final byte[] token, final int version) {
    return new HelperMessage(HelperProtocol.HELLO, token, version, "", "", null, null);
  }

  static HelperMessage text(final int type, final String text) {
    return new HelperMessage(type, NO_TOKEN, 0, text, "", null, null);
  }

  static HelperMessage loading(final boolean loading) {
    return new HelperMessage(HelperProtocol.LOADING, NO_TOKEN, loading ? 1 : 0, "", "", null, null);
  }

  static HelperMessage loadError(final int code, final String text, final String url) {
    return new HelperMessage(HelperProtocol.LOAD_ERROR, NO_TOKEN, code, text, url, null, null);
  }

  static HelperMessage frame(final FrameRegion region) {
    return new HelperMessage(HelperProtocol.FRAME, NO_TOKEN, 0, "", "", region, null);
  }

  static HelperMessage mouse(final MouseInput input) {
    return new HelperMessage(HelperProtocol.MOUSE, NO_TOKEN, 0, "", "", null, input);
  }

  static HelperMessage key(final int action, final String value) {
    return new HelperMessage(HelperProtocol.KEY, NO_TOKEN, action, value, "", null, null);
  }

  static HelperMessage close() {
    return new HelperMessage(HelperProtocol.CLOSE, NO_TOKEN, 0, "", "", null, null);
  }

  /**
   * Gets the type of the message, one of the message constants of {@link HelperProtocol}.
   *
   * @return the type
   */
  int getType() {
    return this.type;
  }

  /**
   * Gets the session token of a {@link HelperProtocol#HELLO}.
   *
   * @return the token, or an empty array for other types
   */
  byte[] getToken() {
    return this.token.clone();
  }

  /**
   * Gets the number of the message: the protocol version of a hello, 1 or 0 for loading, the error code of a load
   * error, or the action of a key message.
   *
   * @return the number, or 0 for types without one
   */
  int getNumber() {
    return this.number;
  }

  /**
   * Gets the string of the message: the CEF version, the notice, the failure, the error text, or the key name or text.
   *
   * @return the string, or an empty string for types without one
   */
  String getText() {
    return this.text;
  }

  /**
   * Gets the address of a load error.
   *
   * @return the address, or an empty string for other types
   */
  String getUrl() {
    return this.url;
  }

  /**
   * Gets the region of a frame.
   *
   * @return the region
   * @throws IllegalStateException if the message is not a frame
   */
  FrameRegion getRegion() {
    final FrameRegion current = this.region;
    if (current == null) {
      throw new IllegalStateException("Message type " + this.type + " carries no frame");
    }
    return current;
  }

  /**
   * Gets the samples of sound, which the receiver owns: they are not copied.
   *
   * @return the samples, or an empty array for other types
   */
  byte[] getSamples() {
    return this.samples;
  }

  /**
   * Gets the event of mouse input.
   *
   * @return the event
   * @throws IllegalStateException if the message is not mouse input
   */
  MouseInput getMouse() {
    final MouseInput current = this.mouse;
    if (current == null) {
      throw new IllegalStateException("Message type " + this.type + " carries no mouse input");
    }
    return current;
  }
}
