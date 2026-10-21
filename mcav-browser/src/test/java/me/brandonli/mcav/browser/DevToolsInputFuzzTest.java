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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;

@Tag("fuzz")
final class DevToolsInputFuzzTest {

  private static final Pattern LINE_BREAK = Pattern.compile("\r\n|\r|\n");
  private static final String ENTER_TEXT = "\r";

  private static final String KEY_DOWN_TYPE = "keyDown";

  @FuzzTest(maxDuration = "30s")
  void shouldQuoteEveryTextSoThatItReadsBackAsItself(final FuzzedDataProvider data) {
    final String text = data.consumeRemainingAsString();
    final String quoted = DevToolsInput.quote(text);
    final JsonElement element = JsonParser.parseString(quoted);
    final String readBack = element.getAsString();
    assertEquals(text, readBack);
  }

  @FuzzTest(maxDuration = "30s")
  void shouldTypeExactlyTheTextWithEveryLineBreakAsEnter(final FuzzedDataProvider data) {
    final String text = data.consumeRemainingAsString();
    final List<DevToolsInput.DevToolsCall> calls = DevToolsInput.typeText(text);
    final String typed = this.collectTypedText(calls);
    final Matcher lineBreaks = LINE_BREAK.matcher(text);
    final String expected = lineBreaks.replaceAll(ENTER_TEXT);
    assertEquals(expected, typed);
  }

  @FuzzTest(maxDuration = "30s")
  void shouldPressAnyKeyNameAsThatKey(final FuzzedDataProvider data) {
    final String name = data.consumeRemainingAsString();
    final List<DevToolsInput.DevToolsCall> calls = DevToolsInput.pressKey(name);
    for (final DevToolsInput.DevToolsCall call : calls) {
      final JsonObject parameters = this.parse(call);
      final JsonElement key = parameters.get("key");
      final String keyName = key.getAsString();
      assertEquals(name, keyName);
    }
  }

  private String collectTypedText(final List<DevToolsInput.DevToolsCall> calls) {
    final StringBuilder typed = new StringBuilder();
    for (final DevToolsInput.DevToolsCall call : calls) {
      final JsonObject parameters = this.parse(call);
      final JsonElement type = parameters.get("type");
      final String typeName = type.getAsString();
      if (typeName.equals(KEY_DOWN_TYPE)) {
        final JsonElement text = parameters.get("text");
        final String character = text.getAsString();
        typed.append(character);
      }
    }
    return typed.toString();
  }

  private JsonObject parse(final DevToolsInput.DevToolsCall call) {
    final String parameters = call.getParameters();
    final JsonElement element = JsonParser.parseString(parameters);
    return element.getAsJsonObject();
  }
}
