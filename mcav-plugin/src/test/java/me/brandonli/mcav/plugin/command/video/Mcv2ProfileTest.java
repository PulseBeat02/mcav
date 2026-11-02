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
package me.brandonli.mcav.plugin.command.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.util.List;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Settings;
import org.incendo.cloud.caption.CaptionVariable;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.parser.standard.EnumParser;
import org.incendo.cloud.parser.standard.EnumParser.EnumParseException;
import org.junit.jupiter.api.Test;

final class Mcv2ProfileTest {

  private static final String ADAPTIVE_NAME = "ADAPTIVE";
  private static final String SHIP_NAME = "SHIP";
  private static final String LIVE_NAME = "LIVE";

  @Test
  void shouldNameTheEncoderSettings() {
    final Settings settings = Mcv2Profile.DEFAULT.getSettings();
    assertEquals(Settings.DEFAULT, settings);

    final Settings fastSettings = Mcv2Profile.FAST.getSettings();
    assertEquals(Settings.FAST, fastSettings);

    final List<Mcv2Profile> list = List.of(Mcv2Profile.values());
    assertEquals(List.of(Mcv2Profile.DEFAULT, Mcv2Profile.FAST), list);
  }

  @Test
  void shouldRefuseRemovedProfiles() {
    for (final String old : List.of(
      ADAPTIVE_NAME,
      SHIP_NAME,
      "LOW",
      "KEYFRAME",
      "INTRA",
      LIVE_NAME,
      "LIVE_ADAPTIVE",
      "LIVE_FAST",
      "LIVE_KEYFRAME"
    )) {
      assertThrows(IllegalArgumentException.class, () -> Mcv2Profile.valueOf(old));
    }
  }

  @Test
  void shouldListTheValidProfilesInCommandErrors() {
    final CommandContext<Object> context = mock();
    final EnumParser<Object, Mcv2Profile> parser = new EnumParser<>(Mcv2Profile.class);
    for (final String old : List.of(ADAPTIVE_NAME, SHIP_NAME, LIVE_NAME)) {
      final EnumParseException failure = assertInstanceOf(
        EnumParseException.class,
        parser.parse(context, CommandInput.of(old)).failure().orElseThrow()
      );

      final CaptionVariable expected = CaptionVariable.of("input", old);
      final CaptionVariable captionVariable = failure.captionVariables()[0];
      assertEquals(expected, captionVariable);

      final CaptionVariable captionVariableExpected = CaptionVariable.of("acceptableValues", "default, fast");
      final CaptionVariable secondCaptionVariable = failure.captionVariables()[1];
      assertEquals(captionVariableExpected, secondCaptionVariable);
    }

    final Mcv2Profile parserParser = parser.parse(context, CommandInput.of("DEFAULT")).parsedValue().orElseThrow();
    assertEquals(Mcv2Profile.DEFAULT, parserParser);

    final Mcv2Profile secondParser = parser.parse(context, CommandInput.of("FAST")).parsedValue().orElseThrow();
    assertEquals(Mcv2Profile.FAST, secondParser);
  }
}
