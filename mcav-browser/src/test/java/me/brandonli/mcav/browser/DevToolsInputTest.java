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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import me.brandonli.mcav.browser.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

class DevToolsInputTest {

  private static List<String> parameters(final List<DevToolsInput.DevToolsCall> calls) {
    final List<String> parameters = new ArrayList<>();
    for (final DevToolsInput.DevToolsCall call : calls) {
      parameters.add(call.getParameters());
    }
    return parameters;
  }

  @Test
  void mouseEventsBecomeDispatchMouseEventCalls() {
    final List<DevToolsInput.DevToolsCall> move = DevToolsInput.mouse(new MouseInput(HelperProtocol.MOUSE_MOVE, 3, 4, 0, 0, 0, 0), 0);
    assertEquals(1, move.size());
    assertEquals(DevToolsInput.MOUSE_METHOD, move.getFirst().getMethod());
    assertEquals("{\"type\":\"mouseMoved\",\"x\":3,\"y\":4,\"buttons\":0,\"button\":\"none\"}", move.getFirst().getParameters());
    final List<String> press = parameters(
      DevToolsInput.mouse(new MouseInput(HelperProtocol.MOUSE_PRESS, 1, 2, HelperProtocol.BUTTON_LEFT, 1, 0, 0), 0)
    );
    assertEquals(List.of("{\"type\":\"mousePressed\",\"x\":1,\"y\":2,\"buttons\":1,\"button\":\"left\",\"clickCount\":1}"), press);
    final List<String> release = parameters(
      DevToolsInput.mouse(new MouseInput(HelperProtocol.MOUSE_RELEASE, 1, 2, HelperProtocol.BUTTON_RIGHT, 2, 0, 0), 2)
    );
    assertEquals(List.of("{\"type\":\"mouseReleased\",\"x\":1,\"y\":2,\"buttons\":0,\"button\":\"right\",\"clickCount\":2}"), release);
    final List<String> middle = parameters(
      DevToolsInput.mouse(new MouseInput(HelperProtocol.MOUSE_PRESS, 0, 0, HelperProtocol.BUTTON_MIDDLE, 1, 0, 0), 0)
    );
    assertTrue(middle.getFirst().contains("\"buttons\":4,\"button\":\"middle\""));
    final List<String> wheel = parameters(DevToolsInput.mouse(new MouseInput(HelperProtocol.MOUSE_WHEEL, 5, 6, 0, 0, -10, 120), 0));
    assertEquals(List.of("{\"type\":\"mouseWheel\",\"x\":5,\"y\":6,\"buttons\":0,\"deltaX\":-10,\"deltaY\":120}"), wheel);
  }

  @Test
  void aMoveWhileAButtonIsHeldIsADrag() {
    final MouseInput pressLeft = new MouseInput(HelperProtocol.MOUSE_PRESS, 5, 5, HelperProtocol.BUTTON_LEFT, 1, 0, 0);
    final MouseInput pressRight = new MouseInput(HelperProtocol.MOUSE_PRESS, 5, 5, HelperProtocol.BUTTON_RIGHT, 1, 0, 0);
    final MouseInput move = new MouseInput(HelperProtocol.MOUSE_MOVE, 6, 6, HelperProtocol.BUTTON_LEFT, 0, 0, 0);
    final MouseInput releaseLeft = new MouseInput(HelperProtocol.MOUSE_RELEASE, 6, 6, HelperProtocol.BUTTON_LEFT, 1, 0, 0);
    final MouseInput wheel = new MouseInput(HelperProtocol.MOUSE_WHEEL, 6, 6, HelperProtocol.BUTTON_LEFT, 0, 0, 120);
    int held = DevToolsInput.heldAfter(pressLeft, 0);
    assertEquals(1, held);
    assertEquals(
      List.of("{\"type\":\"mouseMoved\",\"x\":6,\"y\":6,\"buttons\":1,\"button\":\"left\"}"),
      parameters(DevToolsInput.mouse(move, held))
    );
    assertEquals(1, DevToolsInput.heldAfter(move, held));
    assertEquals(1, DevToolsInput.heldAfter(wheel, held));
    held = DevToolsInput.heldAfter(pressRight, held);
    assertEquals(3, held);
    held = DevToolsInput.heldAfter(releaseLeft, held);
    assertEquals(2, held, "the right button stays held");
    assertTrue(parameters(DevToolsInput.mouse(move, held)).getFirst().endsWith("\"buttons\":2,\"button\":\"right\"}"));
    assertEquals(0, DevToolsInput.heldAfter(releaseLeft, 0), "releasing a button that is not held changes nothing");
  }

  @Test
  void aNamedKeyIsPressedAndReleasedWithItsCodes() {
    final List<DevToolsInput.DevToolsCall> pageDown = DevToolsInput.pressKey("PageDown");
    assertEquals(2, pageDown.size());
    assertEquals(DevToolsInput.KEY_METHOD, pageDown.getFirst().getMethod());
    assertEquals(
      List.of(
        "{\"type\":\"rawKeyDown\",\"key\":\"PageDown\",\"code\":\"PageDown\",\"windowsVirtualKeyCode\":34}",
        "{\"type\":\"keyUp\",\"key\":\"PageDown\",\"code\":\"PageDown\",\"windowsVirtualKeyCode\":34}"
      ),
      parameters(pageDown)
    );
  }

  @Test
  void keysThatTypeTextSendTheirText() {
    assertEquals(
      List.of(
        "{\"type\":\"keyDown\",\"key\":\"Enter\",\"code\":\"Enter\",\"windowsVirtualKeyCode\":13,\"text\":\"\\r\",\"unmodifiedText\":\"\\r\"}",
        "{\"type\":\"keyUp\",\"key\":\"Enter\",\"code\":\"Enter\",\"windowsVirtualKeyCode\":13}"
      ),
      parameters(DevToolsInput.pressKey("Enter"))
    );
    final List<String> space = parameters(DevToolsInput.pressKey(" "));
    assertTrue(space.getFirst().contains("\"code\":\"Space\""));
    assertTrue(space.getFirst().contains("\"text\":\" \""));
  }

  @Test
  void functionKeysHaveTheirCodes() {
    assertTrue(DevToolsInput.pressKey("F1").getFirst().getParameters().contains("\"windowsVirtualKeyCode\":112"));
    assertTrue(DevToolsInput.pressKey("F24").getFirst().getParameters().contains("\"windowsVirtualKeyCode\":135"));
    assertTrue(DevToolsInput.isKnownKey("F12"));
    assertFalse(DevToolsInput.isKnownKey("F25"));
  }

  @Test
  void aKeyWithoutADefinitionIsPressedByItsNameAlone() {
    assertEquals(
      List.of(
        "{\"type\":\"rawKeyDown\",\"key\":\"MediaPlayPause\",\"windowsVirtualKeyCode\":0}",
        "{\"type\":\"keyUp\",\"key\":\"MediaPlayPause\",\"windowsVirtualKeyCode\":0}"
      ),
      parameters(DevToolsInput.pressKey("MediaPlayPause"))
    );
  }

  @Test
  void textIsTypedOneCharacterAtATime() {
    final List<String> typed = parameters(DevToolsInput.typeText("a1!"));
    assertEquals(
      List.of(
        "{\"type\":\"keyDown\",\"key\":\"a\",\"windowsVirtualKeyCode\":65,\"text\":\"a\",\"unmodifiedText\":\"a\"}",
        "{\"type\":\"keyUp\",\"key\":\"a\",\"windowsVirtualKeyCode\":65}",
        "{\"type\":\"keyDown\",\"key\":\"1\",\"windowsVirtualKeyCode\":49,\"text\":\"1\",\"unmodifiedText\":\"1\"}",
        "{\"type\":\"keyUp\",\"key\":\"1\",\"windowsVirtualKeyCode\":49}",
        "{\"type\":\"keyDown\",\"key\":\"!\",\"windowsVirtualKeyCode\":0,\"text\":\"!\",\"unmodifiedText\":\"!\"}",
        "{\"type\":\"keyUp\",\"key\":\"!\",\"windowsVirtualKeyCode\":0}"
      ),
      typed
    );
  }

  @Test
  void lineBreaksPressEnterAndCharactersOutsideTheBasicPlaneStayWhole() {
    final List<String> typed = parameters(DevToolsInput.typeText("\n\r\uD83D\uDE00"));
    assertEquals(6, typed.size());
    assertTrue(typed.get(0).contains("\"key\":\"Enter\""));
    assertTrue(typed.get(2).contains("\"key\":\"Enter\""));
    assertTrue(typed.get(4).contains("\"text\":\"\uD83D\uDE00\""));
    assertEquals(List.of(), DevToolsInput.typeText(""));
  }

  @Test
  void aWindowsLineBreakPressesEnterOnce() {
    final java.util.regex.Pattern key = java.util.regex.Pattern.compile("\"key\":(\"[^\"]*\")");
    final List<String> keys = parameters(DevToolsInput.typeText("a\r\nb\n\r"))
      .stream()
      .filter(call -> call.contains("keyDown"))
      .map(call -> {
        final java.util.regex.Matcher matcher = key.matcher(call);
        assertTrue(matcher.find(), call);
        return matcher.group(1);
      })
      .toList();
    assertEquals(List.of("\"a\"", "\"Enter\"", "\"b\"", "\"Enter\"", "\"Enter\""), keys);
  }

  @Test
  void onlyLettersAndDigitsHaveAVirtualKeyCode() {
    assertEquals('A', DevToolsInput.virtualKeyCode('a'));
    assertEquals('Z', DevToolsInput.virtualKeyCode('z'));
    assertEquals('A', DevToolsInput.virtualKeyCode('A'));
    assertEquals('Z', DevToolsInput.virtualKeyCode('Z'));
    assertEquals('0', DevToolsInput.virtualKeyCode('0'));
    assertEquals('9', DevToolsInput.virtualKeyCode('9'));
    assertEquals(0, DevToolsInput.virtualKeyCode('@'));
    assertEquals(0, DevToolsInput.virtualKeyCode('['));
    assertEquals(0, DevToolsInput.virtualKeyCode('`'));
    assertEquals(0, DevToolsInput.virtualKeyCode('{'));
    assertEquals(0, DevToolsInput.virtualKeyCode('/'));
    assertEquals(0, DevToolsInput.virtualKeyCode(':'));
    assertEquals(0, DevToolsInput.virtualKeyCode('\u00e9'));
  }

  @Test
  void stringsAreQuotedAsJson() {
    assertEquals("\"plain\"", DevToolsInput.quote("plain"));
    assertEquals("\"\\\"\\\\\\n\\r\\t\"", DevToolsInput.quote("\"\\\n\r\t"));
    assertEquals("\"\\u0000\\u001f\\u2028\\u2029\"", DevToolsInput.quote("\u0000\u001f\u2028\u2029"));
    assertEquals("\" \u007f\u00e9\"", DevToolsInput.quote(" \u007f\u00e9"));
  }

  @Test
  void theScriptThatOpensWindowsInPlaceIsSentAsOneJsonString() {
    final List<DevToolsInput.DevToolsCall> calls = DevToolsInput.openWindowsInPlace();
    assertEquals(2, calls.size());
    assertEquals("Page.enable", calls.getFirst().getMethod());
    assertEquals("{}", calls.getFirst().getParameters());
    final DevToolsInput.DevToolsCall call = calls.get(1);
    assertEquals("Page.addScriptToEvaluateOnNewDocument", call.getMethod());
    final JsonObject parameters = JsonParser.parseString(call.getParameters()).getAsJsonObject();
    assertEquals(DevToolsInput.OPEN_IN_PLACE_SCRIPT, parameters.get("source").getAsString());
    assertEquals(1, parameters.size());
    assertTrue(DevToolsInput.OPEN_IN_PLACE_SCRIPT.contains("window.top.location.href = target.href"));
  }

  @Test
  void theInputIsNotInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(DevToolsInput.class);
  }
}
