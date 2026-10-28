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

final class AudioWebSocketHandler extends BinaryWebSocketHandler {

  private final SpringHttpResult result;

  AudioWebSocketHandler(final SpringHttpResult result) {
    this.result = result;
  }

  @Override
  public void afterConnectionEstablished(final @NonNull WebSocketSession session) {
    this.result.addListener(session);
  }

  // ignores text messages, which the base class would answer by closing the connection
  @Override
  protected void handleTextMessage(final @NonNull WebSocketSession session, final @NonNull TextMessage message) {}

  @Override
  public void afterConnectionClosed(final @NonNull WebSocketSession session, final @NonNull CloseStatus status) {
    this.result.removeListener(session);
  }

  @Override
  public void handleTransportError(final @NonNull WebSocketSession session, final @NonNull Throwable exception) {
    this.result.removeListener(session);
  }
}
