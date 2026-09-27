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
import static org.junit.jupiter.api.Assertions.assertThrows;

import me.brandonli.mcav.browser.testing.UtilityClassAssertions;
import me.brandonli.mcav.media.player.PlayerException;
import org.junit.jupiter.api.Test;

class HelperProcessesTest {

  @Test
  void aStoppedRegistryRefusesStartsUntilItIsOpenedAgain() {
    HelperProcesses.requireOpen();
    HelperProcesses.closeAll();
    try {
      final PlayerException failure = assertThrows(PlayerException.class, HelperProcesses::requireOpen);
      assertEquals("The browser module is stopped", failure.getMessage());
    } finally {
      HelperProcesses.open();
    }
    HelperProcesses.requireOpen();
  }

  @Test
  void theRegistryIsNotInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(HelperProcesses.class);
  }
}
