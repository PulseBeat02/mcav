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
package me.brandonli.mcav.media.player.multimedia.vlc;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import me.brandonli.mcav.MCAVException;
import me.brandonli.mcav.capability.installer.vlc.DownloadedVlc;
import me.brandonli.mcav.testing.GeneratedMedia;
import org.junit.jupiter.api.Assumptions;

final class VlcTestSupport {

  private static final String UNAVAILABLE = loadVlc();

  private static final String VIDEO_EXTENSION = ".mp4";
  private static final String SIZE_SEPARATOR = "x";

  private VlcTestSupport() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  // the VLC of the installation kit, never this machine's, so every host behaves alike
  private static String loadVlc() {
    try {
      final Optional<Path> directory = DownloadedVlc.load();
      final Optional<String> location = directory.map(Path::toString);
      final String description = location.orElse("the libvlc vlcj loaded earlier");
      System.out.println("VLC integration tests use " + description);
      return null;
    } catch (final MCAVException | LinkageError exception) {
      return "VLC could not be installed or loaded: " + exception;
    }
  }

  static void assumeVlc() {
    Assumptions.assumeTrue(UNAVAILABLE == null, UNAVAILABLE);
  }

  static Path solidVideo(final Path directory, final String color, final int width, final int height) {
    final Path output = directory.resolve(color + "-" + width + SIZE_SEPARATOR + height + VIDEO_EXTENSION);
    return GeneratedMedia.generate(output, temporary -> solidVideoCommand(color, width, height, temporary));
  }

  private static List<String> solidVideoCommand(final String color, final int width, final int height, final Path output) {
    final Path executable = GeneratedMedia.ffmpegPath();
    final String executablePath = executable.toString();
    final String outputPath = output.toString();
    final List<String> arguments = solidVideoArguments(color, width, height, outputPath);
    final List<String> command = new ArrayList<>();
    command.add(executablePath);
    command.addAll(arguments);
    return command;
  }

  private static List<String> solidVideoArguments(final String color, final int width, final int height, final String outputPath) {
    final String colorSource = "color=c=" + color + ":size=" + width + SIZE_SEPARATOR + height + ":rate=30";
    return List.of(
      "-hide_banner",
      "-loglevel",
      "error",
      "-f",
      "lavfi",
      "-i",
      colorSource,
      "-f",
      "lavfi",
      "-i",
      "sine=frequency=440:sample_rate=48000",
      "-t",
      "3",
      "-c:v",
      "mpeg4",
      "-q:v",
      "2",
      "-pix_fmt",
      "yuv420p",
      "-c:a",
      "aac",
      "-ac",
      "2",
      "-y",
      outputPath
    );
  }
}
