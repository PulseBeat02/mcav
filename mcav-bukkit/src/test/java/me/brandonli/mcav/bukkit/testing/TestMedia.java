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
package me.brandonli.mcav.bukkit.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

// generated with the FFmpeg bundled with JavaCV, so no binary test file is committed
public final class TestMedia {

  private static final int VIDEO_WIDTH = 320;
  private static final int VIDEO_HEIGHT = 240;

  public static final int VIDEO_FRAME_RATE = 30;

  private static final int KEYFRAME_INTERVAL_FRAMES = 30;

  private static final int AUDIO_SAMPLE_RATE = 44_100;
  private static final int AUDIO_CHANNELS = 1;

  private static final int SINE_FREQUENCY = 440;

  public static final int VIDEO_SECONDS = 5;

  private static final String TEST_PATTERN_INPUT = "-f lavfi -i testsrc=size=%sx%s:rate=%s".formatted(
    VIDEO_WIDTH,
    VIDEO_HEIGHT,
    VIDEO_FRAME_RATE
  );
  private static final String MPEG4_VIDEO_OUTPUT = "-c:v mpeg4 -q:v 4 -pix_fmt yuv420p";
  private static final String SINE_INPUT = "-f lavfi -i sine=sample_rate=%s:frequency=%s".formatted(AUDIO_SAMPLE_RATE, SINE_FREQUENCY);
  private static final String ARGUMENT_SEPARATOR = " ";

  private static final String DIRECTORY_PREFIX = "mcav-test-media";
  private static final String VIDEO_FILE_NAME = "video.mp4";

  private static final String CLEANUP_THREAD_NAME = "mcav-test-media-cleanup";

  private static final Path DIRECTORY = createDirectory();

  private TestMedia() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  private static Path createDirectory() {
    try {
      final Path directory = Files.createTempDirectory(DIRECTORY_PREFIX);
      final Path absolute = directory.toAbsolutePath();
      final Runtime runtime = Runtime.getRuntime();
      final Thread cleanup = new Thread(() -> deleteRecursively(absolute), CLEANUP_THREAD_NAME);
      runtime.addShutdownHook(cleanup);
      return absolute;
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  private static void deleteRecursively(final Path directory) {
    try (final Stream<Path> paths = Files.walk(directory)) {
      final Comparator<Path> deepestFirst = Comparator.reverseOrder();
      final Stream<Path> sorted = paths.sorted(deepestFirst);
      final List<Path> ordered = sorted.toList();
      for (final Path path : ordered) {
        Files.deleteIfExists(path);
      }
    } catch (final IOException exception) {
      // leftovers in the temporary directory are harmless
    }
  }

  public static synchronized Path video() {
    final String arguments = "%s %s -t %s %s -g %s -c:a aac -ac %s".formatted(
      TEST_PATTERN_INPUT,
      SINE_INPUT,
      VIDEO_SECONDS,
      MPEG4_VIDEO_OUTPUT,
      KEYFRAME_INTERVAL_FRAMES,
      AUDIO_CHANNELS
    );
    return generate(VIDEO_FILE_NAME, arguments);
  }

  private static Path generate(final String fileName, final String arguments) {
    final Path output = DIRECTORY.resolve(fileName);
    return GeneratedMedia.generate(output, temporary -> createCommand(arguments, temporary));
  }

  private static List<String> createCommand(final String arguments, final Path output) {
    final Path executable = GeneratedMedia.ffmpegPath();
    final String executablePath = executable.toString();
    final String[] argumentArray = arguments.split(ARGUMENT_SEPARATOR);
    final List<String> argumentList = List.of(argumentArray);
    final String outputPath = output.toString();

    final List<String> command = new ArrayList<>();
    command.add(executablePath);
    command.add("-hide_banner");
    command.add("-loglevel");
    command.add("error");
    command.addAll(argumentList);
    command.add("-y");
    command.add(outputPath);
    return command;
  }
}
