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
package me.brandonli.mcav.utils.ffmpeg;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.runtime.CommandTask;
import org.junit.jupiter.api.Test;

final class FfmpegCommandTest {

  private static final String INPUT_FILE_NAME = "in.mp4";
  private static final String OUTPUT_FILE_NAME = "out.mp4";

  private static final String VIDEO_CODEC = "libx264";
  private static final String AUDIO_CODEC = "aac";

  private static final String VIDEO_BITRATE = "1M";
  private static final String AUDIO_BITRATE = "128k";

  private static final int FRAME_RATE = 30;

  private static final int WIDTH = 640;
  private static final int HEIGHT = 360;

  private static final String FILTER = "scale=320:-1";

  private static final String NO_AUDIO_ARGUMENT = "-an";
  private static final String DURATION_ARGUMENT = "-t";
  private static final String DURATION_SECONDS = "5";

  private static final String VERSION_ARGUMENT = "-version";

  @Test
  void shouldEmitTheExecutableFromAnEmptyBuilder() {
    final FfmpegCommandBuilder builder = FfmpegCommand.builder();
    final FfmpegCommand command = builder.build();
    final Path executable = command.getExecutable();
    final String expected = executable.toString();
    final String[] actual = command.toCommandArray();
    assertArrayEquals(new String[] { expected }, actual);
  }

  @Test
  void shouldAddEveryArgumentInOrderInTheBuilder() {
    final FfmpegCommandBuilder builder = FfmpegCommand.builder();
    final FfmpegCommandBuilder returned = builder.addInput(INPUT_FILE_NAME);
    builder.addVideoCodec(VIDEO_CODEC);
    builder.addAudioCodec(AUDIO_CODEC);
    builder.addBitrate(VIDEO_BITRATE);
    builder.addAudioBitrate(AUDIO_BITRATE);
    builder.addFramerate(FRAME_RATE);
    builder.addResolution(WIDTH, HEIGHT);
    builder.addFilter(FILTER);
    builder.addArgument(NO_AUDIO_ARGUMENT);
    builder.addArguments(DURATION_ARGUMENT, DURATION_SECONDS);
    builder.addOverwrite();
    builder.addOutput(OUTPUT_FILE_NAME);
    final FfmpegCommand command = builder.build();
    final List<String> arguments = command.getArguments();
    final List<String> expected = List.of(
      "-i",
      INPUT_FILE_NAME,
      "-c:v",
      VIDEO_CODEC,
      "-c:a",
      AUDIO_CODEC,
      "-b:v",
      VIDEO_BITRATE,
      "-b:a",
      AUDIO_BITRATE,
      "-r",
      "30",
      "-s",
      "640x360",
      "-filter_complex",
      FILTER,
      NO_AUDIO_ARGUMENT,
      DURATION_ARGUMENT,
      DURATION_SECONDS,
      "-y",
      OUTPUT_FILE_NAME
    );
    assertSame(builder, returned);
    assertEquals(expected, arguments);
  }

  @Test
  void shouldReturnTheSameBuilderFromEveryBuilderMethod() {
    final FfmpegCommandBuilder builder = FfmpegCommand.builder();
    final FfmpegCommandBuilder afterInput = builder.addInput(INPUT_FILE_NAME);
    final FfmpegCommandBuilder afterOutput = builder.addOutput(OUTPUT_FILE_NAME);
    final FfmpegCommandBuilder afterVideoCodec = builder.addVideoCodec(VIDEO_CODEC);
    final FfmpegCommandBuilder afterAudioCodec = builder.addAudioCodec(AUDIO_CODEC);
    final FfmpegCommandBuilder afterBitrate = builder.addBitrate(VIDEO_BITRATE);
    final FfmpegCommandBuilder afterAudioBitrate = builder.addAudioBitrate(AUDIO_BITRATE);
    final FfmpegCommandBuilder afterFramerate = builder.addFramerate(FRAME_RATE);
    final FfmpegCommandBuilder afterResolution = builder.addResolution(WIDTH, HEIGHT);
    final FfmpegCommandBuilder afterOverwrite = builder.addOverwrite();
    final FfmpegCommandBuilder afterFilter = builder.addFilter(FILTER);
    final FfmpegCommandBuilder afterArgument = builder.addArgument(NO_AUDIO_ARGUMENT);
    final FfmpegCommandBuilder afterArguments = builder.addArguments(DURATION_ARGUMENT, DURATION_SECONDS);
    assertSame(builder, afterInput);
    assertSame(builder, afterOutput);
    assertSame(builder, afterVideoCodec);
    assertSame(builder, afterAudioCodec);
    assertSame(builder, afterBitrate);
    assertSame(builder, afterAudioBitrate);
    assertSame(builder, afterFramerate);
    assertSame(builder, afterResolution);
    assertSame(builder, afterOverwrite);
    assertSame(builder, afterFilter);
    assertSame(builder, afterArgument);
    assertSame(builder, afterArguments);
  }

  @Test
  void shouldStartTheCommandWithTheBundledExecutable() throws IOException {
    final FfmpegCommandBuilder builder = FfmpegCommand.builder();
    builder.addArgument(VERSION_ARGUMENT);
    final FfmpegCommand command = builder.build();
    final Path executable = command.getExecutable();
    final String executablePath = executable.toString();
    final String[] commandArray = command.toCommandArray();
    final String text = command.toString();
    final CommandTask task = command.createTask();
    final List<String> taskCommand = task.getCommand();
    final int exitCode = task.run();
    final String output = task.getOutput();
    final List<String> expectedTaskCommand = List.of(commandArray);
    final boolean printsTheVersion = output.startsWith("ffmpeg version");
    assertArrayEquals(new String[] { executablePath, VERSION_ARGUMENT }, commandArray);
    assertEquals(executablePath + " -version", text);
    assertEquals(expectedTaskCommand, taskCommand);
    assertEquals(0, exitCode);
    assertTrue(printsTheVersion, output);
  }

  @Test
  void shouldRunTheTaskWithTheEnvironmentOfTheInstalledLibraries() {
    final Path folder = Path.of("libraries");
    final List<Path> folders = List.of(folder);
    final FfmpegLibraries libraries = new FfmpegLibraries(Os.LINUX, folders);
    final List<String> arguments = List.of(VERSION_ARGUMENT);
    final FfmpegCommand command = new FfmpegCommand(arguments, () -> libraries);
    final Map<String, String> environment = command.getEnvironment();
    final CommandTask task = command.createTask();
    final Map<String, String> taskEnvironment = task.getEnvironment();
    final Path executable = command.getExecutable();
    final Map<String, String> expected = libraries.getEnvironment(executable);
    assertEquals(expected, environment);
    assertEquals(expected, taskEnvironment);
  }

  @Test
  void shouldCommandAreImmutable() {
    final FfmpegCommandBuilder builder = FfmpegCommand.builder();
    builder.addArgument(VERSION_ARGUMENT);
    final FfmpegCommand command = builder.build();
    builder.addArgument("-h");
    final List<String> arguments = command.getArguments();
    final List<String> expected = List.of(VERSION_ARGUMENT);
    assertEquals(expected, arguments);
    assertThrows(UnsupportedOperationException.class, () -> arguments.add("-x"));
  }

  @Test
  void shouldRejectInvalidArgumentsInTheBuilder() {
    final FfmpegCommandBuilder builder = FfmpegCommand.builder();
    assertThrows(IllegalArgumentException.class, () -> builder.addFramerate(0));
    assertThrows(IllegalArgumentException.class, () -> builder.addResolution(0, 10));
    assertThrows(IllegalArgumentException.class, () -> builder.addResolution(10, 0));
    assertThrows(NullPointerException.class, () -> builder.addInput(null));
    assertThrows(NullPointerException.class, () -> builder.addOutput(null));
    assertThrows(NullPointerException.class, () -> builder.addVideoCodec(null));
    assertThrows(NullPointerException.class, () -> builder.addAudioCodec(null));
    assertThrows(NullPointerException.class, () -> builder.addBitrate(null));
    assertThrows(NullPointerException.class, () -> builder.addAudioBitrate(null));
    assertThrows(NullPointerException.class, () -> builder.addFilter(null));
    assertThrows(NullPointerException.class, () -> builder.addArgument(null));
    assertThrows(NullPointerException.class, () -> builder.addArguments((String[]) null));
    assertThrows(NullPointerException.class, () -> builder.addArguments(DURATION_ARGUMENT, null));
  }

  @Test
  void shouldProvideAnExistingExecutableOnce() throws ReflectiveOperationException {
    final Path first = FfmpegExecutableProvider.getFfmpegPath();
    final Path second = FfmpegExecutableProvider.getFfmpegPath();
    final boolean exists = Files.isRegularFile(first);
    assertTrue(exists);
    assertSame(first, second);

    UtilityClassAssertions.assertNotInstantiable(FfmpegExecutableProvider.class);
  }

  @Test
  void shouldNotInstantiateTheHolder() throws ReflectiveOperationException {
    final Class<?>[] nestedClasses = FfmpegExecutableProvider.class.getDeclaredClasses();
    assertEquals(1, nestedClasses.length);

    UtilityClassAssertions.assertNotInstantiable(nestedClasses[0]);
  }
}
