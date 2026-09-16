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
package me.brandonli.mcav.sandbox.locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.MissingResourceException;
import java.util.function.Function;
import me.brandonli.mcav.sandbox.testing.Components;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link LocaleTools}.
 */
final class LocaleToolsTest {

  private static final String LINK_KEY = "mcav.command.audio.http";
  private static final String LINK_PREFIX = "Click on the URL to listen onto the website! ";

  private static void collectClickEvents(final Component component, final List<ClickEvent<?>> events) {
    final ClickEvent<?> event = component.clickEvent();
    if (event != null) {
      events.add(event);
    }
    final List<Component> children = component.children();
    for (final Component child : children) {
      collectClickEvents(child, events);
    }
  }

  private static TextColor findColor(final Component component) {
    final TextColor color = component.color();
    if (color != null) {
      return color;
    }
    final List<Component> children = component.children();
    final Component first = children.getFirst();
    return findColor(first);
  }

  @Test
  void rendersAMessageWithoutArguments() {
    final LocaleTools.NullComponent message = LocaleTools.direct("mcav.command.screen.build");
    final Component component = message.build();
    final String text = Components.plain(component);
    final TextColor color = findColor(component);
    assertEquals("Built a new map screen!", text);
    assertEquals(NamedTextColor.GOLD, color);
  }

  @Test
  void insertsTheArgumentIntoTheUrlPlaceholders() {
    final LocaleTools.UniComponent<String> message = LocaleTools.direct(LINK_KEY, null);
    final Component component = message.build("http://localhost:3000/");
    final String text = Components.plain(component);
    final List<ClickEvent<?>> events = new ArrayList<>();
    collectClickEvents(component, events);
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

  @Test
  void convertsTheArgumentWithTheFunction() {
    final Function<Integer, String> function = port -> "http://localhost:" + port + "/";
    final LocaleTools.UniComponent<Integer> message = LocaleTools.direct(LINK_KEY, function);
    final Component component = message.build(8080);
    final String text = Components.plain(component);
    assertEquals(LINK_PREFIX + "http://localhost:8080/", text);
  }

  @Test
  void rendersMessagesWithTwoArguments() {
    final Function<String, String> upper = String::toUpperCase;
    final LocaleTools.BiComponent<String, String> message = LocaleTools.direct(LINK_KEY, upper, null);
    final Component component = message.build("http://a/", "ignored");
    final String text = Components.plain(component);
    assertEquals(LINK_PREFIX + "HTTP://A/", text);
  }

  @Test
  void rendersMessagesWithThreeArguments() {
    final LocaleTools.TriComponent<String, String, String> message = LocaleTools.direct(LINK_KEY, null, null, null);
    final Component component = message.build("http://b/", "second", "third");
    final String text = Components.plain(component);
    assertEquals(LINK_PREFIX + "http://b/", text);
  }

  @Test
  void rendersNullArgumentsAsEmptyText() {
    final Component empty = LocaleTools.createFinalText(null, null);
    final Component converted = LocaleTools.createFinalText(null, _ -> "fallback");
    final String emptyText = Components.plain(empty);
    final String convertedText = Components.plain(converted);
    assertEquals("", emptyText);
    assertEquals("fallback", convertedText);
  }

  @Test
  void rendersArgumentsWithToStringWithoutFunction() {
    final Component component = LocaleTools.createFinalText(42, null);
    final String text = Components.plain(component);
    assertEquals("42", text);
  }

  @Test
  void failsForUnknownKeysWhenBuilt() {
    final LocaleTools.NullComponent message = LocaleTools.direct("mcav.unknown.key");
    assertThrows(MissingResourceException.class, message::build);
  }

  @Test
  void refusesNullKeys() {
    assertThrows(NullPointerException.class, () -> LocaleTools.direct(null));
    assertThrows(NullPointerException.class, () -> LocaleTools.direct(null, null));
    assertThrows(NullPointerException.class, () -> LocaleTools.direct(null, null, null));
    assertThrows(NullPointerException.class, () -> LocaleTools.direct(null, null, null, null));
  }

  @Test
  void sharesOneTranslationManager() {
    final TranslationManager manager = LocaleTools.MANAGER;
    assertNotNull(manager);
  }
}
