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
package me.brandonli.mcav.plugin.locale.minimessage;

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
import me.brandonli.mcav.plugin.testing.Components;
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

  private static final String URL_PLACEHOLDER = "$URL$";
  private static final String CLICKABLE_URL_TEMPLATE = "<click:open_url:'$URL$'>$URL$</click>";

  private static final String QUOTED_URL = "https://example.test/a'b";

  private static final String MESSAGE_KEY = "message";

  @ParameterizedTest
  @ValueSource(strings = { "https://example.test/normal", "https://example.test/a'b", "https://example.test/a%5Cb?x=$URL$" })
  void shouldKeepLegacyQuotedClickTargetsAndVisibleUrlsLiteral(final String url) {
    final MiniMessage parser = MiniMessage.miniMessage();
    final MiniMessageTranslator translator = this.translator(CLICKABLE_URL_TEMPLATE, parser);
    final Component argument = Component.text(url);
    final TranslatableComponent input = Component.translatable(MESSAGE_KEY, argument);
    final Component actual = translator.translate(input, Locale.ENGLISH);
    assertNotNull(actual);

    final ClickEvent<?> click = ClickEvent.openUrl(url);
    final Component text = Component.text(url);
    final Component expected = text.clickEvent(click);
    assertEquals(expected, actual, "URL text and the click payload must round-trip without MiniMessage interpretation");
    this.assertNoRunCommands(actual);
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "https://example.test/a'b\\c",
      "https://example.test/?q=<red>paint</red>",
      "https://example.test/\"quoted\"?x=$URL$",
      INJECTION,
    }
  )
  void shouldKeepTheLiteralTextOfAnInvalidClickTargetWithoutAnEvent(final String url) {
    final MiniMessage parser = MiniMessage.miniMessage();
    final MiniMessageTranslator translator = this.translator(CLICKABLE_URL_TEMPLATE, parser);
    final Component argument = Component.text(url);
    final TranslatableComponent input = Component.translatable(MESSAGE_KEY, argument);
    final Component actual = translator.translate(input, Locale.ENGLISH);
    assertNotNull(actual);

    final Component expected = Component.text(url);
    assertEquals(expected, actual);

    final ClickEvent<?> click = actual.clickEvent();
    assertNull(click);
    this.assertNoRunCommands(actual);
  }

  @Test
  void shouldPreserveUrlTextHoverTextAndTheOriginalArgumentAndSuffix() {
    final MiniMessage parser = MiniMessage.miniMessage();
    final MiniMessageTranslator translator = this.translator(
      "<red>$URL$</red> <arg:1></arg> <hover:show_text:'$URL$'>hover</hover>",
      parser
    );
    final Component argument = Component.text(INJECTION);
    final Component second = Component.text("literal $URL$", NamedTextColor.GOLD);
    final Component suffix = Component.text(" suffix $URL$", NamedTextColor.BLUE);
    final TranslatableComponent base = Component.translatable(MESSAGE_KEY, argument, second);
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
    this.assertNoRunCommands(actual);
  }

  @Test
  void shouldPreserveRepeatedPlaceholdersWithinExistingClickTargets() {
    final String url = QUOTED_URL;
    final MiniMessage parser = MiniMessage.miniMessage();
    final MiniMessageTranslator translator = this.translator(
      "<click:open_url:'https://redirect.test/?to=$URL$&again=$URL$'>go</click>",
      parser
    );
    final Component argument = Component.text(url);
    final TranslatableComponent input = Component.translatable(MESSAGE_KEY, argument);
    final Component actual = translator.translate(input, Locale.ENGLISH);
    assertNotNull(actual);

    final String target = "https://redirect.test/?to=" + url + "&again=" + url;
    final ClickEvent<?> click = ClickEvent.openUrl(target);
    final Component label = Component.text("go");
    final Component expected = label.clickEvent(click);
    assertEquals(expected, actual, "the parser must not prepend a scheme or normalize the opaque marker");
  }

  @Test
  void shouldNotEnableClickTagsExcludedByTheSuppliedParser() {
    final TagResolver colors = StandardTags.color();
    final MiniMessage.Builder builder = MiniMessage.builder();
    builder.tags(colors);
    final MiniMessage parser = builder.build();
    final MiniMessageTranslator translator = this.translator("<red><click:open_url:'$URL$'>$URL$</click></red>", parser);
    final Component argument = Component.text(INJECTION);
    final TranslatableComponent input = Component.translatable(MESSAGE_KEY, argument);
    final Component actual = translator.translate(input, Locale.ENGLISH);
    assertNotNull(actual);

    final Component expected = Component.text("<click:open_url:'" + INJECTION + "'>" + INJECTION + "</click>", NamedTextColor.RED);
    assertEquals(expected, actual);
    this.assertNoRunCommands(actual);
  }

  @Test
  void shouldRetainCustomClickResolverSemantics() {
    final TagResolver customClick = TagResolver.resolver("click", (arguments, context) -> Tag.styling(NamedTextColor.GOLD));
    final MiniMessage.Builder builder = MiniMessage.builder();
    builder.tags(customClick);
    final MiniMessage parser = builder.build();
    final MiniMessageTranslator translator = this.translator(CLICKABLE_URL_TEMPLATE, parser);
    final Component argument = Component.text(INJECTION);
    final TranslatableComponent input = Component.translatable(MESSAGE_KEY, argument);
    final Component actual = translator.translate(input, Locale.ENGLISH);
    assertNotNull(actual);

    final Component expected = Component.text(INJECTION, NamedTextColor.GOLD);
    assertEquals(expected, actual);
    this.assertNoRunCommands(actual);
  }

  @Test
  void shouldRestoreUrlsInsideHoverEntityNamesAndTranslationArguments() {
    final String url = QUOTED_URL;
    final UrlPlaceholder placeholder = new UrlPlaceholder();
    final String marker = placeholder.prepare(URL_PLACEHOLDER);
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
    this.assertNoRunCommands(actual);
  }

  @Test
  void shouldLeaveNonUrlEventsIntactAndSupportsNonTextComponents() {
    final UrlPlaceholder placeholder = new UrlPlaceholder();
    final ClickEvent<?> click = ClickEvent.suggestCommand("/say ready");
    final Component score = Component.score("reviewer", "points");
    final Component original = score.clickEvent(click);
    final Component actual = placeholder.render(original, INJECTION);
    assertEquals(original, actual);
  }

  @Test
  void shouldStillFailClearlyOnMissingUrlArguments() {
    final MiniMessage parser = MiniMessage.miniMessage();
    final MiniMessageTranslator translator = this.translator(URL_PLACEHOLDER, parser);
    final TranslatableComponent input = Component.translatable(MESSAGE_KEY);
    assertThrows(NoSuchElementException.class, () -> translator.translate(input, Locale.ENGLISH));
  }

  private MiniMessageTranslator translator(final String template, final MiniMessage parser) {
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

  private void assertNoRunCommands(final Component component) {
    final EnumSet<ComponentIteratorFlag> flags = EnumSet.allOf(ComponentIteratorFlag.class);
    final Iterable<Component> descendants = component.iterable(ComponentIteratorType.DEPTH_FIRST, flags);
    for (final Component descendant : descendants) {
      final ClickEvent<?> click = descendant.clickEvent();
      if (click != null) {
        final ClickEvent.Action<? extends ClickEvent.Payload> action = click.action();
        assertNotEquals(ClickEvent.Action.RUN_COMMAND, action, "URL data must not inject commands in text, hover or argument components");
      }
    }
  }
}
