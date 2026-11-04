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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

// Input as DevTools calls, which act alike on every system, unlike JCEF's key events, which are translated per platform
// and lose keys such as Page Down.
final class DevToolsInput {

  static final String MOUSE_METHOD = "Input.dispatchMouseEvent";
  static final String KEY_METHOD = "Input.dispatchKeyEvent";

  static final String ADD_SCRIPT_METHOD = "Page.addScriptToEvaluateOnNewDocument";
  static final String ENABLE_PAGE_METHOD = "Page.enable";

  // An off-screen JCEF browser cancels every popup, so this script sends window.open and other-window links and forms to
  // the page itself during a click or key, as Chromium's popup blocker would allow; navigations still meet the policy.
  static final String OPEN_IN_PLACE_SCRIPT = """
  (() => {
    // the helper may place the script twice; once is enough
    const PLACED = Symbol.for('mcav.open-in-place');

    const FORM_TARGET_ATTRIBUTE = 'formtarget';
    const SELF_TARGET = '_self';
    const LINK_SELECTOR = 'a[href], area[href]';
    const WEB_PROTOCOLS = ['http:', 'https:'];

    if (Object.prototype.hasOwnProperty.call(globalThis, PLACED)) {
      return;
    }

    Object.defineProperty(globalThis, PLACED, { value: true });
    // like Chromium's popup blocker, only a click or a key lets a page open a window
    const isActive = () => navigator.userActivation === undefined || navigator.userActivation.isActive;
    const openInPlace = address => {
      let target;
      try {
        target = new URL(String(address), document.baseURI);
      } catch (error) {
        return;
      }
      if (WEB_PROTOCOLS.includes(target.protocol)) {
        // a frame of another origin may set the address of the top frame, but not call its methods
        window.top.location.href = target.href;
      }
    };
    const namesAFrame = (view, name) => {
      let found;
      try {
        found = view[name];
      } catch (error) {
        found = undefined;
      }
      // the window of a frame is its own window property, unlike a page element of that name
      if (found !== undefined && found !== null && found !== view && found.window === found) {
        return true;
      }

      let count = 0;
      try {
        count = view.frames.length;
      } catch (error) {
        return false;
      }
      for (let index = 0; index < count; index++) {
        if (namesAFrame(view.frames[index], name)) {
          return true;
        }
      }
      return false;
    };
    const opensElsewhere = name => {
      const text = String(name || '');
      const lower = text.toLowerCase();
      if (lower === '' || lower === SELF_TARGET || lower === '_top' || lower === '_parent') {
        return false;
      }

      return lower === '_blank' || !namesAFrame(window.top, text);
    };
    Object.defineProperty(window, 'open', {
      configurable: true,
      writable: true,
      value: function (address) {
        if (isActive() && address !== undefined && address !== null && String(address) !== '') {
          openInPlace(address);
        }
        return null;
      }
    });
    window.addEventListener('click', event => {
      const origin = event.target instanceof Element ? event.target.closest(LINK_SELECTOR) : null;
      if (origin !== null && !event.defaultPrevented && isActive() && opensElsewhere(origin.target)) {
        event.preventDefault();
        openInPlace(origin.href);
      }
    });
    window.addEventListener('submit', event => {
      const form = event.target;
      if (!(form instanceof HTMLFormElement) || !isActive()) {
        return;
      }

      // the target of the button that sends the form wins over the target of the form
      const button = event.submitter;
      if (button instanceof HTMLElement && button.hasAttribute(FORM_TARGET_ATTRIBUTE)) {
        if (opensElsewhere(button.getAttribute(FORM_TARGET_ATTRIBUTE))) {
          button.setAttribute(FORM_TARGET_ATTRIBUTE, SELF_TARGET);
        }
      } else if (opensElsewhere(form.target)) {
        form.target = SELF_TARGET;
      }
    }, true);
  })();
  """;

  private static final String[] BUTTON_NAMES = { "left", "middle", "right" };
  private static final String NO_BUTTON = "none";

  // the bits of the DevTools "buttons" field by button number: left, middle, right
  private static final int[] BUTTON_BITS = { 1, 4, 2 };

  private static final String ENTER_KEY = "Enter";

  private static final String FUNCTION_KEY_PREFIX = "F";
  private static final int FUNCTION_KEY_COUNT = 24;
  // F1 has the Windows virtual key code 112, and every next function key the next code
  private static final int FUNCTION_KEY_CODE_OFFSET = 111;

  private static final String MOUSE_PRESSED_TYPE = "mousePressed";
  private static final String MOUSE_RELEASED_TYPE = "mouseReleased";
  private static final String MOUSE_WHEEL_TYPE = "mouseWheel";
  private static final String MOUSE_MOVED_TYPE = "mouseMoved";

  private static final int NO_VIRTUAL_KEY_CODE = 0;

  private static final String RAW_KEY_DOWN_START = "{\"type\":\"rawKeyDown\"";
  private static final String KEY_DOWN_START = "{\"type\":\"keyDown\"";
  private static final String KEY_UP_START = "{\"type\":\"keyUp\"";
  private static final String OBJECT_END = "}";

  private static final String BUTTON_FIELD = ",\"button\":\"";
  private static final String KEY_FIELD = ",\"key\":";
  private static final String VIRTUAL_KEY_CODE_FIELD = ",\"windowsVirtualKeyCode\":";
  private static final String TEXT_FIELD = ",\"text\":";
  private static final String UNMODIFIED_TEXT_FIELD = ",\"unmodifiedText\":";

  private static final char LINE_FEED = '\n';
  private static final char CARRIAGE_RETURN = '\r';

  private static final int QUOTE_CHARACTERS = 2;
  private static final char FIRST_UNESCAPED_CHARACTER = 0x20;
  private static final char LINE_SEPARATOR = 0x2028;
  private static final char PARAGRAPH_SEPARATOR = 0x2029;

  private static final Map<String, KeyDefinition> KEYS = createKeys();

  private DevToolsInput() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  private static Map<String, KeyDefinition> createKeys() {
    final Map<String, KeyDefinition> keys = new HashMap<>();
    keys.put(ENTER_KEY, new KeyDefinition(ENTER_KEY, 13, "\r"));
    keys.put("Tab", new KeyDefinition("Tab", 9, ""));
    keys.put(" ", new KeyDefinition("Space", 32, " "));
    keys.put("Backspace", new KeyDefinition("Backspace", 8, ""));
    keys.put("Delete", new KeyDefinition("Delete", 46, ""));
    keys.put("Insert", new KeyDefinition("Insert", 45, ""));
    keys.put("Escape", new KeyDefinition("Escape", 27, ""));
    keys.put("ArrowDown", new KeyDefinition("ArrowDown", 40, ""));
    keys.put("ArrowLeft", new KeyDefinition("ArrowLeft", 37, ""));
    keys.put("ArrowRight", new KeyDefinition("ArrowRight", 39, ""));
    keys.put("ArrowUp", new KeyDefinition("ArrowUp", 38, ""));
    keys.put("End", new KeyDefinition("End", 35, ""));
    keys.put("Home", new KeyDefinition("Home", 36, ""));
    keys.put("PageDown", new KeyDefinition("PageDown", 34, ""));
    keys.put("PageUp", new KeyDefinition("PageUp", 33, ""));
    keys.put("Shift", new KeyDefinition("ShiftLeft", 16, ""));
    keys.put("Control", new KeyDefinition("ControlLeft", 17, ""));
    keys.put("Alt", new KeyDefinition("AltLeft", 18, ""));
    keys.put("Meta", new KeyDefinition("MetaLeft", 91, ""));
    keys.put("CapsLock", new KeyDefinition("CapsLock", 20, ""));
    keys.put("NumLock", new KeyDefinition("NumLock", 144, ""));
    keys.put("ScrollLock", new KeyDefinition("ScrollLock", 145, ""));
    keys.put("Pause", new KeyDefinition("Pause", 19, ""));
    keys.put("ContextMenu", new KeyDefinition("ContextMenu", 93, ""));
    keys.put("PrintScreen", new KeyDefinition("PrintScreen", 44, ""));
    keys.put("Help", new KeyDefinition("Help", 47, ""));
    keys.put("Clear", new KeyDefinition("NumpadEqual", 12, ""));
    for (int number = 1; number <= FUNCTION_KEY_COUNT; number++) {
      final String name = FUNCTION_KEY_PREFIX + number;
      keys.put(name, new KeyDefinition(name, FUNCTION_KEY_CODE_OFFSET + number, ""));
    }

    return Map.copyOf(keys);
  }

  // every event carries the held buttons, so a page sees a move with a held button as a drag
  static List<DevToolsCall> mouse(final MouseInput input, final int held) {
    final int pageX = input.getX();
    final int pageY = input.getY();
    final String button = BUTTON_NAMES[input.getButton()];
    final int clickCount = input.getClickCount();
    final int after = heldAfter(input, held);
    final String parameters = switch (input.getAction()) {
      case HelperProtocol.MOUSE_PRESS -> mouseParameters(MOUSE_PRESSED_TYPE, pageX, pageY, after) +
        buttonParameters(button, clickCount) +
        OBJECT_END;
      case HelperProtocol.MOUSE_RELEASE -> mouseParameters(MOUSE_RELEASED_TYPE, pageX, pageY, after) +
        buttonParameters(button, clickCount) +
        OBJECT_END;
      case HelperProtocol.MOUSE_WHEEL -> mouseParameters(MOUSE_WHEEL_TYPE, pageX, pageY, after) +
        ",\"deltaX\":" +
        input.getDeltaX() +
        ",\"deltaY\":" +
        input.getDeltaY() +
        OBJECT_END;
      default -> mouseParameters(MOUSE_MOVED_TYPE, pageX, pageY, after) + BUTTON_FIELD + heldButtonName(after) + '"' + OBJECT_END;
    };
    final DevToolsCall call = new DevToolsCall(MOUSE_METHOD, parameters);
    return List.of(call);
  }

  static int heldAfter(final MouseInput input, final int held) {
    final int bit = BUTTON_BITS[input.getButton()];
    return switch (input.getAction()) {
      case HelperProtocol.MOUSE_PRESS -> held | bit;
      case HelperProtocol.MOUSE_RELEASE -> held & ~bit;
      default -> held;
    };
  }

  private static String heldButtonName(final int held) {
    for (int button = 0; button < BUTTON_BITS.length; button++) {
      if ((held & BUTTON_BITS[button]) != 0) {
        return BUTTON_NAMES[button];
      }
    }

    return NO_BUTTON;
  }

  private static String mouseParameters(final String type, final int pageX, final int pageY, final int held) {
    return "{\"type\":\"" + type + "\",\"x\":" + pageX + ",\"y\":" + pageY + ",\"buttons\":" + held;
  }

  private static String buttonParameters(final String button, final int clickCount) {
    return BUTTON_FIELD + button + "\",\"clickCount\":" + clickCount;
  }

  static boolean isKnownKey(final String name) {
    return KEYS.containsKey(name);
  }

  static List<DevToolsCall> pressKey(final String name) {
    final KeyDefinition definition = KEYS.get(name);
    final String quotedName = quote(name);
    final String code = definition == null ? "" : ",\"code\":" + quote(definition.code());
    final int keyCode = definition == null ? NO_VIRTUAL_KEY_CODE : definition.keyCode();
    final String text = definition == null ? "" : definition.text();
    final String keyFields = KEY_FIELD + quotedName + code + VIRTUAL_KEY_CODE_FIELD + keyCode;
    final List<DevToolsCall> calls = new ArrayList<>();
    if (text.isEmpty()) {
      calls.add(new DevToolsCall(KEY_METHOD, RAW_KEY_DOWN_START + keyFields + OBJECT_END));
    } else {
      final String quotedText = quote(text);
      calls.add(
        new DevToolsCall(KEY_METHOD, KEY_DOWN_START + keyFields + TEXT_FIELD + quotedText + UNMODIFIED_TEXT_FIELD + quotedText + OBJECT_END)
      );
    }

    calls.add(new DevToolsCall(KEY_METHOD, KEY_UP_START + keyFields + OBJECT_END));

    return calls;
  }

  // a line break presses Enter, once for \r\n
  static List<DevToolsCall> typeText(final String text) {
    final List<DevToolsCall> calls = new ArrayList<>();
    final int[] codePoints = text.codePoints().toArray();
    int previous = 0;
    for (final int codePoint : codePoints) {
      final boolean isSecondHalfOfCrLf = codePoint == LINE_FEED && previous == CARRIAGE_RETURN;
      previous = codePoint;
      if (isSecondHalfOfCrLf) {
        continue;
      }
      if (codePoint == LINE_FEED || codePoint == CARRIAGE_RETURN) {
        final List<DevToolsCall> enter = pressKey(ENTER_KEY);
        calls.addAll(enter);
        continue;
      }
      final String character = Character.toString(codePoint);
      final String quoted = quote(character);
      final int keyCode = virtualKeyCode(codePoint);
      final String keyFields = KEY_FIELD + quoted + VIRTUAL_KEY_CODE_FIELD + keyCode;
      calls.add(
        new DevToolsCall(KEY_METHOD, KEY_DOWN_START + keyFields + TEXT_FIELD + quoted + UNMODIFIED_TEXT_FIELD + quoted + OBJECT_END)
      );
      calls.add(new DevToolsCall(KEY_METHOD, KEY_UP_START + keyFields + OBJECT_END));
    }

    return calls;
  }

  static int virtualKeyCode(final int codePoint) {
    if (codePoint >= 'a' && codePoint <= 'z') {
      return codePoint - 'a' + 'A';
    }

    final boolean upperCase = codePoint >= 'A' && codePoint <= 'Z';
    final boolean isDigit = codePoint >= '0' && codePoint <= '9';
    return upperCase || isDigit ? codePoint : NO_VIRTUAL_KEY_CODE;
  }

  static String quote(final String value) {
    final StringBuilder builder = new StringBuilder(value.length() + QUOTE_CHARACTERS);
    builder.append('"');
    for (int index = 0; index < value.length(); index++) {
      final char character = value.charAt(index);
      switch (character) {
        case '"' -> builder.append("\\\"");
        case '\\' -> builder.append("\\\\");
        case LINE_FEED -> builder.append("\\n");
        case CARRIAGE_RETURN -> builder.append("\\r");
        case '\t' -> builder.append("\\t");
        default -> appendPlainOrUnicodeEscaped(builder, character);
      }
    }

    builder.append('"');

    return builder.toString();
  }

  private static void appendPlainOrUnicodeEscaped(final StringBuilder builder, final char character) {
    if (character < FIRST_UNESCAPED_CHARACTER || character == LINE_SEPARATOR || character == PARAGRAPH_SEPARATOR) {
      builder.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
    } else {
      builder.append(character);
    }
  }

  // the page domain must be enabled, or the script never runs
  static List<DevToolsCall> openWindowsInPlace() {
    final DevToolsCall enable = new DevToolsCall(ENABLE_PAGE_METHOD, "{}");
    final DevToolsCall script = new DevToolsCall(ADD_SCRIPT_METHOD, "{\"source\":" + quote(OPEN_IN_PLACE_SCRIPT) + OBJECT_END);
    return List.of(enable, script);
  }

  static final class DevToolsCall {

    private final String method;
    private final String parameters;

    DevToolsCall(final String method, final String parameters) {
      this.method = method;
      this.parameters = parameters;
    }

    String getMethod() {
      return this.method;
    }

    String getParameters() {
      return this.parameters;
    }

    @Override
    public boolean equals(final @Nullable Object other) {
      if (this == other) {
        return true;
      }

      if (!(other instanceof final DevToolsCall call)) {
        return false;
      }

      return this.method.equals(call.method) && this.parameters.equals(call.parameters);
    }

    @Override
    public int hashCode() {
      return Objects.hash(this.method, this.parameters);
    }

    @Override
    public String toString() {
      // the helper process has no Guava on its class path
      return "DevToolsCall{method=%s, parameters=%s}".formatted(this.method, this.parameters);
    }
  }

  private record KeyDefinition(String code, int keyCode, String text) {}
}
