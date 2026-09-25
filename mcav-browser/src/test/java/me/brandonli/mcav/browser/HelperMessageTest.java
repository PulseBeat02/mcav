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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class HelperMessageTest {

  @Test
  void messagesWithoutAFrameOrMouseInputSayWhatTheyCarry() {
    final HelperMessage close = HelperMessage.close();
    assertEquals(HelperProtocol.CLOSE, close.getType());
    assertArrayEquals(new byte[0], close.getToken());
    assertEquals("", close.getText());
    assertEquals("", close.getUrl());
    final IllegalStateException noFrame = assertThrows(IllegalStateException.class, close::getRegion);
    assertEquals("Message type 18 carries no frame", noFrame.getMessage());
    final IllegalStateException noMouse = assertThrows(IllegalStateException.class, close::getMouse);
    assertEquals("Message type 18 carries no mouse input", noMouse.getMessage());
  }

  @Test
  void theTokenIsACopy() {
    final byte[] token = new byte[HelperProtocol.TOKEN_BYTES];
    final HelperMessage hello = HelperMessage.hello(token, 1);
    final byte[] first = hello.getToken();
    first[0] = 9;
    assertEquals(0, hello.getToken()[0]);
    assertNotSame(hello.getToken(), hello.getToken());
  }

  @Test
  void eachFactorySetsItsValues() {
    assertEquals(1, HelperMessage.loading(true).getNumber());
    assertEquals(0, HelperMessage.loading(false).getNumber());
    final HelperMessage error = HelperMessage.loadError(-3, "aborted", "http://a/");
    assertEquals(HelperProtocol.LOAD_ERROR, error.getType());
    assertEquals(-3, error.getNumber());
    assertEquals("aborted", error.getText());
    assertEquals("http://a/", error.getUrl());
    final HelperMessage key = HelperMessage.key(HelperProtocol.KEY_TYPE, "x");
    assertEquals(HelperProtocol.KEY, key.getType());
    assertEquals(HelperProtocol.KEY_TYPE, key.getNumber());
    assertEquals("x", key.getText());
    final FrameRegion region = new FrameRegion(1, 1, 0, 0, 1, 1, new byte[4]);
    assertSame(region, HelperMessage.frame(region).getRegion());
    final MouseInput mouse = new MouseInput(0, 0, 0, 0, 0, 0, 0);
    assertSame(mouse, HelperMessage.mouse(mouse).getMouse());
    final HelperMessage notice = HelperMessage.text(HelperProtocol.NOTICE, "n");
    assertEquals(HelperProtocol.NOTICE, notice.getType());
    assertEquals("n", notice.getText());
  }
}
