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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import me.brandonli.mcav.capability.installer.vlc.UnsupportedOperatingSystemException;
import me.brandonli.mcav.capability.installer.vlc.VLCInstallationKit;
import me.brandonli.mcav.capability.installer.vlc.VLCInstaller;
import me.brandonli.mcav.testing.GeneratedMedia;
import org.junit.jupiter.api.Assumptions;
import org.mockito.Mockito;

/**
 * Loads the VLC installed on the machine for the integration tests and generates media for them.
 */
final class VlcTestSupport {

  private static final String UNAVAILABLE = loadVlc();

  private VlcTestSupport() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Loads VLC through the installation kit of the library, with an installer that refuses to download, so the tests
   * never need the internet.
   *
   * @return null if VLC was loaded, otherwise why not
   */
  private static String loadVlc() {
    final VLCInstaller installer = Mockito.mock(VLCInstaller.class);
    Mockito.when(installer.isSupported()).thenReturn(false);
    final VLCInstallationKit kit = VLCInstallationKit.create(installer);
    try {
      final Optional<Path> directory = kit.start();
      final Optional<String> location = directory.map(Path::toString);
      final String description = location.orElse("the libvlc found by vlcj");
      System.out.println("VLC integration tests use " + description);
      return null;
    } catch (final IOException | UnsupportedOperatingSystemException | LinkageError exception) {
      return "VLC is not installed: " + exception;
    }
  }

  /**
   * Skips the calling tests when VLC cannot be loaded.
   */
  static void assumeVlc() {
    Assumptions.assumeTrue(UNAVAILABLE == null, UNAVAILABLE);
  }

  /**
   * Generates a video of one color with a stereo sine tone.
   *
   * @param directory the directory to write into
   * @param color     an FFmpeg color name
   * @param width     the width in pixels
   * @param height    the height in pixels
   * @return the video file
   */
  static Path solidVideo(final Path directory, final String color, final int width, final int height) {
    final Path output = directory.resolve(color + "-" + width + "x" + height + ".mp4");
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
    final String colorSource = "color=c=" + color + ":size=" + width + "x" + height + ":rate=30";
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
