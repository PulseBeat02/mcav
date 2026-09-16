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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import me.brandonli.mcav.sandbox.testing.Components;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link PluginTranslator}, {@link MiniMessageTranslator} and {@link ArgumentTag}.
 */
final class PluginTranslatorTest {

  private static final Key KEY = Key.key("mcav", "test");
  private static final String MESSAGES =
    """
    plain=Plain text
    colored=<red>Hello <arg:0>, you are <arg:1></red>
    link=Open $URL$ now
    """;

  private PluginTranslator translator;

  @BeforeEach
  void createTranslator() throws IOException {
    final StringReader reader = new StringReader(MESSAGES);
    final ResourceBundle bundle = new PropertyResourceBundle(reader);
    this.translator = new PluginTranslator(KEY, bundle);
  }

  private String render(final TranslatableComponent component) {
    final Component rendered = this.translator.translate(component, Locale.ENGLISH);
    return Components.plain(rendered);
  }

  @Test
  void isNamedByItsKey() {
    final Key name = this.translator.name();
    assertSame(KEY, name);
  }

  @Test
  void rendersMessagesWithoutArguments() {
    final TranslatableComponent component = Component.translatable("plain");
    final String text = this.render(component);
    assertEquals("Plain text", text);
  }

  @Test
  void insertsArgumentsIntoArgumentTags() {
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
  void replacesTheUrlPlaceholderWithTheFirstArgument() {
    final TextComponent url = Component.text("https://example.com/");
    final TranslatableComponent component = Component.translatable("link", url);
    final String text = this.render(component);
    assertEquals("Open https://example.com/ now", text);
  }

  @Test
  void keepsTheChildrenOfTheComponent() {
    final TranslatableComponent translatable = Component.translatable("plain");
    final TextComponent suffix = Component.text("!");
    final TranslatableComponent component = translatable.append(suffix);
    final String text = this.render(component);
    assertEquals("Plain text!", text);
  }

  @Test
  void failsForUnknownKeys() {
    final TranslatableComponent component = Component.translatable("missing");
    assertThrows(MissingResourceException.class, () -> this.translator.translate(component, Locale.ENGLISH));
  }

  @Test
  void doesNotTranslateToMessageFormats() {
    assertThrows(UnsupportedOperationException.class, () -> this.translator.translate("plain", Locale.ENGLISH));
  }

  @Test
  void resolvesOnlyTheArgumentTag() {
    final List<ComponentLike> arguments = List.of(Component.text("value"));
    final ArgumentTag tag = new ArgumentTag(arguments);
    final boolean argument = tag.has("arg");
    final boolean other = tag.has("argument");
    assertTrue(argument);
    assertFalse(other);
  }

  @Test
  void insertsTheArgumentAtTheIndexOfTheTag() {
    final List<ComponentLike> arguments = List.of(Component.text("zero"), Component.text("one"));
    final TagResolver tag = new ArgumentTag(arguments);
    final MiniMessage miniMessage = MiniMessage.miniMessage();
    final Component component = miniMessage.deserialize("<arg:1> then <arg:0>", tag);
    final String text = Components.plain(component);
    assertEquals("one then zero", text);
  }

  @Test
  void usesTheMiniMessageInstanceItWasGiven() {
    final MiniMessage.Builder builder = MiniMessage.builder();
    builder.strict(true);
    final MiniMessage strict = builder.build();
    final MiniMessageTranslator custom = new MiniMessageTranslator(strict) {
      @Override
      protected String getMiniMessageString(final String key, final Locale locale) {
        return "<green>" + key;
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
  void refusesNullArguments() {
    assertThrows(NullPointerException.class, () -> new ArgumentTag(null));
    assertThrows(NullPointerException.class, () -> new PluginTranslator(KEY, null));
  }
}
