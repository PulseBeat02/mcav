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

// Values a type does not carry are empty, zero or null.
final class HelperMessage {

  private static final byte[] NO_BYTES = new byte[0];

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
    this(type, token, number, text, url, region, mouse, NO_BYTES);
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

  static HelperMessage audio(final byte[] samples) {
    return new HelperMessage(HelperProtocol.AUDIO, NO_BYTES, 0, "", "", null, null, samples);
  }

  static HelperMessage hello(final byte[] token, final int version) {
    return new HelperMessage(HelperProtocol.HELLO, token, version, "", "", null, null);
  }

  static HelperMessage text(final int type, final String text) {
    return new HelperMessage(type, NO_BYTES, 0, text, "", null, null);
  }

  static HelperMessage loading(final boolean loading) {
    return new HelperMessage(
      HelperProtocol.LOADING,
      NO_BYTES,
      loading ? HelperProtocol.LOADING_STARTED : HelperProtocol.LOADING_FINISHED,
      "",
      "",
      null,
      null
    );
  }

  static HelperMessage loadError(final int code, final String text, final String url) {
    return new HelperMessage(HelperProtocol.LOAD_ERROR, NO_BYTES, code, text, url, null, null);
  }

  static HelperMessage frame(final FrameRegion region) {
    return new HelperMessage(HelperProtocol.FRAME, NO_BYTES, 0, "", "", region, null);
  }

  static HelperMessage mouse(final MouseInput input) {
    return new HelperMessage(HelperProtocol.MOUSE, NO_BYTES, 0, "", "", null, input);
  }

  static HelperMessage key(final int action, final String value) {
    return new HelperMessage(HelperProtocol.KEY, NO_BYTES, action, value, "", null, null);
  }

  static HelperMessage close() {
    return new HelperMessage(HelperProtocol.CLOSE, NO_BYTES, 0, "", "", null, null);
  }

  FrameRegion getRegion() {
    final FrameRegion current = this.region;
    if (current == null) {
      final String message = "Message type %s carries no frame!".formatted(this.type);
      throw new IllegalStateException(message);
    }

    return current;
  }

  MouseInput getMouse() {
    final MouseInput current = this.mouse;
    if (current == null) {
      final String message = "Message type %s carries no mouse input!".formatted(this.type);
      throw new IllegalStateException(message);
    }

    return current;
  }

  byte[] getToken() {
    return this.token.clone();
  }

  int getType() {
    return this.type;
  }

  // the protocol version of a hello, 1 or 0 for loading, the code of a load error, or the action of a key
  int getNumber() {
    return this.number;
  }

  // the CEF version, a notice, a failure, an error text, or a key name or text
  String getText() {
    return this.text;
  }

  String getUrl() {
    return this.url;
  }

  // not copied: the receiver owns them
  byte[] getSamples() {
    return this.samples;
  }
}
