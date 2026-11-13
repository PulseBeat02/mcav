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

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;

// The line comes from another process, and the helper reports only an IllegalArgumentException as a bad configuration.
@Tag("fuzz")
final class HelperConfigurationFuzzTest {

  @FuzzTest(maxDuration = "30s")
  void shouldReadBackWhatItAcceptsAndRefuseTheRestAsIllegal(final FuzzedDataProvider data) {
    final byte[] bytes = data.consumeRemainingAsBytes();
    final String line = new String(bytes, StandardCharsets.UTF_8);
    final HelperConfiguration configuration;
    try {
      configuration = HelperConfiguration.fromLine(line);
    } catch (final IllegalArgumentException exception) {
      // a bad configuration: the helper exits with status 2
      return;
    }

    final String written = configuration.toLine();
    final HelperConfiguration readBack = HelperConfiguration.fromLine(written);
    assertEquals(configuration, readBack);
  }
}
