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
import java.util.List;
import java.util.Map;

/**
 * Turns input for the browser into DevTools protocol calls ({@code Input.dispatchMouseEvent} and
 * {@code Input.dispatchKeyEvent}), which CEF executes inside the browser without any debugging port.
 *
 * <p>The DevTools input methods behave the same on every operating system, unlike the key events of JCEF, which are
 * translated per platform and lose keys such as Page Down. Named keys follow the W3C {@code KeyboardEvent.key} values
 * and carry the Windows virtual key code and physical key code of a US keyboard where one exists; text is typed one
 * character at a time as a key press that inserts the character, which is what typing it on a keyboard does.
 */
final class DevToolsInput {

  /**
   * The DevTools method of mouse events.
   */
  static final String MOUSE_METHOD = "Input.dispatchMouseEvent";

  /**
   * The DevTools method of key events.
   */
  static final String KEY_METHOD = "Input.dispatchKeyEvent";

  /**
   * The DevTools method that runs a script in every new document of the page.
   */
  static final String ADD_SCRIPT_METHOD = "Page.addScriptToEvaluateOnNewDocument";

  /**
   * The DevTools method that enables the page domain.
   */
  static final String ENABLE_PAGE_METHOD = "Page.enable";

  /**
   * Opens new windows in place. An off-screen JCEF browser cancels every popup before the helper hears of it, so
   * {@code window.open} and links and forms that target another window would do nothing. This script, which runs in
   * every frame before the page's own scripts, sends them to the page itself instead, but only during a click or a
   * key, as Chromium's popup blocker allows a popup: {@code window.open} and a click on such a link navigate the top
   * frame, for {@code http} and {@code https} addresses only, and such a form, or a form sent by a button that targets
   * another window, is submitted into its own frame. A link or form that targets a frame of the page by its name is left
   * to the page. Every navigation still goes through the navigation policy of the helper.
   */
  static final String OPEN_IN_PLACE_SCRIPT =
    """
    (() => {
      // like Chromium's popup blocker, only a click or a key lets a page open a window
      const isActive = () => navigator.userActivation === undefined || navigator.userActivation.isActive;
      const openInPlace = address => {
        let target;
        try {
          target = new URL(String(address), document.baseURI);
        } catch (error) {
          return;
        }
        if (target.protocol === 'http:' || target.protocol === 'https:') {
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
        // the window of a frame is its own window property, which an element of the page named so is not
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
        if (lower === '' || lower === '_self' || lower === '_top' || lower === '_parent') {
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
        const origin = event.target instanceof Element ? event.target.closest('a[href], area[href]') : null;
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
        if (button instanceof HTMLElement && button.hasAttribute('formtarget')) {
          if (opensElsewhere(button.getAttribute('formtarget'))) {
            button.setAttribute('formtarget', '_self');
          }
        } else if (opensElsewhere(form.target)) {
          form.target = '_self';
        }
      }, true);
    })();
    """;

  private static final String[] BUTTON_NAMES = { "left", "middle", "right" };

  // the bits of the buttons in the DevTools "buttons" field, in the order of the button numbers: left, middle, right
  private static final int[] BUTTON_BITS = { 1, 4, 2 };
  private static final Map<String, KeyDefinition> KEYS = createKeys();

  private DevToolsInput() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  private static Map<String, KeyDefinition> createKeys() {
    final Map<String, KeyDefinition> keys = new java.util.HashMap<>();
    keys.put("Enter", new KeyDefinition("Enter", 13, "\r"));
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
    for (int number = 1; number <= 24; number++) {
      final String name = "F" + number;
      keys.put(name, new KeyDefinition(name, 111 + number, ""));
    }
    return Map.copyOf(keys);
  }

  /**
   * Builds the DevTools calls of one mouse event. Every event carries the buttons that are held while it happens, so a
   * page sees a move with a held button as a drag.
   *
   * @param input the event
   * @param held  the buttons held before the event, as the bits of the DevTools {@code buttons} field
   * @return the calls, in order
   */
  static List<DevToolsCall> mouse(final MouseInput input, final int held) {
    final int x = input.getX();
    final int y = input.getY();
    final String button = BUTTON_NAMES[input.getButton()];
    final int clickCount = input.getClickCount();
    final int after = heldAfter(input, held);
    final String parameters =
      switch (input.getAction()) {
        case HelperProtocol.MOUSE_PRESS -> mouseParameters("mousePressed", x, y, after) + buttonParameters(button, clickCount) + "}";
        case HelperProtocol.MOUSE_RELEASE -> mouseParameters("mouseReleased", x, y, after) + buttonParameters(button, clickCount) + "}";
        case HelperProtocol.MOUSE_WHEEL -> mouseParameters("mouseWheel", x, y, after) +
        ",\"deltaX\":" +
        input.getDeltaX() +
        ",\"deltaY\":" +
        input.getDeltaY() +
        "}";
        default -> mouseParameters("mouseMoved", x, y, after) + ",\"button\":\"" + heldButtonName(after) + "\"}";
      };
    final DevToolsCall call = new DevToolsCall(MOUSE_METHOD, parameters);
    return List.of(call);
  }

  /**
   * Gets the buttons held after a mouse event: a press adds its button, a release takes it away.
   *
   * @param input the event
   * @param held  the buttons held before, as the bits of the DevTools {@code buttons} field
   * @return the buttons held after
   */
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
    return "none";
  }

  private static String mouseParameters(final String type, final int x, final int y, final int held) {
    return "{\"type\":\"" + type + "\",\"x\":" + x + ",\"y\":" + y + ",\"buttons\":" + held;
  }

  private static String buttonParameters(final String button, final int clickCount) {
    return ",\"button\":\"" + button + "\",\"clickCount\":" + clickCount;
  }

  /**
   * Checks whether a text names a key that can be pressed.
   *
   * @param name the text
   * @return true if the key has a definition
   */
  static boolean isKnownKey(final String name) {
    return KEYS.containsKey(name);
  }

  /**
   * Builds the DevTools calls that press and release a named key.
   *
   * @param name the W3C name of the key, such as {@code Enter}; a name without a definition is pressed with only its
   *             name
   * @return the calls, in order
   */
  static List<DevToolsCall> pressKey(final String name) {
    final KeyDefinition definition = KEYS.get(name);
    final String quotedName = quote(name);
    final String code = definition == null ? "" : ",\"code\":" + quote(definition.getCode());
    final int keyCode = definition == null ? 0 : definition.getKeyCode();
    final String text = definition == null ? "" : definition.getText();
    final String keyFields = ",\"key\":" + quotedName + code + ",\"windowsVirtualKeyCode\":" + keyCode;
    final List<DevToolsCall> calls = new ArrayList<>();
    if (text.isEmpty()) {
      calls.add(new DevToolsCall(KEY_METHOD, "{\"type\":\"rawKeyDown\"" + keyFields + "}"));
    } else {
      final String quotedText = quote(text);
      calls.add(
        new DevToolsCall(
          KEY_METHOD,
          "{\"type\":\"keyDown\"" + keyFields + ",\"text\":" + quotedText + ",\"unmodifiedText\":" + quotedText + "}"
        )
      );
    }
    calls.add(new DevToolsCall(KEY_METHOD, "{\"type\":\"keyUp\"" + keyFields + "}"));
    return calls;
  }

  /**
   * Builds the DevTools calls that type a text, one key press per character. A line break presses Enter, once for
   * {@code \r\n}.
   *
   * @param text the text
   * @return the calls, in order
   */
  static List<DevToolsCall> typeText(final String text) {
    final List<DevToolsCall> calls = new ArrayList<>();
    final int[] codePoints = text.codePoints().toArray();
    int previous = 0;
    for (final int codePoint : codePoints) {
      final boolean secondHalfOfCrLf = codePoint == '\n' && previous == '\r';
      previous = codePoint;
      if (secondHalfOfCrLf) {
        continue;
      }
      if (codePoint == '\n' || codePoint == '\r') {
        final List<DevToolsCall> enter = pressKey("Enter");
        calls.addAll(enter);
        continue;
      }
      final String character = Character.toString(codePoint);
      final String quoted = quote(character);
      final int keyCode = virtualKeyCode(codePoint);
      final String keyFields = ",\"key\":" + quoted + ",\"windowsVirtualKeyCode\":" + keyCode;
      calls.add(
        new DevToolsCall(KEY_METHOD, "{\"type\":\"keyDown\"" + keyFields + ",\"text\":" + quoted + ",\"unmodifiedText\":" + quoted + "}")
      );
      calls.add(new DevToolsCall(KEY_METHOD, "{\"type\":\"keyUp\"" + keyFields + "}"));
    }
    return calls;
  }

  /**
   * Gets the Windows virtual key code of a character on a US keyboard: letters and digits have one, other characters
   * are typed without.
   *
   * @param codePoint the character
   * @return the key code, or 0
   */
  static int virtualKeyCode(final int codePoint) {
    if (codePoint >= 'a' && codePoint <= 'z') {
      return codePoint - 'a' + 'A';
    }
    final boolean upperCase = codePoint >= 'A' && codePoint <= 'Z';
    final boolean digit = codePoint >= '0' && codePoint <= '9';
    return upperCase || digit ? codePoint : 0;
  }

  /**
   * Writes a string as a JSON string literal.
   *
   * @param value the string
   * @return the literal with its quotes
   */
  static String quote(final String value) {
    final StringBuilder builder = new StringBuilder(value.length() + 2);
    builder.append('"');
    for (int index = 0; index < value.length(); index++) {
      final char character = value.charAt(index);
      switch (character) {
        case '"' -> builder.append("\\\"");
        case '\\' -> builder.append("\\\\");
        case '\n' -> builder.append("\\n");
        case '\r' -> builder.append("\\r");
        case '\t' -> builder.append("\\t");
        default -> {
          if (character < 0x20 || character == 0x2028 || character == 0x2029) {
            builder.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
          } else {
            builder.append(character);
          }
        }
      }
    }
    builder.append('"');
    return builder.toString();
  }

  /**
   * Builds the DevTools calls that run {@link #OPEN_IN_PLACE_SCRIPT} in every new document, before the scripts of the
   * page: the page domain, without which the script is never run, and the script.
   *
   * @return the calls, in order
   */
  static List<DevToolsCall> openWindowsInPlace() {
    final DevToolsCall enable = new DevToolsCall(ENABLE_PAGE_METHOD, "{}");
    final DevToolsCall script = new DevToolsCall(ADD_SCRIPT_METHOD, "{\"source\":" + quote(OPEN_IN_PLACE_SCRIPT) + "}");
    return List.of(enable, script);
  }

  /**
   * A DevTools method and its parameters as a JSON object.
   */
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
  }

  /**
   * The physical key and Windows virtual key code of a named key, and the text it types.
   */
  private static final class KeyDefinition {

    private final String code;
    private final int keyCode;
    private final String text;

    KeyDefinition(final String code, final int keyCode, final String text) {
      this.code = code;
      this.keyCode = keyCode;
      this.text = text;
    }

    String getCode() {
      return this.code;
    }

    int getKeyCode() {
      return this.keyCode;
    }

    String getText() {
      return this.text;
    }
  }
}
