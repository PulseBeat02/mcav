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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.brandonli.mcav.utils.interaction.MouseClick;
import org.junit.jupiter.api.Test;

/**
 * Tests the factory methods of {@link BrowserPlayer}. Players that were never started open no browser.
 */
final class BrowserPlayerTest {

  @Test
  void createsSeleniumPlayers() {
    final BrowserPlayer defaults = BrowserPlayer.selenium();
    final BrowserPlayer custom = BrowserPlayer.selenium("--headless=new");
    assertInstanceOf(SeleniumPlayer.class, defaults);
    assertInstanceOf(SeleniumPlayer.class, custom);
    final boolean playing = defaults.isPlaying();
    assertFalse(playing);
    final boolean released = defaults.release();
    assertTrue(released);
  }

  @Test
  void createsPlaywrightPlayers() {
    final BrowserPlayer player = BrowserPlayer.playwright();
    assertInstanceOf(PlaywrightPlayer.class, player);
    final boolean playing = player.isPlaying();
    final boolean released = player.release();
    assertFalse(playing);
    assertTrue(released);
  }

  @Test
  void runsChromeHeadlessAndMutedByDefault() {
    final List<String> expected = List.of("--headless=new", "--disable-gpu", "--mute-audio", "--hide-scrollbars");
    final List<String> actual = BrowserPlayer.DEFAULT_CHROME_ARGUMENTS;
    assertEquals(expected, actual);
  }

  @Test
  void keepsTheDefaultArgumentsFromBeingChanged() {
    final List<String> defaults = BrowserPlayer.DEFAULT_CHROME_ARGUMENTS;
    assertThrows(UnsupportedOperationException.class, () -> defaults.add("--incognito"));
    assertThrows(UnsupportedOperationException.class, () -> defaults.set(0, "--headless=old"));
  }

  @Test
  void ignoresInputBeforeStarting() {
    final BrowserPlayer selenium = BrowserPlayer.selenium();
    final BrowserPlayer playwright = BrowserPlayer.playwright();
    for (final BrowserPlayer player : new BrowserPlayer[] { selenium, playwright }) {
      player.moveMouse(1, 1);
      player.sendMouseEvent(MouseClick.LEFT, 1, 1);
      player.sendKeyEvent("Enter");
      final boolean playing = player.isPlaying();
      assertFalse(playing);
    }
  }

  @Test
  void rejectsNullArguments() {
    final BrowserPlayer selenium = BrowserPlayer.selenium();
    final BrowserPlayer playwright = BrowserPlayer.playwright();
    assertThrows(NullPointerException.class, () -> BrowserPlayer.selenium((String[]) null));
    assertThrows(NullPointerException.class, () -> BrowserPlayer.playwright((String[]) null));
    for (final BrowserPlayer player : new BrowserPlayer[] { selenium, playwright }) {
      assertThrows(NullPointerException.class, () -> player.sendMouseEvent(null, 0, 0));
      assertThrows(NullPointerException.class, () -> player.sendKeyEvent(null));
    }
  }
}
