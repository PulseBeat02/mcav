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
package me.brandonli.mcav.http;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

/**
 * Keeps track of the browsers connected to {@code /audio}. The browsers only receive; anything they send is
 * ignored.
 */
final class AudioWebSocketHandler extends BinaryWebSocketHandler {

  private final HttpResultImpl result;

  /**
   * Creates a handler that registers the connected browsers as listeners of a server.
   *
   * @param result the server that sends the audio to the browsers
   */
  AudioWebSocketHandler(final HttpResultImpl result) {
    this.result = result;
  }

  /**
   * Registers a browser that just connected as a listener, so it receives the samples from now on.
   *
   * @param session the WebSocket session of the browser
   */
  @Override
  public void afterConnectionEstablished(final @NonNull WebSocketSession session) {
    this.result.addListener(session);
  }

  /**
   * Ignores text messages. The base class would close the connection instead, which contradicts the promise that
   * anything a browser sends is ignored.
   *
   * @param session the WebSocket session
   * @param message the ignored message
   */
  @Override
  protected void handleTextMessage(final @NonNull WebSocketSession session, final @NonNull TextMessage message) {
    // browsers only receive
  }

  /**
   * Forgets a browser that disconnected, so no more samples are queued for it.
   *
   * @param session the WebSocket session of the browser
   * @param status  the reason the connection was closed
   */
  @Override
  public void afterConnectionClosed(final @NonNull WebSocketSession session, final @NonNull CloseStatus status) {
    this.result.removeListener(session);
  }

  /**
   * Forgets a browser whose connection failed, so no more samples are queued for it.
   *
   * @param session   the WebSocket session of the browser
   * @param exception the error of the connection
   */
  @Override
  public void handleTransportError(final @NonNull WebSocketSession session, final @NonNull Throwable exception) {
    this.result.removeListener(session);
  }
}
