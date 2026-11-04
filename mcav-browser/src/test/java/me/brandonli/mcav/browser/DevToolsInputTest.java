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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.brandonli.mcav.browser.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

class DevToolsInputTest {

  private static final int WHEEL_DISTANCE = 120;

  private static final String OPEN_WINDOWS_SCRIPT_FILE = "open-windows.mjs";

  private static final Pattern KEY_FIELD = Pattern.compile("\"key\":(\"[^\"]*\")");

  private List<String> parameters(final List<DevToolsInput.DevToolsCall> calls) {
    final List<String> parameters = new ArrayList<>();
    for (final DevToolsInput.DevToolsCall call : calls) {
      parameters.add(call.getParameters());
    }

    return parameters;
  }

  @Test
  void shouldTurnMouseEventsIntoDispatchMouseEventCalls() {
    final List<DevToolsInput.DevToolsCall> move = DevToolsInput.mouse(new MouseInput(HelperProtocol.MOUSE_MOVE, 3, 4, 0, 0, 0, 0), 0);
    final int moveCallCount = move.size();
    assertEquals(1, moveCallCount);

    final String method = move.getFirst().getMethod();
    assertEquals(DevToolsInput.MOUSE_METHOD, method);

    final String moveParameters = move.getFirst().getParameters();
    assertEquals("{\"type\":\"mouseMoved\",\"x\":3,\"y\":4,\"buttons\":0,\"button\":\"none\"}", moveParameters);

    final List<String> press = this.parameters(
      DevToolsInput.mouse(new MouseInput(HelperProtocol.MOUSE_PRESS, 1, 2, HelperProtocol.BUTTON_LEFT, 1, 0, 0), 0)
    );
    assertEquals(List.of("{\"type\":\"mousePressed\",\"x\":1,\"y\":2,\"buttons\":1,\"button\":\"left\",\"clickCount\":1}"), press);

    final List<String> release = this.parameters(
      DevToolsInput.mouse(new MouseInput(HelperProtocol.MOUSE_RELEASE, 1, 2, HelperProtocol.BUTTON_RIGHT, 2, 0, 0), 2)
    );
    assertEquals(List.of("{\"type\":\"mouseReleased\",\"x\":1,\"y\":2,\"buttons\":0,\"button\":\"right\",\"clickCount\":2}"), release);

    final List<String> middle = this.parameters(
      DevToolsInput.mouse(new MouseInput(HelperProtocol.MOUSE_PRESS, 0, 0, HelperProtocol.BUTTON_MIDDLE, 1, 0, 0), 0)
    );
    final boolean holdsTheMiddleButton = middle.getFirst().contains("\"buttons\":4,\"button\":\"middle\"");
    assertTrue(holdsTheMiddleButton);

    final List<String> wheel = this.parameters(
      DevToolsInput.mouse(new MouseInput(HelperProtocol.MOUSE_WHEEL, 5, 6, 0, 0, -10, WHEEL_DISTANCE), 0)
    );
    assertEquals(List.of("{\"type\":\"mouseWheel\",\"x\":5,\"y\":6,\"buttons\":0,\"deltaX\":-10,\"deltaY\":120}"), wheel);
  }

  @Test
  void shouldTreatAMoveWhileAButtonIsHeldAsADrag() {
    final MouseInput pressLeft = new MouseInput(HelperProtocol.MOUSE_PRESS, 5, 5, HelperProtocol.BUTTON_LEFT, 1, 0, 0);
    final MouseInput pressRight = new MouseInput(HelperProtocol.MOUSE_PRESS, 5, 5, HelperProtocol.BUTTON_RIGHT, 1, 0, 0);
    final MouseInput move = new MouseInput(HelperProtocol.MOUSE_MOVE, 6, 6, HelperProtocol.BUTTON_LEFT, 0, 0, 0);
    final MouseInput releaseLeft = new MouseInput(HelperProtocol.MOUSE_RELEASE, 6, 6, HelperProtocol.BUTTON_LEFT, 1, 0, 0);
    final MouseInput wheel = new MouseInput(HelperProtocol.MOUSE_WHEEL, 6, 6, HelperProtocol.BUTTON_LEFT, 0, 0, WHEEL_DISTANCE);
    int held = DevToolsInput.heldAfter(pressLeft, 0);
    assertEquals(1, held);

    final List<String> dragParameters = this.parameters(DevToolsInput.mouse(move, held));
    assertEquals(List.of("{\"type\":\"mouseMoved\",\"x\":6,\"y\":6,\"buttons\":1,\"button\":\"left\"}"), dragParameters);

    final int heldAfterTheMove = DevToolsInput.heldAfter(move, held);
    assertEquals(1, heldAfterTheMove);

    final int heldAfterTheWheel = DevToolsInput.heldAfter(wheel, held);
    assertEquals(1, heldAfterTheWheel);

    held = DevToolsInput.heldAfter(pressRight, held);
    assertEquals(3, held);

    held = DevToolsInput.heldAfter(releaseLeft, held);
    assertEquals(2, held, "the right button stays held");

    final boolean dragHoldsTheRightButton = this.parameters(DevToolsInput.mouse(move, held))
      .getFirst()
      .endsWith("\"buttons\":2,\"button\":\"right\"}");
    assertTrue(dragHoldsTheRightButton);

    final int heldAfterAStrayRelease = DevToolsInput.heldAfter(releaseLeft, 0);
    assertEquals(0, heldAfterAStrayRelease, "releasing a button that is not held changes nothing");
  }

  @Test
  void shouldPressAndReleaseANamedKeyWithItsCodes() {
    final List<DevToolsInput.DevToolsCall> pageDown = DevToolsInput.pressKey("PageDown");
    final int pageDownCallCount = pageDown.size();
    assertEquals(2, pageDownCallCount);

    final String method = pageDown.getFirst().getMethod();
    assertEquals(DevToolsInput.KEY_METHOD, method);

    final List<String> pageDownParameters = this.parameters(pageDown);
    assertEquals(
      List.of(
        "{\"type\":\"rawKeyDown\",\"key\":\"PageDown\",\"code\":\"PageDown\",\"windowsVirtualKeyCode\":34}",
        "{\"type\":\"keyUp\",\"key\":\"PageDown\",\"code\":\"PageDown\",\"windowsVirtualKeyCode\":34}"
      ),
      pageDownParameters
    );
  }

  @Test
  void shouldSendTheTextOfKeysThatTypeText() {
    final List<String> enterParameters = this.parameters(DevToolsInput.pressKey("Enter"));
    assertEquals(
      List.of(
        "{\"type\":\"keyDown\",\"key\":\"Enter\",\"code\":\"Enter\",\"windowsVirtualKeyCode\":13,\"text\":\"\\r\",\"unmodifiedText\":\"\\r\"}",
        "{\"type\":\"keyUp\",\"key\":\"Enter\",\"code\":\"Enter\",\"windowsVirtualKeyCode\":13}"
      ),
      enterParameters
    );

    final List<String> space = this.parameters(DevToolsInput.pressKey(" "));
    final boolean spaceHasItsCode = space.getFirst().contains("\"code\":\"Space\"");
    assertTrue(spaceHasItsCode);

    final boolean spaceTypesASpace = space.getFirst().contains("\"text\":\" \"");
    assertTrue(spaceTypesASpace);
  }

  @Test
  void shouldGiveFunctionKeysTheirCodes() {
    final boolean firstFunctionKeyHasItsCode = DevToolsInput.pressKey("F1")
      .getFirst()
      .getParameters()
      .contains("\"windowsVirtualKeyCode\":112");
    assertTrue(firstFunctionKeyHasItsCode);

    final boolean lastFunctionKeyHasItsCode = DevToolsInput.pressKey("F24")
      .getFirst()
      .getParameters()
      .contains("\"windowsVirtualKeyCode\":135");
    assertTrue(lastFunctionKeyHasItsCode);

    final boolean twelfthFunctionKeyKnown = DevToolsInput.isKnownKey("F12");
    assertTrue(twelfthFunctionKeyKnown);

    final boolean twentyFifthFunctionKeyKnown = DevToolsInput.isKnownKey("F25");
    assertFalse(twentyFifthFunctionKeyKnown);
  }

  @Test
  void shouldPressAKeyWithoutADefinitionByItsNameAlone() {
    final List<String> mediaKeyParameters = this.parameters(DevToolsInput.pressKey("MediaPlayPause"));
    assertEquals(
      List.of(
        "{\"type\":\"rawKeyDown\",\"key\":\"MediaPlayPause\",\"windowsVirtualKeyCode\":0}",
        "{\"type\":\"keyUp\",\"key\":\"MediaPlayPause\",\"windowsVirtualKeyCode\":0}"
      ),
      mediaKeyParameters
    );
  }

  @Test
  void shouldTypeTextOneCharacterAtATime() {
    final List<String> typed = this.parameters(DevToolsInput.typeText("a1!"));
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
  void shouldPressEnterForLineBreaksAndKeepCharactersOutsideTheBasicPlaneWhole() {
    final List<String> typed = this.parameters(DevToolsInput.typeText("\n\r\uD83D\uDE00"));
    final int typedCallCount = typed.size();
    assertEquals(6, typedCallCount);

    final boolean firstLineBreakPressesEnter = typed.get(0).contains("\"key\":\"Enter\"");
    assertTrue(firstLineBreakPressesEnter);

    final boolean secondLineBreakPressesEnter = typed.get(2).contains("\"key\":\"Enter\"");
    assertTrue(secondLineBreakPressesEnter);

    final boolean emojiTypedWhole = typed.get(4).contains("\"text\":\"\uD83D\uDE00\"");
    assertTrue(emojiTypedWhole);

    final List<DevToolsInput.DevToolsCall> callsForNoText = DevToolsInput.typeText("");
    assertEquals(List.of(), callsForNoText);
  }

  @Test
  void shouldPressEnterOnceForAWindowsLineBreak() {
    final List<String> keys = this.parameters(DevToolsInput.typeText("a\r\nb\n\r"))
      .stream()
      .filter(call -> call.contains("keyDown"))
      .map(this::keyOf)
      .toList();
    assertEquals(List.of("\"a\"", "\"Enter\"", "\"b\"", "\"Enter\"", "\"Enter\""), keys);
  }

  private String keyOf(final String call) {
    final Matcher matcher = KEY_FIELD.matcher(call);
    final boolean keyFound = matcher.find();
    assertTrue(keyFound, call);

    return matcher.group(1);
  }

  @Test
  void shouldOnlyGiveLettersAndDigitsAVirtualKeyCode() {
    final int lowerACode = DevToolsInput.virtualKeyCode('a');
    assertEquals('A', lowerACode);

    final int lowerZCode = DevToolsInput.virtualKeyCode('z');
    assertEquals('Z', lowerZCode);

    final int upperACode = DevToolsInput.virtualKeyCode('A');
    assertEquals('A', upperACode);

    final int upperZCode = DevToolsInput.virtualKeyCode('Z');
    assertEquals('Z', upperZCode);

    final int zeroCode = DevToolsInput.virtualKeyCode('0');
    assertEquals('0', zeroCode);

    final int nineCode = DevToolsInput.virtualKeyCode('9');
    assertEquals('9', nineCode);

    final int atSignCode = DevToolsInput.virtualKeyCode('@');
    assertEquals(0, atSignCode);

    final int openingBracketCode = DevToolsInput.virtualKeyCode('[');
    assertEquals(0, openingBracketCode);

    final int graveAccentCode = DevToolsInput.virtualKeyCode('`');
    assertEquals(0, graveAccentCode);

    final int openingBraceCode = DevToolsInput.virtualKeyCode('{');
    assertEquals(0, openingBraceCode);

    final int slashCode = DevToolsInput.virtualKeyCode('/');
    assertEquals(0, slashCode);

    final int colonCode = DevToolsInput.virtualKeyCode(':');
    assertEquals(0, colonCode);

    final int accentedECode = DevToolsInput.virtualKeyCode('\u00e9');
    assertEquals(0, accentedECode);
  }

  @Test
  void shouldQuoteStringsAsJson() {
    final String plainQuoted = DevToolsInput.quote("plain");
    assertEquals("\"plain\"", plainQuoted);

    final String escapesQuoted = DevToolsInput.quote("\"\\\n\r\t");
    assertEquals("\"\\\"\\\\\\n\\r\\t\"", escapesQuoted);

    final String controlCharactersQuoted = DevToolsInput.quote("\u0000\u001f\u2028\u2029");
    assertEquals("\"\\u0000\\u001f\\u2028\\u2029\"", controlCharactersQuoted);

    final String unescapedCharactersQuoted = DevToolsInput.quote(" \u007f\u00e9");
    assertEquals("\" \u007f\u00e9\"", unescapedCharactersQuoted);
  }

  @Test
  void shouldSendTheScriptThatOpensWindowsInPlaceAsOneJsonString() throws Exception {
    ScriptAssertions.execute(OPEN_WINDOWS_SCRIPT_FILE, DevToolsInput.OPEN_IN_PLACE_SCRIPT);
    final List<DevToolsInput.DevToolsCall> calls = DevToolsInput.openWindowsInPlace();
    final int callCount = calls.size();
    assertEquals(2, callCount);

    final String firstMethod = calls.getFirst().getMethod();
    assertEquals("Page.enable", firstMethod);

    final String firstParameters = calls.getFirst().getParameters();
    assertEquals("{}", firstParameters);

    final DevToolsInput.DevToolsCall call = calls.get(1);
    final String secondMethod = call.getMethod();
    assertEquals("Page.addScriptToEvaluateOnNewDocument", secondMethod);

    final JsonObject parameters = JsonParser.parseString(call.getParameters()).getAsJsonObject();
    final String scriptSource = parameters.get("source").getAsString();
    assertEquals(DevToolsInput.OPEN_IN_PLACE_SCRIPT, scriptSource);

    final int parameterCount = parameters.size();
    assertEquals(1, parameterCount);

    final boolean opensInTheTopWindow = DevToolsInput.OPEN_IN_PLACE_SCRIPT.contains("window.top.location.href = target.href");
    assertTrue(opensInTheTopWindow);

    final boolean marksItselfPlaced = DevToolsInput.OPEN_IN_PLACE_SCRIPT.contains("const PLACED = Symbol.for('mcav.open-in-place');");
    assertTrue(marksItselfPlaced, "placed twice, it runs once");
  }

  @Test
  void shouldNotInstantiateTheInput() {
    UtilityClassAssertions.assertNotInstantiable(DevToolsInput.class);
  }
}
