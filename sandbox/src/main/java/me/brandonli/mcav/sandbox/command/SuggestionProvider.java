/*
 * This file is part of mcav, a media playback library for Minecraft
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

import com.mojang.brigadier.context.CommandContext;
import java.util.stream.Stream;
import me.brandonli.mcav.sandbox.MCAV;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.annotations.suggestion.Suggestions;

public final class SuggestionProvider implements AnnotationCommandFeature {

  @Suggestions("id")
  public Stream<Integer> suggestId(final CommandContext<CommandSender> ctx, final String input) {
    return Stream.of(0, 5, 10, 100, 1000, 10000, 100000);
  }

  @Suggestions("dimensions")
  public Stream<String> suggestDimensions(final CommandContext<CommandSender> ctx, final String input) {
    return Stream.of("4x4", "5x5", "16x9", "32x18");
  }

  @Suggestions("resolutions")
  public Stream<String> suggestResolutions(final CommandContext<CommandSender> ctx, final String input) {
    return Stream.of("512x512", "640x640", "1280x720", "1920x1080");
  }

  @Override
  public void registerFeature(final MCAV plugin, final AnnotationParser<CommandSender> parser) {
    // do nothing
  }
}
