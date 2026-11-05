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
package me.brandonli.mcav.testing;

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

// Media files made on first request with JavaCV's bundled FFmpeg, so no binary test files are committed; deleted when the JVM exits.
public final class TestMedia {

  public static final int VIDEO_WIDTH = 320;
  public static final int VIDEO_HEIGHT = 240;

  public static final int VIDEO_FRAME_RATE = 30;

  public static final int AUDIO_SAMPLE_RATE = 44_100;

  public static final int VIDEO_SECONDS = 5;

  public static final int IMAGE_WIDTH = 16;
  public static final int IMAGE_HEIGHT = 8;

  private static final int KEYFRAME_INTERVAL_FRAMES = VIDEO_FRAME_RATE;

  private static final int SEEK_RED_SECONDS = 3;
  private static final int SEEK_BLUE_SECONDS = 2;

  private static final int SILENT_VIDEO_SECONDS = 1;

  private static final int VIDEO_TONE_HERTZ = 440;
  private static final int AUDIO_TONE_HERTZ = 880;

  private static final int MONO_CHANNELS = 1;
  private static final int STEREO_CHANNELS = 2;

  private static final String TEST_PATTERN_INPUT =
    "-f lavfi -i testsrc=size=" + VIDEO_WIDTH + "x" + VIDEO_HEIGHT + ":rate=" + VIDEO_FRAME_RATE;
  private static final String MPEG4_VIDEO_OUTPUT = "-c:v mpeg4 -q:v 4 -pix_fmt yuv420p";
  private static final String SINE_INPUT = "-f lavfi -i sine=sample_rate=" + AUDIO_SAMPLE_RATE + ":frequency=";
  private static final String COLOR_INPUT =
    "-f lavfi -i color=c=%s:size=" + VIDEO_WIDTH + "x" + VIDEO_HEIGHT + ":rate=" + VIDEO_FRAME_RATE + ":duration=%s";
  private static final String ARGUMENT_SEPARATOR = " ";

  private static final String DIRECTORY_PREFIX = "mcav-test-media";
  private static final String CLEANUP_THREAD_NAME = "mcav-test-media-cleanup";
  private static final String VIDEO_FILE_NAME = "video.mp4";
  private static final String SEEK_VIDEO_FILE_NAME = "seek-video.mp4";
  private static final String SILENT_VIDEO_FILE_NAME = "silent.mp4";
  private static final String AUDIO_FILE_PREFIX = "audio-";
  private static final String AUDIO_FILE_EXTENSION = ".m4a";
  private static final String IMAGE_FILE_NAME = "image.png";

  private static final Path DIRECTORY = createDirectory();

  private TestMedia() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
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
    final String arguments = "%s %s%s -t %s %s -g %s -c:a aac -ac %s".formatted(
      TEST_PATTERN_INPUT,
      SINE_INPUT,
      VIDEO_TONE_HERTZ,
      VIDEO_SECONDS,
      MPEG4_VIDEO_OUTPUT,
      KEYFRAME_INTERVAL_FRAMES,
      MONO_CHANNELS
    );
    return generate(VIDEO_FILE_NAME, arguments);
  }

  public static synchronized Path seekVideo() {
    final String redInput = COLOR_INPUT.formatted("red", SEEK_RED_SECONDS);
    final String blueInput = COLOR_INPUT.formatted("blue", SEEK_BLUE_SECONDS);
    final String arguments = "%s %s -filter_complex [0:v][1:v]concat=n=2:v=1:a=0 %s -g %s -an".formatted(
      redInput,
      blueInput,
      MPEG4_VIDEO_OUTPUT,
      KEYFRAME_INTERVAL_FRAMES
    );
    return generate(SEEK_VIDEO_FILE_NAME, arguments);
  }

  public static synchronized Path silentVideo() {
    final String arguments = "%s -t %s %s -an".formatted(TEST_PATTERN_INPUT, SILENT_VIDEO_SECONDS, MPEG4_VIDEO_OUTPUT);
    return generate(SILENT_VIDEO_FILE_NAME, arguments);
  }

  public static synchronized Path audio(final double seconds) {
    Preconditions.checkArgument(seconds > 0, "Seconds %s must be positive!", seconds);

    final String length = String.valueOf(seconds);
    final String fileName = AUDIO_FILE_PREFIX + length + AUDIO_FILE_EXTENSION;
    final String arguments = "%s%s -t %s -c:a aac -ac %s".formatted(SINE_INPUT, AUDIO_TONE_HERTZ, length, STEREO_CHANNELS);
    return generate(fileName, arguments);
  }

  public static synchronized Path png() {
    final String arguments = "-f lavfi -i color=c=white:size=%sx%s -frames:v 1".formatted(IMAGE_WIDTH, IMAGE_HEIGHT);
    return generate(IMAGE_FILE_NAME, arguments);
  }

  private static Path generate(final String fileName, final String arguments) {
    final Path output = DIRECTORY.resolve(fileName);
    return GeneratedMedia.generate(output, temporary -> createCommand(arguments, temporary));
  }

  private static List<String> createCommand(final String arguments, final Path output) {
    final Path executable = GeneratedMedia.getFfmpegPath();
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
