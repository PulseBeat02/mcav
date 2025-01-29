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

public enum KeyCode {
  NULL('\uE000'),
  CANCEL('\uE001'), // ^break
  HELP('\uE002'),
  BACK_SPACE('\uE003'),
  TAB('\uE004'),
  CLEAR('\uE005'),
  RETURN('\uE006'),
  ENTER('\uE007'),
  SHIFT('\uE008'),
  LEFT_SHIFT(KeyCode.SHIFT),
  CONTROL('\uE009'),
  LEFT_CONTROL(KeyCode.CONTROL),
  ALT('\uE00A'),
  LEFT_ALT(KeyCode.ALT),
  PAUSE('\uE00B'),
  ESCAPE('\uE00C'),
  SPACE('\uE00D'),
  PAGE_UP('\uE00E'),
  PAGE_DOWN('\uE00F'),
  END('\uE010'),
  HOME('\uE011'),
  LEFT('\uE012'),
  ARROW_LEFT(KeyCode.LEFT),
  UP('\uE013'),
  ARROW_UP(KeyCode.UP),
  RIGHT('\uE014'),
  ARROW_RIGHT(KeyCode.RIGHT),
  DOWN('\uE015'),
  ARROW_DOWN(KeyCode.DOWN),
  INSERT('\uE016'),
  DELETE('\uE017'),
  SEMICOLON('\uE018'),
  EQUALS('\uE019'),

  // Number pad keys
  NUMPAD0('\uE01A'),
  NUMPAD1('\uE01B'),
  NUMPAD2('\uE01C'),
  NUMPAD3('\uE01D'),
  NUMPAD4('\uE01E'),
  NUMPAD5('\uE01F'),
  NUMPAD6('\uE020'),
  NUMPAD7('\uE021'),
  NUMPAD8('\uE022'),
  NUMPAD9('\uE023'),
  MULTIPLY('\uE024'),
  ADD('\uE025'),
  SEPARATOR('\uE026'),
  SUBTRACT('\uE027'),
  DECIMAL('\uE028'),
  DIVIDE('\uE029'),

  // Function keys
  F1('\uE031'),
  F2('\uE032'),
  F3('\uE033'),
  F4('\uE034'),
  F5('\uE035'),
  F6('\uE036'),
  F7('\uE037'),
  F8('\uE038'),
  F9('\uE039'),
  F10('\uE03A'),
  F11('\uE03B'),
  F12('\uE03C'),

  META('\uE03D'),
  COMMAND(KeyCode.META),

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

  public char charAt(final int index) {
    return (index == 0) ? this.keyCode : '\0';
  }

  public int getCodePoint() {
    return this.codePoint;
  }
}
