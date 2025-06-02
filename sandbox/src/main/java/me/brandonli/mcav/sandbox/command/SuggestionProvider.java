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

import java.util.stream.Stream;
import me.brandonli.mcav.bukkit.media.result.Characters;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.annotations.suggestion.Suggestions;

public final class SuggestionProvider implements AnnotationCommandFeature {

  @Suggestions("ids")
  public Stream<String> suggestId() {
    return Stream.of(0, 5, 10, 100, 1000, 10000, 100000).map(String::valueOf);
  }

  @Suggestions("dimensions")
  public Stream<String> suggestDimensions() {
    return Stream.of("4x4", "5x5", "16x9", "16x16", "32x18");
  }

  @Suggestions("resolutions")
  public Stream<String> suggestResolutions() {
    return Stream.of("512x512", "640x640", "1280x720", "1280x1280", "1920x1080");
  }

  @Suggestions("chat-resolutions")
  public Stream<String> suggestChatResolutions() {
    return Stream.of("8x8", "16x16", "32x32");
  }

  @Suggestions("chat-characters")
  public Stream<String> suggestChatCharacters() {
    return Stream.of(Characters.FULL_CHARACTER, Characters.BLACK_CIRCLE, Characters.SMALL_BLACK_SQUARE);
  }

  @Override
  public void registerFeature(final MCAVSandbox plugin, final AnnotationParser<CommandSender> parser) {
    // do nothing
  }
}
