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
package me.brandonli.mcav.plugin.locale;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.function.Function;
import me.brandonli.mcav.plugin.testing.Components;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

/**
 * Tests {@link LocaleTools}.
 */
final class LocaleToolsTest {

  private static final String LINK_KEY = "mcav.command.audio.http";
  private static final String LINK_PREFIX = "Click on the URL to listen onto the website! ";

  private static final String SCREEN_BUILT_TEXT = "Built a new map screen!";
  private static final String SCREEN_BUILD_KEY = "mcav.command.screen.build";

  private static final String FIRST_ARGUMENT = "first";
  private static final String SECOND_ARGUMENT = "second";

  @Test
  void shouldRenderAMessageWithoutArguments() {
    final LocaleTools.NullComponent message = LocaleTools.direct(SCREEN_BUILD_KEY);
    final Component component = message.build();
    final String text = Components.plain(component);
    final TextColor color = this.findColor(component);
    assertEquals(SCREEN_BUILT_TEXT, text);
    assertEquals(NamedTextColor.GOLD, color);
  }

  private TextColor findColor(final Component component) {
    final TextColor color = component.color();
    if (color != null) {
      return color;
    }
    final List<Component> children = component.children();
    final Component first = children.getFirst();
    return this.findColor(first);
  }

  @Test
  void shouldInsertTheArgumentIntoTheUrlPlaceholders() {
    final LocaleTools.UniComponent<String> message = LocaleTools.direct(LINK_KEY, null);
    final Component component = message.build("http://localhost:3000/");
    final String text = Components.plain(component);
    final List<ClickEvent<?>> events = new ArrayList<>();
    this.collectClickEvents(component, events);
    assertEquals(LINK_PREFIX + "http://localhost:3000/", text);
    final int eventCount = events.size();
    assertEquals(1, eventCount);
    final ClickEvent<?> event = events.getFirst();
    final ClickEvent.Action<?> action = event.action();
    assertEquals(ClickEvent.Action.OPEN_URL, action);
    final ClickEvent.Payload payload = event.payload();
    final ClickEvent.Payload.Text textPayload = (ClickEvent.Payload.Text) payload;
    final String url = textPayload.value();
    assertEquals("http://localhost:3000/", url);
  }

  private void collectClickEvents(final Component component, final List<ClickEvent<?>> events) {
    final ClickEvent<?> event = component.clickEvent();
    if (event != null) {
      events.add(event);
    }
    final List<Component> children = component.children();
    for (final Component child : children) {
      this.collectClickEvents(child, events);
    }
  }

  @Test
  void shouldConvertTheArgumentWithTheFunction() {
    final Function<Integer, String> function = port -> "http://localhost:" + port + "/";
    final LocaleTools.UniComponent<Integer> message = LocaleTools.direct(LINK_KEY, function);
    final Component component = message.build(8080);
    final String text = Components.plain(component);
    assertEquals(LINK_PREFIX + "http://localhost:8080/", text);
  }

  @Test
  void shouldRenderMessagesWithTwoArguments() {
    final Function<String, String> upper = value -> value.toUpperCase(Locale.ROOT);
    final LocaleTools.BiComponent<String, String> message = LocaleTools.direct(LINK_KEY, upper, null);
    final Component component = message.build("http://a/", "ignored");
    final String text = Components.plain(component);
    assertEquals(LINK_PREFIX + "HTTP://A/", text);
    final LocaleTools.BiComponent<String, String> pair = LocaleTools.direct("mcav.test.two", upper, value -> "length=" + value.length());

    final String componentsText = Components.plain(assertDoesNotThrow(() -> pair.build(FIRST_ARGUMENT, SECOND_ARGUMENT)));
    assertEquals("FIRST|length=6", componentsText);
  }

  @Test
  void shouldRenderMessagesWithThreeArguments() {
    final LocaleTools.TriComponent<String, String, String> message = LocaleTools.direct(LINK_KEY, null, null, null);
    final Component component = message.build("http://b/", SECOND_ARGUMENT, "third");
    final String text = Components.plain(component);
    assertEquals(LINK_PREFIX + "http://b/", text);
    final LocaleTools.TriComponent<String, String, Integer> triple = LocaleTools.direct(
      "mcav.test.three",
      value -> value.toUpperCase(Locale.ROOT),
      value -> "[" + value + "]",
      value -> "number=" + value
    );

    final String componentsText = Components.plain(triple.build(FIRST_ARGUMENT, SECOND_ARGUMENT, 3));
    assertEquals("FIRST|[second]|number=3", componentsText);
  }

  @Test
  void shouldRenderNullArgumentsAsEmptyText() {
    final Component empty = LocaleTools.createFinalText(null, null);
    final Component converted = LocaleTools.createFinalText(null, _ -> "fallback");
    final String emptyText = Components.plain(empty);
    final String convertedText = Components.plain(converted);
    assertEquals("", emptyText);
    assertEquals("fallback", convertedText);
  }

  @Test
  void shouldRenderArgumentsWithToStringWithoutFunction() {
    final Component component = LocaleTools.createFinalText(42, null);
    final String text = Components.plain(component);
    assertEquals("42", text);
  }

  @Test
  void shouldFailForUnknownKeysWhenBuilt() {
    final LocaleTools.NullComponent message = LocaleTools.direct("mcav.unknown.key");
    assertThrows(MissingResourceException.class, message::build);
  }

  @Test
  void shouldRefuseNullKeys() {
    assertThrows(NullPointerException.class, () -> LocaleTools.direct(null));
    assertThrows(NullPointerException.class, () -> LocaleTools.direct(null, null));
    assertThrows(NullPointerException.class, () -> LocaleTools.direct(null, null, null));
    assertThrows(NullPointerException.class, () -> LocaleTools.direct(null, null, null, null));
  }

  @Test
  void shouldShareOneTranslationManager() {
    final TranslationManager manager = LocaleTools.MANAGER;
    assertNotNull(manager);
    try (final MockedConstruction<TranslationManager> managers = Mockito.mockConstruction(TranslationManager.class)) {
      final Component first = LocaleTools.direct(SCREEN_BUILD_KEY).build();
      final Component second = LocaleTools.direct(SCREEN_BUILD_KEY).build();

      final List<TranslationManager> constructed = managers.constructed();
      assertEquals(List.of(), constructed, "message builders share the initialized manager");

      final String text = Components.plain(first);
      assertEquals(SCREEN_BUILT_TEXT, text);
      assertEquals(first, second);
    }
  }
}
