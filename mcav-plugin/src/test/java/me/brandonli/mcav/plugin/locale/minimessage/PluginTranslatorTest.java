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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicReference;
import me.brandonli.mcav.plugin.testing.Components;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class PluginTranslatorTest {

  private static final Key KEY = Key.key("mcav", "test");
  private static final String MESSAGES = """
  plain=Plain text
  colored=<red>Hello <arg:0>, you are <arg:1></red>
  link=Open $URL$ now
  rich=Hello <red>world</red>
  """;

  private static final String SUFFIX_TEXT = "!";

  private static final String PLAIN_KEY = "plain";

  private PluginTranslator translator;

  @BeforeEach
  void createTranslator() throws IOException {
    final StringReader reader = new StringReader(MESSAGES);
    final ResourceBundle bundle = new PropertyResourceBundle(reader);
    this.translator = new PluginTranslator(KEY, bundle);
  }

  @Test
  void shouldBeNamedByItsKey() {
    final Key name = this.translator.name();
    assertSame(KEY, name);
  }

  @Test
  void shouldRenderMessagesWithoutArguments() {
    final TranslatableComponent component = Component.translatable(PLAIN_KEY);
    final String text = this.render(component);
    assertEquals("Plain text", text);
  }

  private String render(final TranslatableComponent component) {
    final Component rendered = this.translator.translate(component, Locale.ENGLISH);
    assertNotNull(rendered);

    return Components.plain(rendered);
  }

  @Test
  void shouldInsertArgumentsIntoArgumentTags() {
    final TextComponent name = Component.text("Steve");
    final TextComponent role = Component.text("admin");
    final TranslatableComponent component = Component.translatable("colored", name, role);
    final Component rendered = this.translator.translate(component, Locale.ENGLISH);
    assertNotNull(rendered);

    final String text = Components.plain(rendered);
    final TextColor color = rendered.color();
    assertEquals("Hello Steve, you are admin", text);
    assertEquals(NamedTextColor.RED, color);
  }

  @Test
  void shouldReplaceTheUrlPlaceholderWithTheFirstArgument() {
    final TextComponent url = Component.text("https://example.com/");
    final TranslatableComponent component = Component.translatable("link", url);
    final String text = this.render(component);
    assertEquals("Open https://example.com/ now", text);
  }

  @Test
  void shouldKeepTheChildrenOfTheComponent() {
    final TranslatableComponent translatable = Component.translatable(PLAIN_KEY);
    final TextComponent suffix = Component.text(SUFFIX_TEXT);
    final TranslatableComponent component = translatable.append(suffix);
    final String text = this.render(component);
    assertEquals("Plain text!", text);
  }

  @Test
  void shouldKeepParsedChildrenOriginalSuffixAndFallbackStyle() {
    final TranslatableComponent base = Component.translatable("rich");
    final TranslatableComponent blue = base.color(NamedTextColor.BLUE);
    final TranslatableComponent bold = blue.decorate(TextDecoration.BOLD);
    final TextComponent suffix = Component.text(SUFFIX_TEXT);
    final TranslatableComponent component = bold.append(suffix);
    final Component rendered = this.translator.translate(component, Locale.ENGLISH);
    assertNotNull(rendered);

    final TextComponent expectedStart = Component.text("Hello ", NamedTextColor.BLUE);
    final TextComponent expectedStyled = expectedStart.decorate(TextDecoration.BOLD);
    final TextComponent redWord = Component.text("world", NamedTextColor.RED);
    final Component expected = expectedStyled.append(redWord, suffix);
    final String text = Components.plain(rendered);
    assertEquals("Hello world!", text);
    assertEquals(expected, rendered, "translation styling overrides fallback while original children remain appended");
  }

  @Test
  void shouldDeclineUnknownKeys() {
    final TranslatableComponent component = Component.translatable("missing");
    final Component rendered = this.translator.translate(component, Locale.ENGLISH);
    assertNull(rendered);
  }

  @Test
  void shouldNotTranslateToMessageFormats() {
    final MessageFormat rendered = this.translator.translate(PLAIN_KEY, Locale.ENGLISH);
    assertNull(rendered);
  }

  @Test
  void shouldResolveOnlyTheArgumentTag() {
    final List<ComponentLike> arguments = List.of(Component.text("value"));
    final ArgumentTag tag = new ArgumentTag(arguments);
    final boolean hasShortName = tag.has("arg");
    final boolean hasArgument = tag.has("argument");
    assertTrue(hasShortName);
    assertFalse(hasArgument);
  }

  @Test
  void shouldInsertTheArgumentAtTheIndexOfTheTag() {
    final List<ComponentLike> arguments = List.of(Component.text("zero"), Component.text("one"));
    final TagResolver tag = new ArgumentTag(arguments);
    final MiniMessage miniMessage = MiniMessage.miniMessage();
    final Component component = miniMessage.deserialize("<arg:1> then <arg:0>", tag);
    final String text = Components.plain(component);
    assertEquals("one then zero", text);
  }

  @Test
  void shouldRefuseAnIndexThatIsNotAWholeNumber() {
    final List<ComponentLike> arguments = List.of(Component.text("value"));
    final ArgumentTag tag = new ArgumentTag(arguments);
    final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    final TagResolver resolver = TagResolver.resolver(
      "arg",
      (queue, context) -> this.resolveAndRecordException(tag, queue, context, failure)
    );
    final MiniMessage miniMessage = MiniMessage.miniMessage();
    miniMessage.deserialize("<arg:first>", resolver);

    final RuntimeException refused = failure.get();
    assertInstanceOf(NoSuchElementException.class, refused);
  }

  private Tag resolveAndRecordException(
    final ArgumentTag tag,
    final ArgumentQueue queue,
    final Context context,
    final AtomicReference<RuntimeException> failure
  ) {
    try {
      return tag.resolve("arg", queue, context);
    } catch (final RuntimeException exception) {
      failure.set(exception);

      throw exception;
    }
  }

  @Test
  void shouldUseTheMiniMessageInstanceItWasGiven() {
    final MiniMessage.Builder builder = MiniMessage.builder();
    builder.strict(true);
    final MiniMessage strict = builder.build();
    final MiniMessageTranslator custom = new MiniMessageTranslator(strict) {
      @Override
      protected Optional<String> getMiniMessageString(final String key, final Locale locale) {
        final String message = "<green>%s".formatted(key);
        return Optional.of(message);
      }

      @Override
      public @NonNull Key name() {
        return KEY;
      }
    };
    final TranslatableComponent component = Component.translatable("unclosed");
    assertThrows(RuntimeException.class, () -> custom.translate(component, Locale.ENGLISH));
  }

  @Test
  void shouldRefuseNullArguments() throws IOException {
    final StringReader reader = new StringReader(MESSAGES);
    final ResourceBundle bundle = new PropertyResourceBundle(reader);
    final Key noKey = null;
    assertThrows(NullPointerException.class, () -> new ArgumentTag(null));
    assertThrows(NullPointerException.class, () -> new PluginTranslator(KEY, null));
    assertThrows(NullPointerException.class, () -> new PluginTranslator(noKey, bundle));
  }

  @Test
  void shouldRefuseANullMiniMessage() {
    final MiniMessage noMiniMessage = null;
    final NullPointerException refused = assertThrows(NullPointerException.class, () -> new FixedTranslator(noMiniMessage));

    final String message = refused.getMessage();
    assertEquals("MiniMessage must not be null!", message);
  }

  @Test
  void shouldRefuseToTranslateANullComponent() {
    final TranslatableComponent noComponent = null;
    final NullPointerException refused = assertThrows(NullPointerException.class, () -> this.translator.translate(noComponent, Locale.ROOT));

    final String message = refused.getMessage();
    assertEquals("Component must not be null!", message);
  }

  @Test
  void shouldRefuseToTranslateForANullLocale() {
    final TranslatableComponent component = Component.translatable(PLAIN_KEY);
    final Locale noLocale = null;
    final NullPointerException refused = assertThrows(NullPointerException.class, () -> this.translator.translate(component, noLocale));

    final String message = refused.getMessage();
    assertEquals("Locale must not be null!", message);
  }

  @Test
  void shouldRefuseANullArgumentQueue() {
    final ArgumentTag tag = new ArgumentTag(List.of());
    final ArgumentQueue noArguments = null;
    final Context noContext = null;
    final NullPointerException refused = assertThrows(NullPointerException.class, () -> tag.resolve("arg", noArguments, noContext));

    final String message = refused.getMessage();
    assertEquals("Arguments must not be null!", message);
  }

  private static final class FixedTranslator extends MiniMessageTranslator {

    FixedTranslator(final MiniMessage miniMessage) {
      super(miniMessage);
    }

    @Override
    protected Optional<String> getMiniMessageString(final String key, final Locale locale) {
      return Optional.empty();
    }

    @Override
    public @NonNull Key name() {
      return KEY;
    }
  }
}
