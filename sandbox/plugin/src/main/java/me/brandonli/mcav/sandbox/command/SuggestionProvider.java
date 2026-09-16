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

import java.util.stream.Stream;
import me.brandonli.mcav.bukkit.media.result.Characters;
import org.incendo.cloud.annotations.suggestion.Suggestions;

/**
 * Tab completions shared by the commands, referenced by name in their {@code @Argument(suggestions = ...)}.
 */
public final class SuggestionProvider implements AnnotationCommandFeature {

  /**
   * Constructs the provider.
   */
  public SuggestionProvider() {
    // stateless
  }

  /**
   * Suggests starting map ids for the {@code <mapId>} argument of {@code /mcav screen} and the map commands. Any id
   * from 0 up can be typed; pick one whose following ids are not used by other maps on the server.
   *
   * @return the suggested ids
   */
  @Suggestions("ids")
  public Stream<String> suggestId() {
    return Stream.of("0", "5", "10", "100", "1000", "10000", "100000");
  }

  /**
   * Suggests sizes for arguments counted in blocks, maps, or characters, such as {@code <blockDimensions>} of the
   * map commands or {@code <imageResolution>} of the block, chat, entity, and scoreboard commands.
   *
   * @return the suggested sizes, written as {@code <width>x<height>}
   */
  @Suggestions("dimensions")
  public Stream<String> suggestDimensions() {
    return Stream.of("4x4", "5x5", "16x9", "16x16", "32x18");
  }

  /**
   * Suggests pixel resolutions for the map commands and the browser and virtual machine screens, such as
   * {@code 640x640}, which fills a 5x5 wall of 128x128 pixel maps exactly.
   *
   * @return the suggested resolutions, written as {@code <width>x<height>}
   */
  @Suggestions("resolutions")
  public Stream<String> suggestResolutions() {
    return Stream.of("512x512", "640x640", "1280x720", "1280x1280", "1920x1080");
  }

  /**
   * Suggests small sizes that fit into the chat window, in characters per line and lines.
   *
   * @return the suggested sizes, written as {@code <width>x<height>}
   */
  @Suggestions("chat-resolutions")
  public Stream<String> suggestChatResolutions() {
    return Stream.of("8x8", "16x16", "32x32");
  }

  /**
   * Suggests the characters pixels are drawn with in chat, entity, and scoreboard displays: a full block, which
   * gives the most solid picture, a black circle, and a small black square.
   *
   * @return the suggested characters
   */
  @Suggestions("chat-characters")
  public Stream<String> suggestChatCharacters() {
    return Stream.of(Characters.FULL_CHARACTER, Characters.BLACK_CIRCLE, Characters.SMALL_BLACK_SQUARE);
  }

  /**
   * Suggests JPEG qualities for the {@code <quality>} argument of {@code /mcav browser create}, from 10 to 100.
   *
   * @return the suggested qualities
   */
  @Suggestions("quality")
  public Stream<String> suggestQuality() {
    return Stream.of("10", "20", "30", "40", "50", "60", "70", "80", "90", "100");
  }

  /**
   * Suggests frame intervals for the {@code <nth>} argument of {@code /mcav browser create}, where 1 streams every
   * frame of the browser and 10 every tenth.
   *
   * @return the suggested intervals
   */
  @Suggestions("nth")
  public Stream<String> suggestNth() {
    return Stream.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
  }

  /**
   * Suggests frame rates for the {@code <targetFps>} argument of {@code /mcav vm create}. Higher rates feel smoother
   * but cost more CPU for dithering and more bandwidth for the maps.
   *
   * @return the suggested frame rates
   */
  @Suggestions("target-fps")
  public Stream<String> suggestTargetFps() {
    return Stream.of("20", "30", "40", "50", "60", "70", "80", "90", "100", "120", "144", "240");
  }
}
