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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.utils.IOUtils;
import org.openqa.selenium.Keys;

/**
 * The key names both backends accept in {@link BrowserPlayer#sendKeyEvent(String)}: the names of the W3C
 * {@code KeyboardEvent.key} values, such as {@code Enter}, {@code Backspace}, or {@code ArrowLeft}. Playwright
 * understands them directly; for Selenium they are mapped to WebDriver key characters.
 */
final class PlaywrightKeys {

  private static final String RESOURCE = "keybinds.json";
  private static final Set<String> SPECIAL_KEYS = loadSpecialKeys();
  private static final Map<String, Keys> SELENIUM_KEYS = createSeleniumKeys();

  private PlaywrightKeys() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  // filled with put rather than Map.ofEntries, whose inference over many generic arguments stalls the type checker
  private static Map<String, Keys> createSeleniumKeys() {
    final Map<String, Keys> keys = new HashMap<>();
    keys.put("Enter", Keys.ENTER);
    keys.put("Tab", Keys.TAB);
    keys.put("Backspace", Keys.BACK_SPACE);
    keys.put("Delete", Keys.DELETE);
    keys.put("Escape", Keys.ESCAPE);
    keys.put(" ", Keys.SPACE);
    keys.put("ArrowUp", Keys.ARROW_UP);
    keys.put("ArrowDown", Keys.ARROW_DOWN);
    keys.put("ArrowLeft", Keys.ARROW_LEFT);
    keys.put("ArrowRight", Keys.ARROW_RIGHT);
    keys.put("Home", Keys.HOME);
    keys.put("End", Keys.END);
    keys.put("PageUp", Keys.PAGE_UP);
    keys.put("PageDown", Keys.PAGE_DOWN);
    keys.put("Insert", Keys.INSERT);
    keys.put("Shift", Keys.SHIFT);
    keys.put("Control", Keys.CONTROL);
    keys.put("Alt", Keys.ALT);
    keys.put("Meta", Keys.META);
    keys.put("Clear", Keys.CLEAR);
    keys.put("Pause", Keys.PAUSE);
    keys.put("Help", Keys.HELP);
    keys.put("F1", Keys.F1);
    keys.put("F2", Keys.F2);
    keys.put("F3", Keys.F3);
    keys.put("F4", Keys.F4);
    keys.put("F5", Keys.F5);
    keys.put("F6", Keys.F6);
    keys.put("F7", Keys.F7);
    keys.put("F8", Keys.F8);
    keys.put("F9", Keys.F9);
    keys.put("F10", Keys.F10);
    keys.put("F11", Keys.F11);
    keys.put("F12", Keys.F12);
    return Map.copyOf(keys);
  }

  private static Set<String> loadSpecialKeys() {
    final Reader reader = IOUtils.getResourceAsStreamReader(RESOURCE);
    return parse(reader);
  }

  /**
   * Reads a key list, a JSON array of the key names Playwright presses, and closes the reader.
   *
   * @param reader the reader of the list
   * @return the key names
   * @throws PlayerException if the list is empty or cannot be read
   */
  @VisibleForTesting
  static Set<String> parse(final Reader reader) {
    final Gson gson = GsonProvider.getSimple();
    final TypeToken<Set<String>> token = new TypeToken<>() {};
    final Type type = token.getType();
    try (reader) {
      final Set<String> keys = gson.fromJson(reader, type);
      if (keys == null) {
        throw new PlayerException("The key list resource " + RESOURCE + " is empty");
      }
      return Set.copyOf(keys);
    } catch (final IOException exception) {
      final String message = exception.getMessage();
      throw new PlayerException("Failed to read the key list resource: " + message, exception);
    }
  }

  /**
   * Checks whether the text is the name of a special key rather than text to type.
   *
   * @param text the text passed to {@link BrowserPlayer#sendKeyEvent(String)}
   * @return true if the text names a key
   */
  static boolean isSpecialKey(final String text) {
    Preconditions.checkNotNull(text, "Text must not be null");
    return SPECIAL_KEYS.contains(text);
  }

  /**
   * Converts text for {@link BrowserPlayer#sendKeyEvent(String)} into what Selenium types: a key name becomes
   * the matching WebDriver key character, anything else is typed as is.
   *
   * @param text the text or key name
   * @return the string to send with {@code Actions.sendKeys}
   */
  static String toSeleniumKeys(final String text) {
    Preconditions.checkNotNull(text, "Text must not be null");
    final Keys key = SELENIUM_KEYS.get(text);
    if (key == null) {
      return text;
    }
    return key.toString();
  }
}
