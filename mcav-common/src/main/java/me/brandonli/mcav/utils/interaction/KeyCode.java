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
package me.brandonli.mcav.utils.interaction;

import com.google.common.base.Preconditions;
import java.util.Locale;
import java.util.Optional;

/**
 * Special keys that interactive players can type, such as {@link #ENTER} or {@link #ARROW_LEFT}. Each key is
 * represented by a character from the private use area of Unicode, the same convention WebDriver uses, so the
 * characters can be mixed into ordinary text; see {@link KeyUtils}.
 *
 * <p>This enum is immutable and thread-safe.
 *
 * @since 1.0.0
 */
public enum KeyCode {
  /**
   * Represents a null or undefined key.
   *
   * @since 1.0.0
   */
  NULL('\uE000'),
  /**
   * Represents the Cancel key (typically mapped to Break or Ctrl+Break).
   *
   * @since 1.0.0
   */
  CANCEL('\uE001'),
  /**
   * Represents the Help key.
   *
   * @since 1.0.0
   */
  HELP('\uE002'),
  /**
   * Represents the Backspace key.
   *
   * @since 1.0.0
   */
  BACK_SPACE('\uE003'),
  /**
   * Represents the Tab key.
   *
   * @since 1.0.0
   */
  TAB('\uE004'),
  /**
   * Represents the Clear key.
   *
   * @since 1.0.0
   */
  CLEAR('\uE005'),
  /**
   * Represents the Return key.
   *
   * @since 1.0.0
   */
  RETURN('\uE006'),
  /**
   * Represents the Enter key.
   *
   * @since 1.0.0
   */
  ENTER('\uE007'),
  /**
   * Represents the Shift key.
   *
   * @since 1.0.0
   */
  SHIFT('\uE008'),
  /**
   * Represents the left Shift key (alias for {@link #SHIFT}).
   *
   * @since 1.0.0
   */
  LEFT_SHIFT(KeyCode.SHIFT),
  /**
   * Represents the Control key.
   *
   * @since 1.0.0
   */
  CONTROL('\uE009'),
  /**
   * Represents the left Control key (alias for {@link #CONTROL}).
   *
   * @since 1.0.0
   */
  LEFT_CONTROL(KeyCode.CONTROL),
  /**
   * Represents the Alt key.
   *
   * @since 1.0.0
   */
  ALT('\uE00A'),
  /**
   * Represents the left Alt key (alias for {@link #ALT}).
   *
   * @since 1.0.0
   */
  LEFT_ALT(KeyCode.ALT),
  /**
   * Represents the Pause key.
   *
   * @since 1.0.0
   */
  PAUSE('\uE00B'),
  /**
   * Represents the Escape key.
   *
   * @since 1.0.0
   */
  ESCAPE('\uE00C'),
  /**
   * Represents the Space key.
   *
   * @since 1.0.0
   */
  SPACE('\uE00D'),
  /**
   * Represents the Page Up key.
   *
   * @since 1.0.0
   */
  PAGE_UP('\uE00E'),
  /**
   * Represents the Page Down key.
   *
   * @since 1.0.0
   */
  PAGE_DOWN('\uE00F'),
  /**
   * Represents the End key.
   *
   * @since 1.0.0
   */
  END('\uE010'),
  /**
   * Represents the Home key.
   *
   * @since 1.0.0
   */
  HOME('\uE011'),
  /**
   * Represents the Left arrow key.
   *
   * @since 1.0.0
   */
  LEFT('\uE012'),
  /**
   * Represents the Left arrow key (alias for {@link #LEFT}).
   *
   * @since 1.0.0
   */
  ARROW_LEFT(KeyCode.LEFT),
  /**
   * Represents the Up arrow key.
   *
   * @since 1.0.0
   */
  UP('\uE013'),
  /**
   * Represents the Up arrow key (alias for {@link #UP}).
   *
   * @since 1.0.0
   */
  ARROW_UP(KeyCode.UP),
  /**
   * Represents the Right arrow key.
   *
   * @since 1.0.0
   */
  RIGHT('\uE014'),
  /**
   * Represents the Right arrow key (alias for {@link #RIGHT}).
   *
   * @since 1.0.0
   */
  ARROW_RIGHT(KeyCode.RIGHT),
  /**
   * Represents the Down arrow key.
   *
   * @since 1.0.0
   */
  DOWN('\uE015'),
  /**
   * Represents the Down arrow key (alias for {@link #DOWN}).
   *
   * @since 1.0.0
   */
  ARROW_DOWN(KeyCode.DOWN),
  /**
   * Represents the Insert key.
   *
   * @since 1.0.0
   */
  INSERT('\uE016'),
  /**
   * Represents the Delete key.
   *
   * @since 1.0.0
   */
  DELETE('\uE017'),
  /**
   * Represents the Semicolon key.
   *
   * @since 1.0.0
   */
  SEMICOLON('\uE018'),
  /**
   * Represents the Equals key.
   *
   * @since 1.0.0
   */
  EQUALS('\uE019'),

  /**
   * Represents the number pad 0 key.
   *
   * @since 1.0.0
   */
  NUMPAD0('\uE01A'),
  /**
   * Represents the number pad 1 key.
   *
   * @since 1.0.0
   */
  NUMPAD1('\uE01B'),
  /**
   * Represents the number pad 2 key.
   *
   * @since 1.0.0
   */
  NUMPAD2('\uE01C'),
  /**
   * Represents the number pad 3 key.
   *
   * @since 1.0.0
   */
  NUMPAD3('\uE01D'),
  /**
   * Represents the number pad 4 key.
   *
   * @since 1.0.0
   */
  NUMPAD4('\uE01E'),
  /**
   * Represents the number pad 5 key.
   *
   * @since 1.0.0
   */
  NUMPAD5('\uE01F'),
  /**
   * Represents the number pad 6 key.
   *
   * @since 1.0.0
   */
  NUMPAD6('\uE020'),
  /**
   * Represents the number pad 7 key.
   *
   * @since 1.0.0
   */
  NUMPAD7('\uE021'),
  /**
   * Represents the number pad 8 key.
   *
   * @since 1.0.0
   */
  NUMPAD8('\uE022'),
  /**
   * Represents the number pad 9 key.
   *
   * @since 1.0.0
   */
  NUMPAD9('\uE023'),
  /**
   * Represents the number pad multiply key.
   *
   * @since 1.0.0
   */
  MULTIPLY('\uE024'),
  /**
   * Represents the number pad add key.
   *
   * @since 1.0.0
   */
  ADD('\uE025'),
  /**
   * Represents the number pad separator key.
   *
   * @since 1.0.0
   */
  SEPARATOR('\uE026'),
  /**
   * Represents the number pad subtract key.
   *
   * @since 1.0.0
   */
  SUBTRACT('\uE027'),
  /**
   * Represents the number pad decimal key.
   *
   * @since 1.0.0
   */
  DECIMAL('\uE028'),
  /**
   * Represents the number pad divide key.
   *
   * @since 1.0.0
   */
  DIVIDE('\uE029'),

  /**
   * Represents the F1 function key.
   *
   * @since 1.0.0
   */
  F1('\uE031'),
  /**
   * Represents the F2 function key.
   *
   * @since 1.0.0
   */
  F2('\uE032'),
  /**
   * Represents the F3 function key.
   *
   * @since 1.0.0
   */
  F3('\uE033'),
  /**
   * Represents the F4 function key.
   *
   * @since 1.0.0
   */
  F4('\uE034'),
  /**
   * Represents the F5 function key.
   *
   * @since 1.0.0
   */
  F5('\uE035'),
  /**
   * Represents the F6 function key.
   *
   * @since 1.0.0
   */
  F6('\uE036'),
  /**
   * Represents the F7 function key.
   *
   * @since 1.0.0
   */
  F7('\uE037'),
  /**
   * Represents the F8 function key.
   *
   * @since 1.0.0
   */
  F8('\uE038'),
  /**
   * Represents the F9 function key.
   *
   * @since 1.0.0
   */
  F9('\uE039'),
  /**
   * Represents the F10 function key.
   *
   * @since 1.0.0
   */
  F10('\uE03A'),
  /**
   * Represents the F11 function key.
   *
   * @since 1.0.0
   */
  F11('\uE03B'),
  /**
   * Represents the F12 function key.
   *
   * @since 1.0.0
   */
  F12('\uE03C'),

  /**
   * Represents the Meta key (Windows key on PC, Command key on Mac).
   *
   * @since 1.0.0
   */
  META('\uE03D'),
  /**
   * Represents the Command key (alias for {@link #META}).
   *
   * @since 1.0.0
   */
  COMMAND(KeyCode.META),

  /**
   * Represents the Zenkaku/Hankaku key used in Japanese keyboards.
   *
   * @since 1.0.0
   */
  ZENKAKU_HANKAKU('\uE040');

  private static final KeyCode[] VALUES = values();

  private final char keyCharacter;

  KeyCode(final KeyCode alias) {
    this(alias.keyCharacter);
  }

  KeyCode(final char keyCharacter) {
    this.keyCharacter = keyCharacter;
  }

  /**
   * Finds a key by its name, ignoring case, such as {@code enter} or {@code ARROW_LEFT}.
   *
   * @param name the name of the key
   * @return the key, or empty if no key has that name
   * @throws NullPointerException if {@code name} is null
   * @since 1.0.0
   */
  public static Optional<KeyCode> fromName(final String name) {
    Preconditions.checkNotNull(name, "Name must not be null!");

    final String upper = name.toUpperCase(Locale.ROOT);
    for (final KeyCode key : VALUES) {
      final String keyName = key.name();
      if (keyName.equals(upper)) {
        return Optional.of(key);
      }
    }

    return Optional.empty();
  }

  /**
   * Returns the character that represents this key. The characters are taken from the private use area of Unicode
   * and are understood by WebDriver-based browsers.
   *
   * @return the key character
   * @since 1.0.0
   */
  public char getKeyChar() {
    return this.keyCharacter;
  }

  /**
   * Returns the code point of {@link #getKeyChar()}.
   *
   * @return the code point
   * @since 1.0.0
   */
  public int getCodePoint() {
    return this.keyCharacter;
  }

  /**
   * Returns the key as a one-character string, for example to pass to a keyboard API.
   *
   * @return the key character as a string
   * @since 1.0.0
   */
  public String asString() {
    return String.valueOf(this.keyCharacter);
  }
}
