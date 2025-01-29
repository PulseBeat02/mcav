/*
 * This file is part of mcav, a media playback library for Minecraft
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

import java.util.OptionalInt;
import java.util.stream.IntStream;

/**
 * Represents a key code used in keyboard interactions.
 */
public enum KeyCode {
  /** Represents a null or undefined key. */
  NULL('\uE000'),
  /** Represents the Cancel key (typically mapped to Break or Ctrl+Break). */
  CANCEL('\uE001'), // ^break
  /** Represents the Help key. */
  HELP('\uE002'),
  /** Represents the Backspace key. */
  BACK_SPACE('\uE003'),
  /** Represents the Tab key. */
  TAB('\uE004'),
  /** Represents the Clear key. */
  CLEAR('\uE005'),
  /** Represents the Return key. */
  RETURN('\uE006'),
  /** Represents the Enter key. */
  ENTER('\uE007'),
  /** Represents the Shift key. */
  SHIFT('\uE008'),
  /** Represents the left Shift key (alias for {@link #SHIFT}). */
  LEFT_SHIFT(KeyCode.SHIFT),
  /** Represents the Control key. */
  CONTROL('\uE009'),
  /** Represents the left Control key (alias for {@link #CONTROL}). */
  LEFT_CONTROL(KeyCode.CONTROL),
  /** Represents the Alt key. */
  ALT('\uE00A'),
  /** Represents the left Alt key (alias for {@link #ALT}). */
  LEFT_ALT(KeyCode.ALT),
  /** Represents the Pause key. */
  PAUSE('\uE00B'),
  /** Represents the Escape key. */
  ESCAPE('\uE00C'),
  /** Represents the Space key. */
  SPACE('\uE00D'),
  /** Represents the Page Up key. */
  PAGE_UP('\uE00E'),
  /** Represents the Page Down key. */
  PAGE_DOWN('\uE00F'),
  /** Represents the End key. */
  END('\uE010'),
  /** Represents the Home key. */
  HOME('\uE011'),
  /** Represents the Left arrow key. */
  LEFT('\uE012'),
  /** Represents the Left arrow key (alias for {@link #LEFT}). */
  ARROW_LEFT(KeyCode.LEFT),
  /** Represents the Up arrow key. */
  UP('\uE013'),
  /** Represents the Up arrow key (alias for {@link #UP}). */
  ARROW_UP(KeyCode.UP),
  /** Represents the Right arrow key. */
  RIGHT('\uE014'),
  /** Represents the Right arrow key (alias for {@link #RIGHT}). */
  ARROW_RIGHT(KeyCode.RIGHT),
  /** Represents the Down arrow key. */
  DOWN('\uE015'),
  /** Represents the Down arrow key (alias for {@link #DOWN}). */
  ARROW_DOWN(KeyCode.DOWN),
  /** Represents the Insert key. */
  INSERT('\uE016'),
  /** Represents the Delete key. */
  DELETE('\uE017'),
  /** Represents the Semicolon key. */
  SEMICOLON('\uE018'),
  /** Represents the Equals key. */
  EQUALS('\uE019'),

  // Number pad keys
  /** Represents the number pad 0 key. */
  NUMPAD0('\uE01A'),
  /** Represents the number pad 1 key. */
  NUMPAD1('\uE01B'),
  /** Represents the number pad 2 key. */
  NUMPAD2('\uE01C'),
  /** Represents the number pad 3 key. */
  NUMPAD3('\uE01D'),
  /** Represents the number pad 4 key. */
  NUMPAD4('\uE01E'),
  /** Represents the number pad 5 key. */
  NUMPAD5('\uE01F'),
  /** Represents the number pad 6 key. */
  NUMPAD6('\uE020'),
  /** Represents the number pad 7 key. */
  NUMPAD7('\uE021'),
  /** Represents the number pad 8 key. */
  NUMPAD8('\uE022'),
  /** Represents the number pad 9 key. */
  NUMPAD9('\uE023'),
  /** Represents the number pad multiply key. */
  MULTIPLY('\uE024'),
  /** Represents the number pad add key. */
  ADD('\uE025'),
  /** Represents the number pad separator key. */
  SEPARATOR('\uE026'),
  /** Represents the number pad subtract key. */
  SUBTRACT('\uE027'),
  /** Represents the number pad decimal key. */
  DECIMAL('\uE028'),
  /** Represents the number pad divide key. */
  DIVIDE('\uE029'),

  // Function keys
  /** Represents the F1 function key. */
  F1('\uE031'),
  /** Represents the F2 function key. */
  F2('\uE032'),
  /** Represents the F3 function key. */
  F3('\uE033'),
  /** Represents the F4 function key. */
  F4('\uE034'),
  /** Represents the F5 function key. */
  F5('\uE035'),
  /** Represents the F6 function key. */
  F6('\uE036'),
  /** Represents the F7 function key. */
  F7('\uE037'),
  /** Represents the F8 function key. */
  F8('\uE038'),
  /** Represents the F9 function key. */
  F9('\uE039'),
  /** Represents the F10 function key. */
  F10('\uE03A'),
  /** Represents the F11 function key. */
  F11('\uE03B'),
  /** Represents the F12 function key. */
  F12('\uE03C'),

  /** Represents the Meta key (Windows key on PC, Command key on Mac). */
  META('\uE03D'),
  /** Represents the Command key (alias for {@link #META}). */
  COMMAND(KeyCode.META),

  /** Represents the Zenkaku/Hankaku key used in Japanese keyboards. */
  ZENKAKU_HANKAKU('\uE040');

  private final char keyCode;
  private final int codePoint;

  KeyCode(final KeyCode key) {
    this(key.charAt(0));
  }

  KeyCode(final char keyCode) {
    final String keyString = String.valueOf(keyCode);
    final IntStream codePoints = keyString.codePoints();
    final OptionalInt first = codePoints.findFirst();
    this.keyCode = keyCode;
    this.codePoint = first.orElseThrow();
  }

  /**
   * Returns the character representation of this key code.
   *
   * @return the character representation of this key code, or '\0' if the index is not 0.
   */
  public char charAt(final int index) {
    return (index == 0) ? this.keyCode : '\0';
  }

  /**
   * Returns the code point of this key code.
   *
   * @return the code point of this key code.
   */
  public int getCodePoint() {
    return this.codePoint;
  }
}
