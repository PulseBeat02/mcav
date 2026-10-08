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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.renderer.ComponentRenderer;

/** Keeps URL data out of the MiniMessage parser, including quoted click arguments. */
final class UrlPlaceholder implements ComponentRenderer<String> {

  // A fresh opaque marker distinguishes placeholders in the template from literal $URL$ in argument components.
  private final String marker;

  UrlPlaceholder() {
    final UUID identifier = UUID.randomUUID();
    this.marker = "mcav_url_" + identifier;
  }

  String prepare(final String message) {
    return message.replace("$URL$", this.marker);
  }

  @Override
  public Component render(final Component component, final String url) {
    Component rendered = this.replaceContent(component, url);
    final List<Component> originalChildren = component.children();
    final List<Component> children = new ArrayList<>(originalChildren.size());
    for (final Component child : originalChildren) {
      final Component replacement = this.render(child, url);
      children.add(replacement);
    }
    rendered = rendered.children(children);

    final ClickEvent<?> click = component.clickEvent();
    if (click != null && ClickEvent.Action.OPEN_URL.equals(click.action())) {
      final ClickEvent.Payload.Text payload = (ClickEvent.Payload.Text) click.payload();
      final String originalTarget = payload.value();
      final String target = originalTarget.replace(this.marker, url);
      try {
        final ClickEvent<?> replacement = ClickEvent.openUrl(target);
        rendered = rendered.clickEvent(replacement);
      } catch (final IllegalArgumentException invalidUri) {
        // Adventure validates URI syntax. Preserve the displayed data without offering a malformed click target.
        rendered = rendered.clickEvent(null);
      }
    }
    final HoverEvent<?> hover = component.hoverEvent();
    if (hover != null) {
      final HoverEvent<?> replacement = hover.withRenderedValue(this, url);
      rendered = rendered.hoverEvent(replacement);
    }
    return rendered;
  }

  private Component replaceContent(final Component component, final String url) {
    if (component instanceof TextComponent text) {
      final String original = text.content();
      final String content = original.replace(this.marker, url);
      return text.content(content);
    }
    if (component instanceof TranslatableComponent translatable) {
      final List<TranslationArgument> originalArguments = translatable.arguments();
      final List<TranslationArgument> arguments = new ArrayList<>(originalArguments.size());
      for (final TranslationArgument argument : originalArguments) {
        final Object value = argument.value();
        if (value instanceof Component nested) {
          final Component replacement = this.render(nested, url);
          final TranslationArgument rendered = TranslationArgument.component(replacement);
          arguments.add(rendered);
        } else {
          arguments.add(argument);
        }
      }
      return translatable.arguments(arguments);
    }
    return component;
  }
}
