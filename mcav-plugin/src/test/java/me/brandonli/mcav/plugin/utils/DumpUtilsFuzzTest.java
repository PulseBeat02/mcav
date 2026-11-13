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
package me.brandonli.mcav.plugin.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;

@Tag("fuzz")
final class DumpUtilsFuzzTest {

  // every redaction needs a digit, an equals sign, a colon, a slash or an option dash
  private static final Pattern REDACTION_TRIGGER = Pattern.compile("[0-9=:/-]");

  @FuzzTest(maxDuration = "30s")
  void shouldChangeOnlyALogLineThatHoldsSomethingToRedact(final FuzzedDataProvider data) {
    final String line = data.consumeRemainingAsString();
    final String redacted = DumpUtils.redactLogLine(line);
    final Matcher trigger = REDACTION_TRIGGER.matcher(line);
    if (trigger.find()) {
      return;
    }

    assertEquals(line, redacted);
  }
}
