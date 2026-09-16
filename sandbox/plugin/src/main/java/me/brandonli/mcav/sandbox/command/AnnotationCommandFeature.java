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
package me.brandonli.mcav.sandbox.command;

import com.google.common.base.Preconditions;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.annotations.AnnotationParser;

/**
 * A group of commands declared with Cloud annotations. Features receive everything they need through their
 * constructor; {@link #registerFeature(AnnotationParser)} is called once before the annotations are parsed and
 * {@link #shutdown()} once when the plugin is disabled.
 */
public interface AnnotationCommandFeature {
  /**
   * Performs setup that needs the fully constructed feature, such as registering listeners.
   *
   * @param parser the parser the feature's annotations are about to be parsed with
   */
  default void registerFeature(final AnnotationParser<CommandSender> parser) {
    Preconditions.checkNotNull(parser, "Parser must not be null");
    // most features need no further setup
  }

  /**
   * Releases everything the feature holds.
   */
  default void shutdown() {
    // most features hold nothing
  }
}
