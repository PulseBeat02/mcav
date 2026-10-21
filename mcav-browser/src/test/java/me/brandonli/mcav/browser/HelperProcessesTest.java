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
import org.junit.jupiter.api.Test;

class HelperProcessesTest {

  @Test
  void shouldRefuseStartsInAStoppedRegistryUntilItIsOpenedAgain() {
    HelperProcesses.requireOpen();
    HelperProcesses.closeAll();
    try {
      final BrowserException failure = assertThrows(BrowserException.class, HelperProcesses::requireOpen);
      final String message = failure.getMessage();
      assertEquals("The browser module is stopped!", message);
    } finally {
      HelperProcesses.open();
    }

    HelperProcesses.requireOpen();
  }

  @Test
  void shouldNotInstantiateTheRegistry() {
    UtilityClassAssertions.assertNotInstantiable(HelperProcesses.class);
  }
}
