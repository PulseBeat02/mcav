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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import me.brandonli.mcav.browser.testing.EqualityAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class MouseInputTest {

  private static final int ACTION = 1;
  private static final int PAGE_X = 320;
  private static final int PAGE_Y = 240;
  private static final int BUTTON = 2;
  private static final int CLICK_COUNT = 3;
  private static final int DELTA_X = 4;
  private static final int DELTA_Y = -120;

  private MouseInput input;

  @BeforeEach
  void setUp() {
    this.input = new MouseInput(ACTION, PAGE_X, PAGE_Y, BUTTON, CLICK_COUNT, DELTA_X, DELTA_Y);
  }

  @Test
  void shouldTreatInputsWithTheSameValuesAsEqual() {
    EqualityAssertions.assertEqualityContract(
      this.input,
      new MouseInput(ACTION, PAGE_X, PAGE_Y, BUTTON, CLICK_COUNT, DELTA_X, DELTA_Y),
      new MouseInput(ACTION + 1, PAGE_X, PAGE_Y, BUTTON, CLICK_COUNT, DELTA_X, DELTA_Y),
      new MouseInput(ACTION, PAGE_X + 1, PAGE_Y, BUTTON, CLICK_COUNT, DELTA_X, DELTA_Y),
      new MouseInput(ACTION, PAGE_X, PAGE_Y + 1, BUTTON, CLICK_COUNT, DELTA_X, DELTA_Y),
      new MouseInput(ACTION, PAGE_X, PAGE_Y, BUTTON + 1, CLICK_COUNT, DELTA_X, DELTA_Y),
      new MouseInput(ACTION, PAGE_X, PAGE_Y, BUTTON, CLICK_COUNT + 1, DELTA_X, DELTA_Y),
      new MouseInput(ACTION, PAGE_X, PAGE_Y, BUTTON, CLICK_COUNT, DELTA_X + 1, DELTA_Y),
      new MouseInput(ACTION, PAGE_X, PAGE_Y, BUTTON, CLICK_COUNT, DELTA_X, DELTA_Y + 1)
    );
  }

  @Test
  void shouldHashAnInputWithAnotherWheelDeltaDifferently() {
    final MouseInput otherInput = new MouseInput(ACTION, PAGE_X, PAGE_Y, BUTTON, CLICK_COUNT, DELTA_X, DELTA_Y + 1);
    final int hashCode = this.input.hashCode();
    final int otherHashCode = otherInput.hashCode();
    assertNotEquals(hashCode, otherHashCode);
  }

  @Test
  void shouldDescribeAnInputByEveryValue() {
    final String description = this.input.toString();
    assertEquals("MouseInput{action=1, x=320, y=240, button=2, clickCount=3, deltaX=4, deltaY=-120}", description);
  }
}
