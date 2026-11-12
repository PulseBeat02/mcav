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
package me.brandonli.mcav.plugin.command;

import java.util.List;
import java.util.stream.Stream;
import me.brandonli.mcav.bukkit.media.result.Characters;
import org.incendo.cloud.annotations.suggestion.Suggestions;

/**
 * Tab completions shared by the commands, referenced by name in their {@code @Argument(suggestions = ...)}.
 *
 * <p>This class is stateless and thread-safe.
 *
 * @since 1.0.0
 */
public final class SuggestionProvider implements AnnotationCommandFeature {

  /**
   * The name of the map id suggestions.
   *
   * @since 1.0.0
   */
  public static final String IDS = "ids";

  /**
   * The name of the size suggestions.
   *
   * @since 1.0.0
   */
  public static final String DIMENSIONS = "dimensions";

  /**
   * The name of the pixel resolution suggestions.
   *
   * @since 1.0.0
   */
  public static final String RESOLUTIONS = "resolutions";

  /**
   * The name of the chat window size suggestions.
   *
   * @since 1.0.0
   */
  public static final String CHAT_RESOLUTIONS = "chat-resolutions";

  /**
   * The name of the chat character suggestions.
   *
   * @since 1.0.0
   */
  public static final String CHAT_CHARACTERS = "chat-characters";

  /**
   * The name of the frame rate suggestions.
   *
   * @since 1.0.0
   */
  public static final String TARGET_FRAMES_PER_SECOND = "target-frames-per-second";

  /**
   * The name of the frame interval suggestions.
   *
   * @since 1.0.0
   */
  public static final String NTH = "nth";

  private static final List<String> ID_SUGGESTIONS = List.of("0", "5", "10", "100", "1000", "10000", "100000");

  private static final List<String> DIMENSION_SUGGESTIONS = List.of("4x4", "5x5", "16x9", "16x16", "32x18");

  private static final List<String> RESOLUTION_SUGGESTIONS = List.of("512x512", "640x640", "1280x720", "1280x1280", "1920x1080");

  private static final List<String> CHAT_RESOLUTION_SUGGESTIONS = List.of("8x8", "16x16", "32x32");

  private static final String QUOTE = "\"";

  private static final List<String> CHAT_CHARACTER_SUGGESTIONS = List.of(
    QUOTE + Characters.FULL_CHARACTER + QUOTE,
    QUOTE + Characters.BLACK_CIRCLE + QUOTE,
    QUOTE + Characters.SMALL_BLACK_SQUARE + QUOTE
  );

  private static final List<String> NTH_SUGGESTIONS = List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");

  private static final List<String> TARGET_FRAMES_PER_SECOND_SUGGESTIONS = List.of(
    "20",
    "30",
    "40",
    "50",
    "60",
    "70",
    "80",
    "90",
    "100",
    "120",
    "144",
    "240"
  );

  private static final List<String> POSITION_SUGGESTIONS = List.of("0", "+10", "-10", "+1:00", "-1:00", "1:30");

  private static final List<String> VOLUME_SUGGESTIONS = List.of("0", "25", "50", "75", "100", "150", "200");

  private static final List<String> SPEED_SUGGESTIONS = List.of("0.5", "0.75", "1", "1.25", "1.5", "2");

  /**
   * Creates the provider.
   *
   * @since 1.0.0
   */
  public SuggestionProvider() {}

  /**
   * Suggests starting map ids for the {@code <mapId>} argument of {@code /mcav screen} and the map commands. Any id
   * from 0 up can be typed; pick one whose following ids are not used by other maps on the server.
   *
   * @return the suggested ids
   * @since 1.0.0
   */
  @Suggestions(IDS)
  public Stream<String> suggestId() {
    return ID_SUGGESTIONS.stream();
  }

  /**
   * Suggests sizes for arguments counted in blocks, maps, or characters, such as {@code <blockDimensions>} of the
   * map commands or {@code <imageResolution>} of the block, entity, and scoreboard commands.
   *
   * @return the suggested sizes, written as {@code <width>x<height>}
   * @since 1.0.0
   */
  @Suggestions(DIMENSIONS)
  public Stream<String> suggestDimensions() {
    return DIMENSION_SUGGESTIONS.stream();
  }

  /**
   * Suggests pixel resolutions for the map commands and the browser and virtual machine screens, such as
   * {@code 640x640}, which fills a 5x5 wall of 128x128 pixel maps exactly.
   *
   * @return the suggested resolutions, written as {@code <width>x<height>}
   * @since 1.0.0
   */
  @Suggestions(RESOLUTIONS)
  public Stream<String> suggestResolutions() {
    return RESOLUTION_SUGGESTIONS.stream();
  }

  /**
   * Suggests small sizes that fit into the chat window, in characters per line and lines, for the
   * {@code <imageResolution>} and {@code <videoResolution>} arguments of the chat commands.
   *
   * @return the suggested sizes, written as {@code <width>x<height>}
   * @since 1.0.0
   */
  @Suggestions(CHAT_RESOLUTIONS)
  public Stream<String> suggestChatResolutions() {
    return CHAT_RESOLUTION_SUGGESTIONS.stream();
  }

  /**
   * Suggests the characters pixels are drawn with in chat, entity, and scoreboard displays: a full block, which
   * gives the most solid picture, a black circle, and a small black square. Each is in double quotes, because the
   * {@code <character>} argument reads only ASCII letters, digits, and {@code _-.+} without them.
   *
   * @return the suggested characters, each in double quotes
   * @since 1.0.0
   */
  @Suggestions(CHAT_CHARACTERS)
  public Stream<String> suggestChatCharacters() {
    return CHAT_CHARACTER_SUGGESTIONS.stream();
  }

  /**
   * Suggests frame intervals for the {@code <nth>} argument of {@code /mcav browser create}, where 1 streams every
   * frame of the browser and 10 every tenth.
   *
   * @return the suggested intervals
   * @since 1.0.0
   */
  @Suggestions(NTH)
  public Stream<String> suggestNth() {
    return NTH_SUGGESTIONS.stream();
  }

  /**
   * Suggests frame rates for the {@code <targetFramesPerSecond>} argument of {@code /mcav vm create}. Higher rates feel smoother
   * but cost more CPU for dithering and more bandwidth for the maps.
   *
   * @return the suggested frame rates
   * @since 1.0.0
   */
  @Suggestions(TARGET_FRAMES_PER_SECOND)
  public Stream<String> suggestTargetFramesPerSecond() {
    return TARGET_FRAMES_PER_SECOND_SUGGESTIONS.stream();
  }

  /**
   * Suggests positions for {@code /mcav video seek}: a time from the start of the video, or with a sign a jump from
   * the current position.
   *
   * @return the suggested positions
   * @since 1.0.0
   */
  @Suggestions("positions")
  public Stream<String> suggestPositions() {
    return POSITION_SUGGESTIONS.stream();
  }

  /**
   * Suggests volumes for {@code /mcav video volume}, in percent of the video's own loudness.
   *
   * @return the suggested volumes
   * @since 1.0.0
   */
  @Suggestions("volumes")
  public Stream<String> suggestVolumes() {
    return VOLUME_SUGGESTIONS.stream();
  }

  /**
   * Suggests speeds for {@code /mcav video speed}, as factors of the normal speed.
   *
   * @return the suggested speeds
   * @since 1.0.0
   */
  @Suggestions("speeds")
  public Stream<String> suggestSpeeds() {
    return SPEED_SUGGESTIONS.stream();
  }
}
