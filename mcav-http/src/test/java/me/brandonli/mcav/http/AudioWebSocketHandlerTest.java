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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.verification.VerificationMode;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

final class AudioWebSocketHandlerTest {

  private static final String LOCALHOST = "localhost";
  private static final int PORT = 8080;

  private SpringHttpResult result;
  private AudioWebSocketHandler handler;
  private WebSocketSession session;

  @BeforeEach
  void setUp() {
    this.result = new SpringHttpResult(LOCALHOST, PORT, null);
    this.handler = new AudioWebSocketHandler(this.result);
    this.session = Mockito.mock(WebSocketSession.class);
  }

  @AfterEach
  void forgetTheSession() {
    this.result.removeListener(this.session);
  }

  @Test
  void shouldRegisterConnectedBrowsersAsListeners() {
    this.connect();
    final int count = this.result.getListenerCount();
    assertEquals(1, count);
  }

  private void connect() {
    Mockito.when(this.session.getId()).thenReturn("session-1");
    Mockito.when(this.session.isOpen()).thenReturn(true);
    this.handler.afterConnectionEstablished(this.session);
  }

  @Test
  void shouldForgetBrowsersThatDisconnect() {
    this.connect();
    this.handler.afterConnectionClosed(this.session, CloseStatus.NORMAL);
    final int count = this.result.getListenerCount();
    assertEquals(0, count);
  }

  @Test
  void shouldForgetBrowsersWithTransportErrors() {
    this.connect();
    final IOException error = new IOException("reset!");
    this.handler.handleTransportError(this.session, error);
    final int count = this.result.getListenerCount();
    assertEquals(0, count);
  }

  @Test
  void shouldIgnoreTextAndBinaryMessagesFromBrowsers() throws Exception {
    this.connect();
    final TextMessage text = new TextMessage("hello");
    final byte[] raw = { 1, 2, 3 };
    final ByteBuffer payload = ByteBuffer.wrap(raw);
    final BinaryMessage binary = new BinaryMessage(payload);

    this.handler.handleMessage(this.session, text);
    this.handler.handleMessage(this.session, binary);
    final int count = this.result.getListenerCount();
    assertEquals(1, count);

    final VerificationMode never = Mockito.never();
    final WebSocketSession notClosed = Mockito.verify(this.session, never);
    notClosed.close(ArgumentMatchers.any());
    final WebSocketSession notSent = Mockito.verify(this.session, never);
    notSent.sendMessage(ArgumentMatchers.any());
  }
}
