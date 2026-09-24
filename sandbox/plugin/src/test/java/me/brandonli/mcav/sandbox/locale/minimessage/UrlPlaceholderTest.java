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
package me.brandonli.mcav.sandbox.locale.minimessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;
import me.brandonli.mcav.sandbox.testing.Components;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentIteratorFlag;
import net.kyori.adventure.text.ComponentIteratorType;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class UrlPlaceholderTest {

  private static final String INJECTION =
    "https://example.test/'><click:run_command:'/say injected'>owned</click><click:open_url:'https://example.test";

  @ParameterizedTest
  @ValueSource(strings = { "https://example.test/normal", "https://example.test/a'b", "https://example.test/a%5Cb?x=$URL$" })
  void legacyQuotedClickTargetsAndVisibleUrlsRemainLiteral(final String url) {
    final MiniMessage parser = MiniMessage.miniMessage();
    final MiniMessageTranslator translator = translator("<click:open_url:'$URL$'>$URL$</click>", parser);
    final Component argument = Component.text(url);
    final TranslatableComponent input = Component.translatable("message", argument);
    final Component actual = translator.translate(input, Locale.ENGLISH);
    assertNotNull(actual);
    final ClickEvent<?> click = ClickEvent.openUrl(url);
    final Component text = Component.text(url);
    final Component expected = text.clickEvent(click);
    assertEquals(expected, actual, "URL text and the click payload must round-trip without MiniMessage interpretation");
    assertNoRunCommands(actual);
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "https://example.test/a'b\\c", "https://example.test/?q=<red>paint</red>", "https://example.test/\"quoted\"?x=$URL$", INJECTION,
    }
  )
  void invalidClickTargetsKeepLiteralTextWithoutAnEvent(final String url) {
    final MiniMessage parser = MiniMessage.miniMessage();
    final MiniMessageTranslator translator = translator("<click:open_url:'$URL$'>$URL$</click>", parser);
    final Component argument = Component.text(url);
    final TranslatableComponent input = Component.translatable("message", argument);
    final Component actual = translator.translate(input, Locale.ENGLISH);
    assertNotNull(actual);
    final Component expected = Component.text(url);
    assertEquals(expected, actual);
    final ClickEvent<?> click = actual.clickEvent();
    assertNull(click);
    assertNoRunCommands(actual);
  }

  @Test
  void preservesUrlTextHoverTextAndTheOriginalArgumentAndSuffix() {
    final MiniMessage parser = MiniMessage.miniMessage();
    final MiniMessageTranslator translator = translator("<red>$URL$</red> <arg:1></arg> <hover:show_text:'$URL$'>hover</hover>", parser);
    final Component argument = Component.text(INJECTION);
    final Component second = Component.text("literal $URL$", NamedTextColor.GOLD);
    final Component suffix = Component.text(" suffix $URL$", NamedTextColor.BLUE);
    final TranslatableComponent base = Component.translatable("message", argument, second);
    final TranslatableComponent input = base.append(suffix);
    final Component actual = translator.translate(input, Locale.ENGLISH);
    assertNotNull(actual);
    final String plain = Components.plain(actual);
    assertEquals(INJECTION + " literal $URL$ hover suffix $URL$", plain);
    final Component red = Component.text(INJECTION, NamedTextColor.RED);
    final Component space = Component.text(" ");
    final Component hoverText = Component.text(INJECTION);
    final HoverEvent<Component> hoverEvent = HoverEvent.showText(hoverText);
    final Component hoverLabel = Component.text("hover");
    final Component hovering = hoverLabel.hoverEvent(hoverEvent);
    final Component empty = Component.empty();
    final Component expected = empty.append(red, space, second, space, hovering, suffix);
    assertEquals(expected, actual);
    assertNoRunCommands(actual);
  }

  @Test
  void preservesRepeatedPlaceholdersWithinExistingClickTargets() {
    final String url = "https://example.test/a'b";
    final MiniMessage parser = MiniMessage.miniMessage();
    final MiniMessageTranslator translator = translator("<click:open_url:'https://redirect.test/?to=$URL$&again=$URL$'>go</click>", parser);
    final Component argument = Component.text(url);
    final TranslatableComponent input = Component.translatable("message", argument);
    final Component actual = translator.translate(input, Locale.ENGLISH);
    assertNotNull(actual);
    final String target = "https://redirect.test/?to=" + url + "&again=" + url;
    final ClickEvent<?> click = ClickEvent.openUrl(target);
    final Component label = Component.text("go");
    final Component expected = label.clickEvent(click);
    assertEquals(expected, actual, "the parser must not prepend a scheme or normalize the opaque marker");
  }

  @Test
  void doesNotEnableClickTagsExcludedByTheSuppliedParser() {
    final TagResolver colors = StandardTags.color();
    final MiniMessage.Builder builder = MiniMessage.builder();
    builder.tags(colors);
    final MiniMessage parser = builder.build();
    final MiniMessageTranslator translator = translator("<red><click:open_url:'$URL$'>$URL$</click></red>", parser);
    final Component argument = Component.text(INJECTION);
    final TranslatableComponent input = Component.translatable("message", argument);
    final Component actual = translator.translate(input, Locale.ENGLISH);
    assertNotNull(actual);
    final Component expected = Component.text("<click:open_url:'" + INJECTION + "'>" + INJECTION + "</click>", NamedTextColor.RED);
    assertEquals(expected, actual);
    assertNoRunCommands(actual);
  }

  @Test
  void retainsCustomClickResolverSemantics() {
    final TagResolver customClick = TagResolver.resolver("click", (arguments, context) -> Tag.styling(NamedTextColor.GOLD));
    final MiniMessage.Builder builder = MiniMessage.builder();
    builder.tags(customClick);
    final MiniMessage parser = builder.build();
    final MiniMessageTranslator translator = translator("<click:open_url:'$URL$'>$URL$</click>", parser);
    final Component argument = Component.text(INJECTION);
    final TranslatableComponent input = Component.translatable("message", argument);
    final Component actual = translator.translate(input, Locale.ENGLISH);
    assertNotNull(actual);
    final Component expected = Component.text(INJECTION, NamedTextColor.GOLD);
    assertEquals(expected, actual);
    assertNoRunCommands(actual);
  }

  @Test
  void restoresUrlsInsideHoverEntityNamesAndTranslationArguments() {
    final String url = "https://example.test/a'b";
    final UrlPlaceholder placeholder = new UrlPlaceholder();
    final String marker = placeholder.prepare("$URL$");
    final Component text = Component.text(marker);
    final ClickEvent<?> click = ClickEvent.openUrl(marker);
    final Component linked = text.clickEvent(click);
    final Key entityType = Key.key("minecraft", "pig");
    final UUID identifier = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    final HoverEvent<HoverEvent.ShowEntity> hover = HoverEvent.showEntity(entityType, identifier, linked);
    final TranslatableComponent original = Component.translatable("custom", linked);
    final TranslationArgument number = TranslationArgument.numeric(42);
    final TranslationArgument linkedArgument = TranslationArgument.component(linked);
    final List<TranslationArgument> arguments = List.of(number, linkedArgument);
    final TranslatableComponent withArguments = original.arguments(arguments);
    final Component input = withArguments.hoverEvent(hover);
    final Component actual = placeholder.render(input, url);
    final Component expectedText = Component.text(url);
    final ClickEvent<?> expectedClick = ClickEvent.openUrl(url);
    final Component expectedLinked = expectedText.clickEvent(expectedClick);
    final HoverEvent<HoverEvent.ShowEntity> expectedHover = HoverEvent.showEntity(entityType, identifier, expectedLinked);
    final TranslationArgument expectedLinkedArgument = TranslationArgument.component(expectedLinked);
    final List<TranslationArgument> expectedArguments = List.of(number, expectedLinkedArgument);
    final TranslatableComponent expectedBase = original.arguments(expectedArguments);
    final Component expected = expectedBase.hoverEvent(expectedHover);
    assertEquals(expected, actual);
    assertNoRunCommands(actual);
  }

  @Test
  void leavesNonUrlEventsIntactAndSupportsNonTextComponents() {
    final UrlPlaceholder placeholder = new UrlPlaceholder();
    final ClickEvent<?> click = ClickEvent.suggestCommand("/say ready");
    final Component score = Component.score("reviewer", "points");
    final Component original = score.clickEvent(click);
    final Component actual = placeholder.render(original, INJECTION);
    assertEquals(original, actual);
  }

  @Test
  void missingUrlArgumentsStillFailClearly() {
    final MiniMessage parser = MiniMessage.miniMessage();
    final MiniMessageTranslator translator = translator("$URL$", parser);
    final TranslatableComponent input = Component.translatable("message");
    assertThrows(NoSuchElementException.class, () -> translator.translate(input, Locale.ENGLISH));
  }

  private static MiniMessageTranslator translator(final String template, final MiniMessage parser) {
    return new MiniMessageTranslator(parser) {
      @Override
      protected String getMiniMessageString(final String key, final Locale locale) {
        return template;
      }

      @Override
      public @NonNull Key name() {
        return Key.key("mcav", "url-test");
      }
    };
  }

  private static void assertNoRunCommands(final Component component) {
    final EnumSet<ComponentIteratorFlag> flags = EnumSet.allOf(ComponentIteratorFlag.class);
    final Iterable<Component> descendants = component.iterable(ComponentIteratorType.DEPTH_FIRST, flags);
    for (final Component descendant : descendants) {
      final ClickEvent<?> click = descendant.clickEvent();
      if (click != null) {
        assertNotEquals(
          ClickEvent.Action.RUN_COMMAND,
          click.action(),
          "URL data must not inject commands in text, hover or argument components"
        );
      }
    }
  }
}
