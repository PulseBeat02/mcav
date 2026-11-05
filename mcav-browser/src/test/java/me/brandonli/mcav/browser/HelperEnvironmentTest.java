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

import java.util.Map;
import me.brandonli.mcav.browser.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

class HelperEnvironmentTest {

  private static final String SYSTEM_PROGRAMS_FOLDER = "/usr/bin";
  private static final String SERVER_HOME_FOLDER = "/home/server";
  private static final String WINDOWS_SYSTEM_FOLDER = "C:\\Windows";
  private static final String PRELOADED_LIBRARY_FILE = "/tmp/evil.so";

  @Test
  void shouldOnlyKeepTheVariablesAProgramNeeds() {
    final Map<String, String> parent = Map.of(
      "PATH",
      SYSTEM_PROGRAMS_FOLDER,
      "HOME",
      SERVER_HOME_FOLDER,
      "SystemRoot",
      WINDOWS_SYSTEM_FOLDER,
      "DISCORD_TOKEN",
      "secret",
      "AWS_SECRET_ACCESS_KEY",
      "secret",
      "LD_PRELOAD",
      PRELOADED_LIBRARY_FILE,
      "DISPLAY",
      ":0"
    );
    final Map<String, String> kept = HelperEnvironment.filter(parent);
    final Map<String, String> expectedEnvironment = Map.of(
      "PATH",
      SYSTEM_PROGRAMS_FOLDER,
      "HOME",
      SERVER_HOME_FOLDER,
      "SystemRoot",
      WINDOWS_SYSTEM_FOLDER
    );
    assertEquals(expectedEnvironment, kept);
  }

  @Test
  void shouldNotInstantiateTheEnvironment() {
    UtilityClassAssertions.assertNotInstantiable(HelperEnvironment.class);
  }
}
