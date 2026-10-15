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
  void shouldDescribeWhatMessagesWithoutAFrameOrMouseInputCarry() {
    final HelperMessage close = HelperMessage.close();
    final int type = close.getType();
    assertEquals(HelperProtocol.CLOSE, type);
    final byte[] token = close.getToken();
    assertArrayEquals(new byte[0], token);
    final String text = close.getText();
    assertEquals("", text);
    final String url = close.getUrl();
    assertEquals("", url);
    final IllegalStateException noFrame = assertThrows(IllegalStateException.class, close::getRegion);
    final String noFrameMessage = noFrame.getMessage();
    assertEquals("Message type 18 carries no frame", noFrameMessage);
    final IllegalStateException noMouse = assertThrows(IllegalStateException.class, close::getMouse);
    final String noMouseMessage = noMouse.getMessage();
    assertEquals("Message type 18 carries no mouse input", noMouseMessage);
  }

  @Test
  void shouldCopyTheToken() {
    final byte[] token = new byte[HelperProtocol.TOKEN_BYTES];
    final HelperMessage hello = HelperMessage.hello(token, 1);
    final byte[] first = hello.getToken();
    first[0] = 9;
    final byte firstByte = hello.getToken()[0];
    assertEquals(0, firstByte);
    final byte[] secondCopy = hello.getToken();
    assertNotSame(hello.getToken(), secondCopy);
  }

  @Test
  void shouldSetTheValuesOfEachFactory() {
    final int loadingNumber = HelperMessage.loading(true).getNumber();
    assertEquals(1, loadingNumber);
    final int loadedNumber = HelperMessage.loading(false).getNumber();
    assertEquals(0, loadedNumber);
    final HelperMessage error = HelperMessage.loadError(-3, "aborted", "http://a/");
    final int errorMessageType = error.getType();
    assertEquals(HelperProtocol.LOAD_ERROR, errorMessageType);
    final int errorCode = error.getNumber();
    assertEquals(-3, errorCode);
    final String errorText = error.getText();
    assertEquals("aborted", errorText);
    final String errorUrl = error.getUrl();
    assertEquals("http://a/", errorUrl);
    final HelperMessage key = HelperMessage.key(HelperProtocol.KEY_TYPE, "x");
    final int keyMessageType = key.getType();
    assertEquals(HelperProtocol.KEY, keyMessageType);
    final int keyEventType = key.getNumber();
    assertEquals(HelperProtocol.KEY_TYPE, keyEventType);
    final String keyText = key.getText();
    assertEquals("x", keyText);
    final FrameRegion region = new FrameRegion(1, 1, 0, 0, 1, 1, new byte[4]);
    final FrameRegion frameRegion = HelperMessage.frame(region).getRegion();
    assertSame(region, frameRegion);
    final MouseInput mouse = new MouseInput(0, 0, 0, 0, 0, 0, 0);
    final MouseInput mouseInput = HelperMessage.mouse(mouse).getMouse();
    assertSame(mouse, mouseInput);
    final HelperMessage notice = HelperMessage.text(HelperProtocol.NOTICE, "n");
    final int noticeMessageType = notice.getType();
    assertEquals(HelperProtocol.NOTICE, noticeMessageType);
    final String noticeText = notice.getText();
    assertEquals("n", noticeText);
  }
}
